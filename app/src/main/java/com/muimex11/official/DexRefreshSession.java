package com.muimex11.official;

import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sets the rate apps render at in Samsung DeX by choosing SurfaceFlinger's pacesetter display
 * and unlocking DeX's high-refresh external desktop modes.
 */
final class DexRefreshSession {
    private static final long TICK_MS = 50;
    private static final long AUTO_RECONNECT_DELAY_MS = 10_000;
    private static final long AUTO_RECONNECT_GAP_MS = 10 * 60_000;
    private static final long RECONNECT_SETTLE_MS = 45_000;
    private static final long RECONNECT_CHECK_MS = 10_000;
    private static final long UNLOCK_GAP_MS = 60_000;
    private static final long ATTACH_SETTLE_MS = 3_000;
    private static final long TIMING_SETTLE_MS = 10_000;
    private static final float DEX_RATE = 61f;
    private static final long SHORT_LINK_MS = 20_000;
    private static final long VERIFY_MS = 5_000;
    private static final float LOWEST_RATE = 24f;
    private static final String PROP = "vendor.display.hdmi_cfg_idx";
    private static final String DP_DEX = "/sys/class/dp_sec/dex";
    private static final String DP_OFF = "ExtDispOff,1";
    private static final String DP_ON = "ExtDispOff,0";
    private static final String SHELL_LOCK = "/data/local/tmp/muimex-dexhz.lock";
    private static final String ROOT_LOCK_DIR = "/data/adb/dexhz";
    private static final File PROP_MARK = new File(ROOT_LOCK_DIR, "muimex-dexhz-start-mode.txt");
    private static final long MODE_DISCONNECT_MS = 4_000;
    private static final long MODE_RETURN_MS = 10_000;
    private static final long MODE_CONFIRM_MS = 20_000;
    private static final File START_FILE = new File("/data/local/tmp/muimex-dexhz-start-mode.txt");
    private static final long START_MATCH_MS = 6_000;
    private static final long START_JUDGE_MS = 1_500;
    private static final long REPLUG_MS = 60_000;
    private static final long RESCUE_WAIT_MS = 8_000;
    private static final float RESCUE_MIN_HZ = 200f;
    private static final float NEAR_LIMIT_HZ = 270f;
    private static final long SUGGEST_SETTLE_MS = 15_000;
    private static final long MIN_CHOICE_PIXELS = 1280L * 720;
    private static final long PACED_DROP_MS = 10_000;
    private static final long PACED_RETRY_MS = 15_000;
    private static final int PHONE_HOLD_TRIES = 3;

    private static final class Policy {
        final IBinder token;
        final Object original;
        final String applied;

        Policy(IBinder token, Object original, String applied) {
            this.token = token;
            this.original = original;
            this.applied = applied;
        }
    }

    static final class PhoneRate {
        private final DexDisplayControl.Mode mode;
        private final float hz;

        PhoneRate(DexDisplayControl.Mode mode, float hz) {
            this.mode = mode;
            this.hz = hz;
        }

        DexDisplayControl.Mode mode() { return mode; }
        float hz() { return hz; }
        boolean capped() { return hz < mode.hz() - 0.5f; }
    }

    static final class ModeTarget {
        private final long display;
        private final int width;
        private final int height;
        private final int hz;

        ModeTarget(long display, int width, int height, int hz) {
            this.display = display;
            this.width = width;
            this.height = height;
            this.hz = hz;
        }

        long display() { return display; }
        int width() { return width; }
        int height() { return height; }
        int hz() { return hz; }
        String key() { return width + ":" + height + ":" + hz; }
        String label() { return width + " × " + height + " at " + hz + " Hz"; }
    }

    private enum ModePhase { SWITCHING, REPLUG, CONFIRM }

    static final class LinkDropTracker {
        enum Event { NONE, LOST, REPEATED }

        private Long fastMonitor;
        private float fastHz;
        private long fastSince;
        private float lastLostHz;
        private final Map<Long, Float> lost = new HashMap<>();

        Event observe(Long leadId, float leadHz, Set<Long> present, long now, long lastUnlock) {
            Long previous = fastMonitor;
            float previousHz = fastHz;
            long since = fastSince;
            if (leadId != null && leadHz > DEX_RATE) {
                if (!leadId.equals(previous)) fastSince = now;
                fastMonitor = leadId;
                fastHz = leadHz;
            } else {
                fastMonitor = null;
            }
            if (previous == null || present.contains(previous)) return Event.NONE;
            if (since <= lastUnlock || now - since > SHORT_LINK_MS) return Event.NONE;
            lastLostHz = previousHz;
            return lost.put(previous, previousHz) != null ? Event.REPEATED : Event.LOST;
        }

        void pause() { fastMonitor = null; }
        boolean held(long displayId) { return lost.containsKey(displayId); }
        Float lostHz(long displayId) { return lost.get(displayId); }
        float lastLostHz() { return lastLostHz; }

        void reset() {
            fastMonitor = null;
            lost.clear();
        }
    }

    private final boolean root;
    private final String appId;
    private final DexDisplayConfig displayConfig;
    private final DexStartModeRacer racer;
    private volatile DexStartModeRacer.Target keptStart;
    private volatile DexStartModeRacer.Target switchStart;
    private int switchBaseline;
    private long switchStartedAt;
    private boolean switchToggled;
    private int switchTries;
    private boolean switchRescue;
    private long switchRejoinUntil;
    private DexDisplayControl.Info lastLead;
    private DexStartModeRacer.Attempt pendingStart;
    private final LinkDropTracker linkDrops = new LinkDropTracker();
    private final Map<Long, Policy> policies = new HashMap<>();
    private final Set<Long> seenMonitors = new HashSet<>();
    private final Map<Long, Long> connectedSince = new HashMap<>();
    private final Set<Long> autoReconnected = new HashSet<>();
    private final Map<Long, Float> limitedLinks = new HashMap<>();
    private final Set<Long> reconnectedLinks = new HashSet<>();
    private final Map<Long, String> keptModes = new HashMap<>();
    private final Set<Long> phonePaced = new HashSet<>();
    private final Set<Long> pacedRetried = new HashSet<>();
    private Long pacedMonitor;
    private long pacedSince;
    private volatile boolean noRenderCaps;
    private volatile boolean noPhoneHold;
    private int phoneHoldFailures;
    private final Map<Long, String> loggedModes = new HashMap<>();
    private final Map<Long, String> monitorNames = new HashMap<>();
    private final DexEventLog log = new DexEventLog();
    private String loggedRate;
    private String loggedSettings;

    private ScheduledExecutorService worker;
    private RandomAccessFile shellLock;
    private RandomAccessFile rootLock;
    private Long pacesetter;
    private boolean noPacesetter;
    private boolean pacesetterVerified;
    private volatile String writtenStartMode;
    private volatile ModeTarget modeTarget;
    private volatile ModePhase modePhase;
    private volatile long modeDeadline;
    private volatile String modeResult;
    private volatile String modeShortLabel;
    private volatile String modeMonitorKey;
    private final Set<String> failedModes = new HashSet<>();
    private volatile String rolledBack;
    private volatile boolean reconnectFailed;
    private String notice;
    private float appHz;
    private boolean pacesetterStale;
    private long lastAutoReconnect;
    private volatile long lastUnlock;
    private volatile boolean reconnecting;
    private volatile long reconnectEndedAt;
    private volatile boolean checkReconnect;
    private volatile boolean active;
    private volatile boolean highResOn;
    private boolean primed;
    private boolean waiting;
    private long verifiedAt;

    private volatile float targetHz = 1000f;
    private volatile boolean autoReconnect;
    private volatile boolean coolDown;

    private float thermalCap = Float.MAX_VALUE;
    private int thermalStatus = -1;
    private long thermalCheckedAt;
    private long coolingSince;
    private long lastClearAttempt;

    DexRefreshSession(boolean root, String appId) {
        this.root = root;
        this.appId = appId;
        displayConfig = DexDisplayConfig.open();
        racer = displayConfig != null ? new DexStartModeRacer(displayConfig) : null;
        keptStart = loadStart();
        noPacesetter = !DexDisplayControl.hasPacesetterCalls() || !DexDisplayControl.forcedPacesetter().supported();
        thermalStatus = DexDisplayControl.thermalStatus();
    }

    private void clearLeftoverStartMode() {
        try {
            if (!PROP_MARK.isFile()) return;
            String left = readFileText(PROP_MARK).trim();
            if (!left.isEmpty() && left.equals(readStartMode())) {
                writeStartMode("");
                log.add("Cleared a monitor start mode (" + left + ") left by an earlier service.");
            }
            //noinspection ResultOfMethodCallIgnored
            PROP_MARK.delete();
        } catch (Exception e) {
            log.add("Couldn't check for a leftover monitor start mode: " + DexDisplayControl.describe(e));
        }
    }

    DexEventLog log() { return log; }

    synchronized void configure(float target, boolean reconnect, boolean cool) {
        targetHz = target <= 0 ? 1000f : target;
        autoReconnect = reconnect;
        coolDown = cool;
        String settings = "Settings: rate " + (targetHz >= 1000f ? "fastest" : Math.round(targetHz) + " Hz")
                + ", Fix Slow Connections " + (reconnect ? "on" : "off") + ", Cool Down " + (cool ? "on" : "off");
        if (!settings.equals(loggedSettings)) {
            loggedSettings = settings;
            log.add(settings);
        }
        if (worker != null) worker.execute(this::tick);
    }

    synchronized String start(float target, boolean reconnect, boolean cool) {
        configure(target, reconnect, cool);
        if (worker != null && !active) return "High Refresh is turning off to restore DeX's standard modes. Try again in a moment.";
        if (worker != null) return "High refresh is on.";
        if (noPacesetter && !DexDisplayControl.canHoldPolicy()) {
            log.add("Couldn't start: no pacesetter calls and no display policy control.");
            return "This phone doesn't expose the display controls needed for DeX Hz Unlock.";
        }
        try {
            shellLock = lock(new File(SHELL_LOCK));
            if (shellLock == null) return "Another DeX Hz instance is already controlling the display.";
            if (root) {
                File dir = new File(ROOT_LOCK_DIR);
                if (dir.isDirectory() || dir.mkdirs()) {
                    rootLock = lock(new File(dir, "hzlock.lock"));
                    if (rootLock == null) {
                        closeLocks();
                        return "The standalone HzLock root daemon is running. Stop it first.";
                    }
                }
                if (rootLock != null) clearLeftoverStartMode();
                String existing = readStartMode();
                if (!existing.isEmpty()) log.add("The monitor start mode is already set to " + existing + " externally.");
            }
            rolledBack = null;
            modeResult = null;
            log.add("High refresh on. Monitor timing " + (noPacesetter ? "unavailable" : "available")
                    + " (calls " + DexDisplayControl.pacesetterCodes() + "), phone rates "
                    + (DexDisplayControl.canHoldPolicy() ? "available" : "unavailable") + ", " + (root ? "root" : "ADB shell") + ".");
            active = true;
            primed = false;
            armKeptStart();
            if (keptStart != null && racer != null) log.add("Monitor start mode: " + keptStart.label() + " (kept earlier).");
            unlockHighResolution("start");
            worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "dexhz-session"));
            worker.scheduleWithFixedDelay(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
            return "High refresh is on.";
        } catch (Exception e) {
            if (worker != null) worker.shutdownNow();
            worker = null;
            active = false;
            closeLocks();
            log.add("Couldn't turn on high refresh: " + DexDisplayControl.describe(e));
            return "Couldn't turn on high refresh. " + DexDisplayControl.describe(e);
        }
    }

    synchronized String stop(boolean restoreDex) {
        if (worker == null) return "High refresh is off.";
        worker.shutdownNow();
        worker = null;
        active = false;
        if (racer != null) racer.disarm();
        switchStart = null;
        pendingStart = null;
        lastLead = null;
        String problem = null;
        try {
            setPacesetter(null);
        } catch (Exception e) {
            problem = "the timing source";
        }
        for (Long id : new HashSet<>(policies.keySet())) {
            if (!release(id)) problem = "a display's refresh policy";
        }
        boolean restoreRootMode = !reconnecting && needsStandardModeReconnect();
        if (modeTarget != null) {
            log.add("Monitor mode switch to " + modeTarget.label() + " cancelled: high refresh turned off.");
            if (modeResult == null) modeResult = "Switch to " + modeTarget.label() + " cancelled.";
        }
        modeTarget = null;
        modePhase = null;
        if (!clearStartMode("high refresh off")) problem = "the monitor's start mode";
        if (restoreDex && highResOn && DexDisplayControl.hasHighResolutionSwitch()) {
            try {
                if (restoreRootMode) {
                    try {
                        write(DP_DEX, DP_OFF);
                        write(DP_DEX, "17");
                    } finally {
                        try {
                            DexDisplayControl.setHighResolution(false);
                        } finally {
                            write(DP_DEX, DP_ON);
                        }
                    }
                } else {
                    DexDisplayControl.setHighResolution(false);
                }
                log.add("Switched DeX back to its standard modes.");
            } catch (Exception e) {
                problem = "DeX's standard modes";
                log.add("Couldn't switch DeX back to its standard modes: " + DexDisplayControl.describe(e));
            }
        }
        highResOn = false;
        waiting = false;
        notice = null;
        appHz = 0;
        pacesetter = null;
        pacesetterStale = false;
        linkDrops.reset();
        phonePaced.clear();
        pacedRetried.clear();
        noPhoneHold = false;
        phoneHoldFailures = 0;
        pacedMonitor = null;
        seenMonitors.clear();
        connectedSince.clear();
        autoReconnected.clear();
        limitedLinks.clear();
        reconnectedLinks.clear();
        keptModes.clear();
        checkReconnect = false;
        loggedModes.clear();
        loggedRate = null;
        closeLocks();
        log.add(problem == null ? "High refresh off." : "High refresh off, but couldn't restore " + problem + ".");
        return problem == null ? "High refresh is off." : "High refresh is off, but couldn't restore " + problem + ".";
    }

    private void unlockHighResolution(String reason) {
        if (!DexDisplayControl.hasHighResolutionSwitch()) return;
        try {
            DexDisplayControl.setHighResolution(true);
            highResOn = true;
            lastUnlock = SystemClock.elapsedRealtime();
            log.add("Switched on DeX's high-refresh modes (" + reason + ").");
        } catch (Exception e) {
            log.add("DeX's high-refresh switch failed (" + reason + "): " + DexDisplayControl.describe(e));
        }
    }

    private synchronized void tick() {
        if (worker == null || !active) return;
        boolean modeChecked = false;
        DexDisplayControl.Info tickLead = null;
        try {
            long now = SystemClock.elapsedRealtime();
            List<DexDisplayControl.Info> displays;
            try {
                displays = DexDisplayControl.displays();
            } catch (Exception e) {
                modeChecked = true;
                checkModeSwitch(null, now);
                throw e;
            }
            DexDisplayControl.Info phone = phone(displays);
            DexDisplayControl.Info lead = lead(displays);
            tickLead = lead;
            Set<Long> present = new HashSet<>();
            boolean newMonitor = false;
            boolean unlockNew = false;
            for (DexDisplayControl.Info display : displays) {
                present.add(display.id());
                if (display.internal()) continue;
                String mode = describe(display.active());
                if (seenMonitors.add(display.id())) {
                    newMonitor = true;
                    if (!linkDrops.held(display.id()) && (!highResOn || display.active().hz() <= DEX_RATE)) unlockNew = true;
                    monitorNames.put(display.id(), display.name());
                    log.add("Monitor connected: " + display.name() + " " + mode + " (fastest "
                            + Math.round(display.fastest().hz()) + " Hz, " + display.modes().size() + " modes).");
                } else if (!mode.equals(loggedModes.get(display.id()))) {
                    log.add("Monitor mode changed: " + display.name() + " " + mode + ".");
                    connectedSince.put(display.id(), now);
                }
                loggedModes.put(display.id(), mode);
                if (!connectedSince.containsKey(display.id())) {
                    connectedSince.put(display.id(), primed ? now : now - ATTACH_SETTLE_MS);
                }
            }
            primed = true;
            for (Long id : seenMonitors) {
                if (!present.contains(id)) {
                    String mName = monitorNames.containsKey(id) ? monitorNames.get(id) : "external display";
                    log.add("Monitor disconnected: " + mName + ".");
                    loggedModes.remove(id);
                }
            }
            seenMonitors.retainAll(present);
            boolean settling = settling(now);
            Long dropped = pacedMonitor != null && !present.contains(pacedMonitor) ? pacedMonitor : null;
            if (dropped != null) pacedMonitor = null;
            boolean pacedDrop = dropped != null && now - pacedSince <= PACED_DROP_MS && !settling
                    && modeTarget == null && highResOn && phonePaced.add(dropped);
            if (pacedDrop) {
                linkDrops.pause();
                log.add("Android removed the monitor " + Math.max(0, (now - pacedSince) / 1000) + " s after app timing "
                        + "moved to it" + (pacedRetried.contains(dropped)
                        ? " again. The phone screen paces apps for this monitor from now on."
                        : ". The phone screen paces apps until the monitor stays connected for "
                        + PACED_RETRY_MS / 1000 + " s."));
            } else if (settling || modeTarget != null || !highResOn) {
                linkDrops.pause();
            } else {
                LinkDropTracker.Event event = linkDrops.observe(lead != null ? lead.id() : null,
                        lead != null ? lead.active().hz() : 0f, present, now, lastUnlock);
                if (event != LinkDropTracker.Event.NONE) forgetStartIfLost(linkDrops.lastLostHz());
                if (event == LinkDropTracker.Event.REPEATED) {
                    active = false;
                    rolledBack = "The monitor lost its high-refresh connection again within seconds of it starting. "
                            + "High Refresh turned off and requested DeX's standard modes.";
                    log.add(rolledBack);
                    new Thread(() -> stop(true), "dexhz-link-recovery").start();
                    return;
                }
                if (event == LinkDropTracker.Event.LOST) {
                    log.add("Android removed the monitor within " + SHORT_LINK_MS / 1000 + " s of a high-refresh connection"
                            + " (" + Math.round(linkDrops.lastLostHz()) + " Hz). Holding DeX high-refresh unlock for this monitor.");
                    rescue(now);
                }
            }
            if (lead != null) lastLead = lead;
            if (!settling) {
                connectedSince.keySet().retainAll(present);
                autoReconnected.retainAll(present);
                limitedLinks.keySet().retainAll(present);
                reconnectedLinks.retainAll(present);
                keptModes.keySet().retainAll(present);
            }
            if (newMonitor) {
                pacesetterStale = true;
                DexStartModeRacer.Attempt attempt = racer != null ? racer.last() : null;
                if (attempt != null && switchStart == null && keptStart != null && now - attempt.at() < 5_000
                        && (attempt.outcome() == DexStartModeRacer.Outcome.SET || attempt.outcome() == DexStartModeRacer.Outcome.ALREADY)) {
                    pendingStart = attempt;
                }
                if (unlockNew && !settling && (lastUnlock == 0 || now - lastUnlock > UNLOCK_GAP_MS)) {
                    lastUnlock = now;
                    unlockHighResolution("monitor attached");
                }
            }
            policies.keySet().retainAll(present);
            for (Long id : new HashSet<>(policies.keySet())) {
                if (phone == null || id != phone.id()) release(id);
            }
            updateThermal();

            if (lead == null) {
                if (phone != null) release(phone.id());
                setPacesetter(null);
                appHz = 0;
                waiting = false;
                logRate("No monitor; the phone runs at Android's own rates.");
            } else if (timingOnHold(lead, now)) {
                if (phone != null) release(phone.id());
                setPacesetter(null);
                appHz = 0;
                waiting = true;
                logRate("Waiting for the monitor to settle; app timing stays with Android.");
            } else {
                waiting = false;
                float link = lead.active().hz();
                float want = effectiveTarget();
                boolean throttled = want < targetHz;
                boolean phoneOnly = phonePaced.contains(lead.id());
                Long since = connectedSince.get(lead.id());
                if (phoneOnly && !pacedRetried.contains(lead.id()) && since != null && now - since >= PACED_RETRY_MS) {
                    phonePaced.remove(lead.id());
                    pacedRetried.add(lead.id());
                    phoneOnly = false;
                    log.add("The monitor stayed connected for " + PACED_RETRY_MS / 1000 + " s. App timing moves back to it.");
                }
                PhoneRate phoneRate = null;
                if (phone != null && canHoldPhone() && (noPacesetter || phoneOnly || want < link - 1f)) {
                    float ceiling = noPacesetter ? link + 1f : link - 1f;
                    phoneRate = phoneRateFor(phone, want, ceiling, !noRenderCaps);
                }
                if (phoneRate != null && !hold(phone.id(), DexDisplayControl.token(phone.id()), phoneRate)) {
                    phoneRate = null;
                    if (phone != null) release(phone.id());
                } else if (phoneRate != null) {
                    setPacesetter(phone.id());
                    pacedMonitor = null;
                    appHz = phoneRate.hz();
                    logRate(String.format(Locale.US, "Apps run at %d Hz, paced by the phone screen%s%s.",
                            Math.round(appHz), phoneRate.capped() ? " at " + Math.round(phoneRate.mode().hz()) + " Hz" : "",
                            throttled ? ", lowered for heat" : ""));
                } else if (phoneOnly) {
                    if (phone != null) release(phone.id());
                    setPacesetter(null);
                    appHz = phone != null ? phone.active().hz() : 60f;
                    logRate("App timing stays with Android for this monitor.");
                } else if (link <= DEX_RATE) {
                    if (phone != null) release(phone.id());
                    setPacesetter(null);
                    appHz = link;
                    logRate(String.format(Locale.US, "Monitor at %d Hz; app timing stays with Android.", Math.round(link)));
                } else {
                    if (phone != null) release(phone.id());
                    Long before = pacesetter;
                    setPacesetter(lead.id());
                    if (!noPacesetter && !Objects.equals(before, lead.id()) && Objects.equals(pacesetter, lead.id())) {
                        pacedMonitor = lead.id();
                        pacedSince = now;
                    }
                    appHz = noPacesetter ? (phone != null ? phone.active().hz() : 60f) : link;
                    logRate(String.format(Locale.US, "Apps run at %d Hz, paced by the %s.", Math.round(appHz),
                            noPacesetter ? "phone screen (no monitor timing)" : "monitor"));
                }
            }
            modeChecked = true;
            checkModeSwitch(lead, now);
            if (lead != null) {
                checkAfterReconnect(lead, now);
                if (needsReconnect(lead)) onSlowConnection(lead, now);
            }
            verifyPacesetter(now);
            if (notice != null && notice.startsWith("Display update failed")) notice = null;
        } catch (Throwable e) {
            notice = "Display update failed: " + DexDisplayControl.describe(e);
            log.add(notice);
            if (!modeChecked && active) {
                try {
                    checkModeSwitch(tickLead, SystemClock.elapsedRealtime());
                } catch (Throwable ignored) {
                    // The next tick tries again.
                }
            }
        }
    }

    private void logRate(String text) {
        if (text.equals(loggedRate)) return;
        loggedRate = text;
        log.add(text);
    }

    private static String describe(DexDisplayControl.Mode mode) {
        return mode.width() + "x" + mode.height() + " @ " + Math.round(mode.hz()) + " Hz";
    }

    private static DexDisplayControl.Info phone(List<DexDisplayControl.Info> displays) {
        DexDisplayControl.Info first = null;
        for (DexDisplayControl.Info display : displays) {
            if (!display.internal()) continue;
            if (display.on()) return display;
            if (first == null) first = display;
        }
        return first;
    }

    private static DexDisplayControl.Info lead(List<DexDisplayControl.Info> displays) {
        DexDisplayControl.Info lead = null;
        for (DexDisplayControl.Info display : displays) {
            if (!display.internal() && (lead == null || display.active().hz() > lead.active().hz())) lead = display;
        }
        return lead;
    }

    static PhoneRate phoneRateFor(DexDisplayControl.Info phone, float target, float ceiling, boolean caps) {
        PhoneRate best = null;
        PhoneRate slowest = null;
        for (PhoneRate rate : phoneRates(phone, ceiling, caps)) {
            if (slowest == null || rate.hz() < slowest.hz()) slowest = rate;
            if (rate.hz() <= target + 0.5f && (best == null || rate.hz() > best.hz())) best = rate;
        }
        return best != null ? best : slowest;
    }

    static List<PhoneRate> phoneRates(DexDisplayControl.Info phone, float ceiling, boolean caps) {
        Map<Integer, PhoneRate> byHz = new HashMap<>();
        for (DexDisplayControl.Mode mode : phone.modes()) {
            if (mode.id() < 0) continue;
            if (mode.width() != phone.active().width() || mode.height() != phone.active().height()) continue;
            for (int divisor = 1; divisor == 1 || caps; divisor++) {
                float hz = mode.hz() / divisor;
                if (hz < LOWEST_RATE - 0.5f) break;
                if (hz > ceiling || divisor > 1 && Math.abs(hz - Math.round(hz)) > 0.05f) continue;
                PhoneRate rate = new PhoneRate(mode, hz);
                int key = Math.round(hz);
                PhoneRate existing = byHz.get(key);
                if (existing == null || rate.mode().hz() < existing.mode().hz()) {
                    byHz.put(key, rate);
                }
            }
        }
        return new ArrayList<>(byHz.values());
    }

    private boolean timingOnHold(DexDisplayControl.Info lead, long now) {
        if (reconnecting) return true;
        long ended = reconnectEndedAt;
        if (ended != 0 && now - ended < TIMING_SETTLE_MS) return true;
        Long since = connectedSince.get(lead.id());
        return since != null && now - since < ATTACH_SETTLE_MS;
    }

    static boolean smooth(float linkHz, float hz) {
        float ratio = linkHz / hz;
        long whole = Math.round(ratio);
        return whole >= 1 && Math.abs(ratio - whole) < 0.02f;
    }

    private float effectiveTarget() {
        return coolDown ? Math.min(targetHz, thermalCap) : targetHz;
    }

    private void updateThermal() {
        long now = SystemClock.elapsedRealtime();
        if (now - thermalCheckedAt < 2_000) return;
        thermalCheckedAt = now;
        int status = DexDisplayControl.thermalStatus();
        if (status != thermalStatus) log.add("Thermal status " + thermalStatus + " -> " + status + ".");
        thermalStatus = status;
        float cap = thermalStatus >= 3 ? 60f : thermalStatus == 2 ? 120f : Float.MAX_VALUE;
        if (cap < thermalCap) {
            thermalCap = cap;
            coolingSince = 0;
        } else if (cap > thermalCap) {
            if (coolingSince == 0) coolingSince = now;
            else if (now - coolingSince > 30_000) {
                thermalCap = cap;
                coolingSince = 0;
            }
        } else {
            coolingSince = 0;
        }
    }

    private boolean canHoldPhone() {
        return !noPhoneHold && DexDisplayControl.canHoldPolicy();
    }

    private boolean hold(long id, IBinder token, PhoneRate rate) throws Exception {
        if (token == null) return true;
        DexDisplayControl.Mode mode = rate.mode();
        Object current = DexDisplayControl.readSpecs(token);
        if (DexDisplayControl.isPinned(current, mode.id(), mode.hz(), rate.hz())) return true;
        Object applied;
        try {
            DexDisplayControl.writeSpecs(token, DexDisplayControl.pinnedSpecs(token, mode.id(), mode.hz(), rate.hz()));
            applied = DexDisplayControl.readSpecs(token);
            if (!DexDisplayControl.isPinned(applied, mode.id(), mode.hz(), rate.hz())) {
                throw new IOException("SurfaceFlinger didn't keep " + Math.round(rate.hz()) + " Hz.");
            }
        } catch (Exception e) {
            try {
                DexDisplayControl.writeSpecs(token, current);
            } catch (Exception ignored) {
                // Restored on release or stop.
            }
            if (!rate.capped()) {
                if (++phoneHoldFailures >= PHONE_HOLD_TRIES) {
                    noPhoneHold = true;
                    log.add("SurfaceFlinger didn't keep the phone screen at " + Math.round(rate.hz()) + " Hz ("
                            + DexDisplayControl.describe(e) + "). App timing stays with Android.");
                }
                return false;
            }
            noRenderCaps = true;
            log.add("SurfaceFlinger didn't keep apps at " + Math.round(rate.hz()) + " Hz on the phone screen's "
                    + Math.round(mode.hz()) + " Hz mode. Offering only the phone screen's own modes.");
            return false;
        }
        phoneHoldFailures = 0;
        Policy previous = policies.get(id);
        Object original = previous != null && previous.token.equals(token)
                && previous.applied.equals(DexDisplayControl.signature(current)) ? previous.original : current;
        policies.put(id, new Policy(token, original, DexDisplayControl.signature(applied)));
        return true;
    }

    private boolean release(long id) {
        Policy policy = policies.remove(id);
        if (policy == null) return true;
        try {
            if (policy.token.equals(DexDisplayControl.token(id))
                    && policy.applied.equals(DexDisplayControl.signature(DexDisplayControl.readSpecs(policy.token)))) {
                DexDisplayControl.writeSpecs(policy.token, policy.original);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void setPacesetter(Long display) throws Exception {
        if (noPacesetter || (!pacesetterStale && Objects.equals(pacesetter, display))) return;
        pacesetterStale = false;
        try {
            if (display == null) DexDisplayControl.resetPacesetter();
            else DexDisplayControl.forcePacesetter(display);
        } catch (Exception e) {
            disablePacesetter();
            return;
        }
        DexDisplayControl.Forced after = DexDisplayControl.forcedPacesetter();
        if (!after.supported() || !Objects.equals(after.display(), display)) {
            disablePacesetter();
            return;
        }
        pacesetter = display;
        pacesetterVerified = true;
    }

    private void verifyPacesetter(long now) {
        if (noPacesetter || pacesetterStale || now - verifiedAt < VERIFY_MS) return;
        verifiedAt = now;
        DexDisplayControl.Forced forced = DexDisplayControl.forcedPacesetter();
        if (!forced.supported() || Objects.equals(forced.display(), pacesetter)) return;
        log.add("App timing was moved outside Muimex11 (now " + (forced.display() == null ? "Android's choice"
                : forced.display()) + "); setting it again.");
        pacesetterStale = true;
    }

    private void disablePacesetter() {
        log.add("SurfaceFlinger didn't confirm the forced pacesetter; pacing from the phone screen from now on.");
        noPacesetter = true;
        pacesetter = null;
        notice = "This phone didn't accept moving app timing to the monitor, so Muimex11 paces apps from the phone screen.";
    }

    private boolean needsReconnect(DexDisplayControl.Info display) {
        if (modeTarget != null || modeKey(display.active()).equals(keptModes.get(display.id()))) return false;
        return display.belowFastest() && targetHz > display.active().hz() + 1f && !limitedLinks.containsKey(display.id());
    }

    private boolean settling(long now) {
        long ended = reconnectEndedAt;
        return reconnecting || (ended != 0 && now - ended < RECONNECT_SETTLE_MS);
    }

    private void checkAfterReconnect(DexDisplayControl.Info display, long now) {
        if (!checkReconnect || reconnecting || now - reconnectEndedAt < RECONNECT_CHECK_MS) return;
        Long since = connectedSince.get(display.id());
        if (since != null && now - since < RECONNECT_CHECK_MS) return;
        checkReconnect = false;
        reconnectedLinks.add(display.id());
        if (modeKey(display.active()).equals(keptModes.get(display.id()))) {
            limitedLinks.remove(display.id());
            log.add("After reconnecting: " + describe(display.active()) + " (the chosen monitor mode).");
        } else if (display.belowFastest()) {
            limitedLinks.put(display.id(), display.active().hz());
            log.add("After reconnecting: still " + describe(display.active()) + " (fastest " + Math.round(display.fastest().hz())
                    + " Hz). The monitor starts in this mode; no more automatic reconnects for this plug-in.");
        } else {
            limitedLinks.remove(display.id());
            log.add("After reconnecting: " + describe(display.active()) + ".");
        }
    }

    private void onSlowConnection(DexDisplayControl.Info display, long now) {
        if (!autoReconnect || checkReconnect || settling(now) || autoReconnected.contains(display.id())
                || linkDrops.held(display.id())) return;
        Long since = connectedSince.get(display.id());
        if (since == null || now - since < AUTO_RECONNECT_DELAY_MS) return;
        if (lastAutoReconnect != 0 && now - lastAutoReconnect < AUTO_RECONNECT_GAP_MS) return;
        autoReconnected.add(display.id());
        lastAutoReconnect = now;
        reconnect("automatic, monitor at " + Math.round(display.active().hz()) + " of "
                + Math.round(display.fastest().hz()) + " Hz", false);
    }

    synchronized String reconnect() {
        if (modeTarget != null) return "Finish the monitor mode switch first.";
        String result = reconnect("manual", false);
        if (result.startsWith("Reconnecting.")) linkDrops.reset();
        return result;
    }

    private synchronized String reconnect(String reason, boolean modeSwitch) {
        if (worker == null || !active) return "Turn on High Refresh first.";
        if (reconnecting) return "Already reconnecting.";
        if (!(root && new File(DP_DEX).exists()) && !DexDisplayControl.hasHighResolutionSwitch()) {
            log.add("Reconnect (" + reason + ") unavailable: no DeX high-refresh switch.");
            return "Unplug the monitor and plug it back in.";
        }
        log.add("Reconnecting the monitor (" + reason + ").");
        reconnecting = true;
        checkReconnect = false;
        reconnectFailed = false;
        try {
            setPacesetter(null);
        } catch (Exception e) {
            if (modeSwitch) {
                reconnecting = false;
                return "Couldn't release monitor timing. " + DexDisplayControl.describe(e);
            }
            log.add("Couldn't release app timing before reconnecting: " + DexDisplayControl.describe(e));
        }
        ModeTarget requested = modeSwitch ? modeTarget : null;
        Thread thread = new Thread(() -> {
            boolean switchedOff = false;
            try {
                if (requested != null && switchStart != null) {
                    reconnectForStart(requested);
                } else if (requested != null) {
                    reconnectForMode(requested);
                } else if (root && new File(DP_DEX).exists()) {
                    write(DP_DEX, "17");
                    SystemClock.sleep(6_000);
                    if (active) write(DP_DEX, "0");
                } else {
                    DexDisplayControl.setHighResolution(false);
                    switchedOff = true;
                    SystemClock.sleep(5_000);
                    if (active) {
                        DexDisplayControl.setHighResolution(true);
                        highResOn = true;
                    }
                    switchedOff = false;
                }
            } catch (Exception e) {
                reconnectFailed = true;
                notice = "Reconnect failed. Unplug the monitor and plug it back in.";
                log.add("Reconnect failed: " + DexDisplayControl.describe(e));
            } finally {
                if (switchedOff && active) {
                    try {
                        DexDisplayControl.setHighResolution(true);
                    } catch (Exception ignored) {
                        // Reported above.
                    }
                }
                if (!modeSwitch) SystemClock.sleep(4_000);
                reconnectEndedAt = SystemClock.elapsedRealtime();
                lastUnlock = reconnectEndedAt;
                checkReconnect = !modeSwitch;
                reconnecting = false;
                log.add("Reconnect finished.");
            }
        }, "dexhz-reconnect");
        thread.start();
        return "Reconnecting. The monitor goes dark for a few seconds.";
    }

    private boolean canSwitchModes() { return rootModes() || shellModes(); }

    private boolean rootModes() {
        return root && DexDisplayControl.canReadModes() && DexDisplayControl.hasHighResolutionSwitch() && new File(DP_DEX).exists();
    }

    private boolean shellModes() {
        return !rootModes() && racer != null && DexDisplayControl.canReadModes() && DexDisplayControl.hasHighResolutionSwitch();
    }

    private boolean needsStandardModeReconnect() {
        if (!rootModes() || (modeTarget == null && keptModes.isEmpty())) return false;
        try {
            DexDisplayControl.Info current = lead(DexDisplayControl.displays());
            return current != null && current.active().hz() > DEX_RATE
                    && (modeTarget != null && modeTarget.display() == current.id() || keptModes.containsKey(current.id()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void reconnectForMode(ModeTarget requested) throws Exception {
        if (!active) return;
        DexDisplayControl.Info current = lead(DexDisplayControl.displays());
        if (current == null || current.id() != requested.display()) throw new IOException("The monitor changed.");
        try {
            write(DP_DEX, DP_OFF);
            long deadline = SystemClock.elapsedRealtime() + MODE_DISCONNECT_MS;
            while (active && lead(DexDisplayControl.displays()) != null) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("The driver did not disconnect the monitor.");
                SystemClock.sleep(100);
            }
            synchronized (this) {
                if (!active || modeTarget != requested) return;
                if (!readStartMode().isEmpty()) throw new IOException("A monitor start mode is already set.");
                markStartMode(requested.key());
                writtenStartMode = requested.key();
                try {
                    writeStartMode(requested.key());
                } catch (Exception e) {
                    clearStartMode("the start-mode write failed");
                    throw e;
                }
            }
        } finally {
            write(DP_DEX, DP_ON);
        }
    }

    static String modeKey(DexDisplayControl.Mode mode) {
        return mode.width() + ":" + mode.height() + ":" + Math.round(mode.hz());
    }

    static List<DexDisplayControl.Mode> modeChoices(DexDisplayControl.Info display) {
        Map<String, List<DexDisplayControl.Mode>> byKey = new LinkedHashMap<>();
        for (DexDisplayControl.Mode mode : display.modes()) {
            if (!Float.isFinite(mode.hz()) || mode.hz() < LOWEST_RATE - 0.5f
                    || mode.width() <= 0 || mode.height() <= 0) continue;
            String k = modeKey(mode);
            List<DexDisplayControl.Mode> list = byKey.get(k);
            if (list == null) {
                list = new ArrayList<>();
                byKey.put(k, list);
            }
            list.add(mode);
        }
        String activeKey = modeKey(display.active());
        List<DexDisplayControl.Mode> choices = new ArrayList<>();
        for (Map.Entry<String, List<DexDisplayControl.Mode>> entry : byKey.entrySet()) {
            if (entry.getValue().size() == 1 && !entry.getKey().equals(activeKey)) choices.add(entry.getValue().get(0));
        }
        DexDisplayControl.Mode active = display.active();
        Collections.sort(choices, (a, b) -> {
            boolean aSame = a.width() == active.width() && a.height() == active.height();
            boolean bSame = b.width() == active.width() && b.height() == active.height();
            if (aSame != bSame) return aSame ? -1 : 1;
            int bySize = Long.compare((long) b.width() * b.height(), (long) a.width() * a.height());
            if (bySize != 0) return bySize;
            if (a.width() != b.width()) return Integer.compare(b.width(), a.width());
            return Integer.compare(Math.round(b.hz()), Math.round(a.hz()));
        });
        return choices;
    }

    static boolean worthChoosing(DexDisplayControl.Info display, DexDisplayControl.Mode mode) {
        long pixels = (long) mode.width() * mode.height();
        if (pixels < MIN_CHOICE_PIXELS || mode.hz() <= DEX_RATE) return false;
        DexDisplayControl.Mode active = display.active();
        return pixels >= (long) active.width() * active.height() || mode.hz() > display.fastest().hz() + 1f;
    }

    static String shortLabel(ModeTarget target, DexDisplayControl.Mode now) {
        if (now != null && now.width() == target.width() && now.height() == target.height()) return target.hz() + " Hz";
        return target.width() + " × " + target.height() + ", " + target.hz() + " Hz";
    }

    static final class Suggestion {
        final DexDisplayControl.Mode mode;
        final String reason;

        Suggestion(DexDisplayControl.Mode mode, String reason) {
            this.mode = mode;
            this.reason = reason;
        }
    }

    private void suggest(DexDisplayControl.Info lead, JSONObject out) throws JSONException {
        long now = SystemClock.elapsedRealtime();
        if (reconnecting || settling(now) || now - lastUnlock < SUGGEST_SETTLE_MS) return;
        Long since = connectedSince.get(lead.id());
        if (since == null || now - since < SUGGEST_SETTLE_MS) return;
        String monitorKey = fastestKey(lead);
        Suggestion pick = suggestion(lead, monitorKey);
        if (pick == null) return;
        DexDisplayControl.Mode best = pick.mode;
        ModeTarget target = new ModeTarget(lead.id(), best.width(), best.height(), Math.round(best.hz()));
        JSONObject suggestion = new JSONObject();
        suggestion.put("width", target.width());
        suggestion.put("height", target.height());
        suggestion.put("hz", target.hz());
        suggestion.put("label", shortLabel(target, lead.active()));
        suggestion.put("reason", pick.reason);
        out.put("suggest", suggestion);
    }

    private Suggestion suggestion(DexDisplayControl.Info lead, String monitorKey) {
        DexDisplayControl.Mode active = lead.active();
        DexDisplayControl.Mode best = null;
        if (active.hz() <= DEX_RATE) {
            for (DexDisplayControl.Mode mode : modeChoices(lead)) {
                boolean sameSize = mode.width() == active.width() && mode.height() == active.height();
                if (!worthChoosing(lead, mode) || mode.hz() <= active.hz() + 1f || !sameSize && mode.height() < 1080
                        || failedModes.contains(monitorKey + "|" + modeKey(mode))) continue;
                if (best == null) {
                    best = mode;
                    continue;
                }
                boolean bestSame = best.width() == active.width() && best.height() == active.height();
                long pixels = (long) mode.width() * mode.height();
                long bestPixels = (long) best.width() * best.height();
                if (sameSize != bestSame ? sameSize
                        : pixels != bestPixels ? pixels > bestPixels : mode.hz() > best.hz()) {
                    best = mode;
                }
            }
            return best == null ? null : new Suggestion(best, "slow");
        }
        if (active.hz() < NEAR_LIMIT_HZ) return null;
        int want = Math.round(active.hz() / 2f);
        for (DexDisplayControl.Mode mode : modeChoices(lead)) {
            if (mode.width() == active.width() && mode.height() == active.height()
                    && Math.abs(Math.round(mode.hz()) - want) <= 1
                    && !failedModes.contains(monitorKey + "|" + modeKey(mode))) {
                return new Suggestion(mode, "fast");
            }
        }
        return null;
    }

    synchronized String switchMode(long displayId, int width, int height, int hz) {
        if (worker == null || !active) return "Turn on High Refresh first.";
        if (!canSwitchModes()) {
            return "Choosing the monitor's mode needs a Snapdragon phone (or root) and Samsung's DeX mode switch.";
        }
        boolean shell = !rootModes();
        if (!shell && rootLock == null) return "Couldn't lock the root display controls.";
        if (!highResOn) {
            return "DeX's high-refresh modes didn't switch on. Turn High Refresh off and on again.";
        }
        if (reconnecting || modeTarget != null) return "Wait for the current reconnect to finish.";
        DexDisplayControl.Info lead;
        try {
            lead = lead(DexDisplayControl.displays());
        } catch (Exception e) {
            return "Couldn't read the monitor's modes. " + DexDisplayControl.describe(e);
        }
        if (lead == null || lead.id() != displayId) return "The monitor changed. Choose its mode again.";
        boolean offered = false;
        for (DexDisplayControl.Mode mode : modeChoices(lead)) {
            if (mode.width() == width && mode.height() == height && Math.round(mode.hz()) == hz) offered = true;
        }
        ModeTarget target = new ModeTarget(lead.id(), width, height, hz);
        if (!offered) {
            log.add("Monitor mode " + target.key() + " refused: not one of the monitor's current choices.");
            return "The monitor doesn't offer " + target.label() + " right now. Choose again.";
        }
        modeShortLabel = shortLabel(target, lead.active());
        modeMonitorKey = fastestKey(lead);
        if (shell) {
            modeTarget = target;
            modePhase = ModePhase.SWITCHING;
            modeResult = null;
            log.add("Starting the monitor at " + target.label() + " instead of " + describe(lead.active()) + " (chosen by the user).");
            beginStartSwitch(lead, target, false);
            return "Switching to " + modeShortLabel + ".";
        }
        try {
            if (!clearStartMode("before another switch") || !readStartMode().isEmpty()) {
                return "Another monitor start mode is already set.";
            }
        } catch (Exception e) {
            return "Couldn't read the monitor's start mode. " + DexDisplayControl.describe(e);
        }
        modeTarget = target;
        modePhase = ModePhase.SWITCHING;
        modeResult = null;
        log.add("Switching the monitor from " + describe(lead.active()) + " to " + target.label() + " (chosen by the user).");
        String result = reconnect("monitor mode " + target.key(), true);
        if (!reconnecting) {
            modeTarget = null;
            modePhase = null;
            clearStartMode("reconnect didn't start");
            return result;
        }
        return "Switching to " + modeShortLabel + ".";
    }

    synchronized String confirmMode(boolean keep) {
        if (!active) return "The switch is being undone.";
        ModeTarget target = modeTarget;
        if (target == null) return "There's no monitor mode switch to confirm.";
        if (!keep && switchStart != null && modePhase != ModePhase.CONFIRM && racer.matches() == switchBaseline) {
            cancelStartSwitch("Cancelled. Nothing changed.");
            return "Cancelled.";
        }
        if (!keep) {
            rollBack("undone");
            return "Undoing the switch. High Refresh turns off and DeX goes back to its standard modes.";
        }
        if (modePhase != ModePhase.CONFIRM) return "Wait until the monitor is back.";
        try {
            DexDisplayControl.Info lead = lead(DexDisplayControl.displays());
            if (lead == null || lead.id() != target.display() || !modeKey(lead.active()).equals(target.key())) {
                return "The monitor changed. This switch will be undone.";
            }
            keptModes.put(lead.id(), modeKey(lead.active()));
            DexStartModeRacer.Target start = switchStart;
            if (start != null) {
                keptStart = start;
                saveStart(start);
                switchStart = null;
                racer.arm(start, 0);
                String name = modeShortLabel != null ? modeShortLabel : start.label();
                modeResult = "Kept. The monitor starts at " + name + " each time.";
            } else {
                modeResult = "Kept until the monitor reconnects.";
            }
            log.add("Kept the monitor mode " + describe(lead.active()) + ".");
        } catch (Exception e) {
            return "Couldn't read the monitor. " + DexDisplayControl.describe(e);
        }
        modeTarget = null;
        modePhase = null;
        return "Kept.";
    }

    private void checkModeSwitch(DexDisplayControl.Info lead, long now) {
        ModeTarget target = modeTarget;
        if (target == null) {
            if (writtenStartMode != null && now - lastClearAttempt > VERIFY_MS) {
                lastClearAttempt = now;
                clearStartMode("retry");
            }
            checkKeptStart(lead, now);
            return;
        }
        if (switchStart != null) {
            checkStartSwitch(target, lead, now);
            return;
        }
        if (reconnecting) return;
        if (lead != null && lead.id() != target.display()) {
            clearStartMode("a different monitor attached");
            modeTarget = null;
            modePhase = null;
            modeResult = "A different monitor connected. Switch cancelled.";
            log.add(modeResult);
            return;
        }
        if (modePhase == ModePhase.SWITCHING) {
            if (reconnectFailed) {
                rollBack("the reconnect failed");
                return;
            }
            long ended = reconnectEndedAt;
            if (lead == null) {
                if (now - ended >= MODE_RETURN_MS) rollBack("the monitor didn't come back");
                return;
            }
            if (!clearStartMode("switch checked")) {
                rollBack("the start mode could not be cleared");
                return;
            }
            DexDisplayControl.Mode came = lead.active();
            String arrived = new ModeTarget(lead.id(), came.width(), came.height(), Math.round(came.hz())).label();
            boolean matched = modeKey(came).equals(target.key());
            if (!matched) {
                rollBack("the monitor came back at " + arrived);
                return;
            }
            log.add("The monitor came back at " + arrived + ". Waiting for the user to keep it.");
            modePhase = ModePhase.CONFIRM;
            modeDeadline = now + MODE_CONFIRM_MS;
        } else if (lead == null) {
            rollBack("the monitor disconnected");
        } else if (now >= modeDeadline) {
            rollBack("not kept in time");
        }
    }

    private synchronized void rollBack(String reason) {
        ModeTarget target = modeTarget;
        if (target == null) return;
        if (!active) return;
        active = false;
        clearStartMode("switch undone");
        String monitor = modeMonitorKey;
        if (monitor != null) failedModes.add(monitor + "|" + target.key());
        String name = modeShortLabel != null ? modeShortLabel : target.label();
        rolledBack = "High Refresh turned off to undo " + name + " (" + reason + "). DeX is back in its standard modes.";
        modeResult = rolledBack;
        log.add("Undoing the monitor mode switch (" + reason + "): turning high refresh off.");
        new Thread(() -> stop(true), "dexhz-undo").start();
    }

    private boolean clearStartMode(String why) {
        String written = writtenStartMode;
        if (written == null) return true;
        try {
            if (written.equals(readStartMode())) {
                writeStartMode("");
                log.add("Cleared the monitor start mode " + written + " (" + why + ").");
            }
            writtenStartMode = null;
            //noinspection ResultOfMethodCallIgnored
            PROP_MARK.delete();
            return true;
        } catch (Exception e) {
            log.add("Couldn't clear the monitor start mode " + written + " (" + why + "): " + DexDisplayControl.describe(e));
            return false;
        }
    }

    private static void markStartMode(String value) throws IOException {
        writeFileText(PROP_MARK, value);
    }

    private void armKeptStart() {
        DexStartModeRacer.Target start = keptStart;
        if (racer != null && start != null && shellModes()) racer.arm(start, DexStartModeRacer.AFTER_DISCONNECT_MS);
    }

    static String fastestKey(DexDisplayControl.Info display) {
        DexDisplayControl.Mode best = null;
        for (DexDisplayControl.Mode mode : display.modes()) {
            int hz = Math.round(mode.hz());
            int bestHz = best == null ? -1 : Math.round(best.hz());
            if (best == null || hz > bestHz
                    || hz == bestHz && (long) mode.width() * mode.height() > (long) best.width() * best.height()) {
                best = mode;
            }
        }
        return best == null ? "" : best.width() + "x" + best.height() + "@" + Math.round(best.hz());
    }

    private void beginStartSwitch(DexDisplayControl.Info monitor, ModeTarget target, boolean rescue) {
        DexStartModeRacer.Target start = new DexStartModeRacer.Target(target.width(), target.height(), target.hz(), fastestKey(monitor));
        switchStart = start;
        switchBaseline = racer.matches();
        switchStartedAt = SystemClock.elapsedRealtime();
        switchToggled = false;
        switchTries = 0;
        switchRescue = rescue;
        switchRejoinUntil = 0;
        racer.arm(start, REPLUG_MS);
    }

    private void reconnectForStart(ModeTarget requested) throws Exception {
        if (!active) return;
        int baseline = racer.matches();
        boolean off = false;
        try {
            DexDisplayControl.setHighResolution(false);
            off = true;
            SystemClock.sleep(5_000);
        } finally {
            if (off && active) {
                DexDisplayControl.setHighResolution(true);
                highResOn = true;
                lastUnlock = SystemClock.elapsedRealtime();
            }
        }
        long until = SystemClock.elapsedRealtime() + START_MATCH_MS;
        while (active && modeTarget == requested && racer.matches() == baseline && SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(50);
        }
    }

    private void checkStartSwitch(ModeTarget target, DexDisplayControl.Info lead, long now) {
        if (modePhase == ModePhase.CONFIRM) {
            if (lead == null) {
                modePhase = ModePhase.SWITCHING;
                switchBaseline = racer.matches();
                switchToggled = true;
                switchRejoinUntil = now + MODE_RETURN_MS;
                log.add("The monitor disconnected while waiting to be kept. Waiting up to " + MODE_RETURN_MS / 1000
                        + " s for it to come back in " + target.label() + ".");
            } else if (!modeKey(lead.active()).equals(target.key())) rollBack("the monitor changed to " + describe(lead.active()));
            else if (now >= modeDeadline) rollBack("not kept in time");
            return;
        }
        if (reconnecting) return;
        if (switchToggled && reconnectFailed) {
            rollBack("the reconnect failed");
            return;
        }
        DexStartModeRacer.Attempt attempt = racer.last();
        int matched = racer.matches();
        if (matched > switchBaseline && attempt != null) {
            if (now - attempt.at() < START_JUDGE_MS) return;
            if (lead == null) {
                if (now - attempt.at() >= MODE_RETURN_MS) rollBack("the monitor didn't come back");
                return;
            }
            switchRejoinUntil = 0;
            if (lead.id() != target.display()) {
                cancelStartSwitch("A different monitor connected. Switch cancelled.");
                return;
            }
            String took = String.format(Locale.US, "%.1f ms", attempt.micros() / 1000f);
            if (modeKey(lead.active()).equals(target.key())) {
                log.add("The monitor started at " + target.label() + " (set " + took + " after the composer saw it). "
                        + "Waiting for the user to keep it.");
                modePhase = ModePhase.CONFIRM;
                modeDeadline = now + MODE_CONFIRM_MS;
                if (switchRescue) showApp();
                return;
            }
            log.add("SurfaceFlinger read the monitor's own mode (" + describe(lead.active()) + ") before " + target.label()
                    + " was set, " + took + " after the connection.");
            if (++switchTries < 2) {
                switchBaseline = matched;
                switchToggled = false;
                switchStartedAt = now - RESCUE_WAIT_MS;
                return;
            }
            rollBack("the phone read the monitor too early, twice");
            return;
        }
        if (modePhase == ModePhase.REPLUG) {
            if (now >= modeDeadline) {
                cancelStartSwitch("The monitor wasn't plugged back in. Nothing changed.");
            }
            return;
        }
        if (!switchToggled) {
            if (switchRescue && now - switchStartedAt < RESCUE_WAIT_MS) return;
            switchToggled = true;
            String result = reconnect("monitor start mode " + target.key(), true);
            if (!reconnecting) {
                log.add("Couldn't reconnect the monitor for its start mode: " + result);
                askReplug(now);
            }
            return;
        }
        if (switchRejoinUntil != 0) {
            if (now >= switchRejoinUntil) rollBack("the monitor didn't come back");
            return;
        }
        askReplug(now);
    }

    private void showApp() {
        new Thread(() -> {
            try {
                DexDisplayControl.run(5, "/system/bin/am", "start", "-n", appId + "/.MainActivity");
            } catch (Exception e) {
                log.add("Couldn't open Muimex11 for the Keep prompt: " + DexDisplayControl.describe(e));
            }
        }, "dexhz-show").start();
    }

    private void askReplug(long now) {
        modePhase = ModePhase.REPLUG;
        modeDeadline = now + REPLUG_MS;
        racer.arm(switchStart, REPLUG_MS);
        log.add("The monitor didn't reconnect. Waiting up to " + REPLUG_MS / 1000 + " s for it to be unplugged and plugged back in.");
    }

    private void cancelStartSwitch(String message) {
        modeTarget = null;
        modePhase = null;
        switchStart = null;
        DexStartModeRacer.Target start = keptStart;
        if (start != null && shellModes()) racer.arm(start, 0);
        else if (racer != null) racer.disarm();
        modeResult = message;
        log.add(message);
    }

    private void checkKeptStart(DexDisplayControl.Info lead, long now) {
        DexStartModeRacer.Attempt attempt = pendingStart;
        if (attempt == null || now - attempt.at() < START_JUDGE_MS) return;
        pendingStart = null;
        if (lead == null) return;
        DexStartModeRacer.Target start = attempt.target();
        String took = String.format(Locale.US, "%.1f ms", attempt.micros() / 1000f);
        DexDisplayControl.Mode read = lead.active();
        if (modeKey(read).equals(start.key())) {
            keptModes.put(lead.id(), start.key());
            log.add("Started the monitor at " + start.label() + " (start mode, set " + took + " after the composer saw it).");
            return;
        }
        if (read.width() == start.width() && read.height() == start.height() && smooth(read.hz(), start.hz())) {
            log.add("SurfaceFlinger read " + describe(read) + " before the start mode landed (" + took + "). The monitor runs at "
                    + start.label() + ", which divides it evenly, so keeping it.");
            return;
        }
        try {
            int index = composerIndex(read, attempt.previousIndex());
            if (index >= 0) displayConfig.set(index);
            log.add("SurfaceFlinger read " + describe(read) + " before the start mode landed (" + took + "). Put the monitor "
                    + "back in that mode so app timing matches. Replug it to try again.");
        } catch (Exception e) {
            log.add("Couldn't put the monitor back at " + describe(read) + ": " + DexDisplayControl.describe(e));
        }
    }

    private int composerIndex(DexDisplayControl.Mode mode, int preferred) throws android.os.RemoteException {
        int hz = Math.round(mode.hz());
        DexDisplayConfig.Config at = preferred >= 0 ? displayConfig.config(preferred) : null;
        if (at != null && at.is(mode.width(), mode.height(), hz)) return preferred;
        for (DexDisplayConfig.Config config : displayConfig.configs()) {
            if (config.is(mode.width(), mode.height(), hz)) return config.index();
        }
        return -1;
    }

    private void rescue(long now) {
        DexDisplayControl.Info monitor = lastLead;
        float lost = linkDrops.lastLostHz();
        if (!active || !shellModes() || modeTarget != null || monitor == null || lost < RESCUE_MIN_HZ) return;
        DexStartModeRacer.Target start = keptStart;
        if (start != null && start.fastestKey().equals(fastestKey(monitor))) return;
        int want = Math.round(lost / 2f);
        DexDisplayControl.Mode half = null;
        for (DexDisplayControl.Mode mode : modeChoices(monitor)) {
            if (mode.width() == monitor.active().width() && mode.height() == monitor.active().height()
                    && Math.abs(Math.round(mode.hz()) - want) <= 1) {
                half = mode;
                break;
            }
        }
        if (half == null) return;
        ModeTarget target = new ModeTarget(monitor.id(), half.width(), half.height(), Math.round(half.hz()));
        modeTarget = target;
        modePhase = ModePhase.SWITCHING;
        modeResult = null;
        modeShortLabel = shortLabel(target, monitor.active());
        modeMonitorKey = fastestKey(monitor);
        log.add("The monitor dropped at " + Math.round(lost) + " Hz. Starting it at " + target.label()
                + " on its next connection; keep that mode within " + MODE_CONFIRM_MS / 1000 + " s once it shows a picture.");
        beginStartSwitch(monitor, target, true);
    }

    private void forgetStartIfLost(float lostHz) {
        DexStartModeRacer.Target start = keptStart;
        if (start == null || Math.abs(start.hz() - lostHz) > 1f) return;
        log.add("The monitor dropped within seconds at its start mode " + start.label() + ". Forgot that start mode.");
        failedModes.add(start.fastestKey() + "|" + start.key());
        forgetStart();
    }

    private void forgetStart() {
        keptStart = null;
        //noinspection ResultOfMethodCallIgnored
        START_FILE.delete();
        if (racer != null && switchStart == null) racer.disarm();
    }

    synchronized String forgetStartMode() {
        DexStartModeRacer.Target start = keptStart;
        if (start == null) return "Not setting a monitor start mode.";
        if (modeTarget != null) return "Finish the monitor mode switch first.";
        forgetStart();
        keptModes.values().removeIf(start.key()::equals);
        log.add("Forgot the monitor start mode " + start.label() + ".");
        modeResult = "Back to the monitor's own mode from its next connection.";
        return "Back to the monitor's own mode from its next connection.";
    }

    private static DexStartModeRacer.Target loadStart() {
        try {
            if (!START_FILE.isFile()) return null;
            return DexStartModeRacer.Target.parse(readFileText(START_FILE));
        } catch (Exception e) {
            return null;
        }
    }

    private void saveStart(DexStartModeRacer.Target start) {
        try {
            writeFileText(START_FILE, start.serialize());
        } catch (Exception e) {
            log.add("Couldn't save the start mode; it lasts until High Refresh turns off: " + DexDisplayControl.describe(e));
        }
    }

    synchronized JSONObject state() throws JSONException {
        JSONObject json = new JSONObject();
        boolean running = worker != null;
        json.put("running", running);
        json.put("reconnecting", reconnecting);
        if (running && waiting) json.put("waiting", true);
        json.put("targetHz", targetHz);
        if (running && appHz > 0) json.put("appHz", appHz);
        if (running && coolDown && thermalCap < targetHz) json.put("coolingHz", thermalCap);
        if (notice != null) json.put("notice", notice);

        JSONObject caps = new JSONObject();
        caps.put("highRes", DexDisplayControl.hasHighResolutionSwitch());
        caps.put("monitorTiming", !noPacesetter);
        caps.put("monitorTimingVerified", pacesetterVerified);
        caps.put("phoneRates", canHoldPhone());
        caps.put("heat", thermalStatus >= 0);
        caps.put("codes", DexDisplayControl.pacesetterCodes());
        caps.put("root", root);
        boolean modeControl = canSwitchModes();
        caps.put("monitorModes", modeControl);
        caps.put("modeMethod", rootModes() ? "root" : shellModes() ? "start" : "none");
        caps.put("displayConfig", racer != null);
        json.put("caps", caps);

        ModeTarget target = modeTarget;
        if (target != null) {
            JSONObject switching = new JSONObject();
            switching.put("label", target.label());
            String shortName = modeShortLabel;
            switching.put("short", shortName != null ? shortName : target.label());
            if (switchRescue && switchStart != null) switching.put("lostHz", Math.round(linkDrops.lastLostHz()));
            boolean confirm = modePhase == ModePhase.CONFIRM;
            switching.put("confirm", confirm);
            boolean replug = modePhase == ModePhase.REPLUG;
            if (replug) switching.put("replug", true);
            if (switchRescue && switchStart != null) switching.put("rescue", true);
            if (confirm || replug) {
                long left = modeDeadline - SystemClock.elapsedRealtime();
                switching.put("secondsLeft", Math.max(0, (left + 999) / 1000));
            }
            json.put("modeSwitch", switching);
        }
        if (modeResult != null) json.put("modeResult", modeResult);
        if (!running && rolledBack != null) json.put("rolledBack", rolledBack);

        try {
            List<DexDisplayControl.Info> displays = DexDisplayControl.displays();
            DexDisplayControl.Info phone = phone(displays);
            DexDisplayControl.Info lead = lead(displays);
            if (lead != null) {
                if (running && !describe(lead.active()).equals(loggedModes.get(lead.id()))) json.put("waiting", true);
                float link = lead.active().hz();
                TreeSet<Integer> stops = new TreeSet<>();
                boolean phoneOnly = running && phonePaced.contains(lead.id());
                if (!noPacesetter && !phoneOnly) stops.add(Math.round(link));
                if (phone != null && canHoldPhone()) {
                    for (PhoneRate rate : phoneRates(phone, noPacesetter ? link + 1f : link - 1f, !noRenderCaps)) {
                        stops.add(Math.round(rate.hz()));
                    }
                }
                if (stops.isEmpty()) stops.add(Math.round(noPacesetter && phone != null ? Math.min(phone.fastest().hz(), link) : link));
                json.put("stops", new JSONArray(stops));

                JSONObject monitor = new JSONObject();
                monitor.put("name", lead.name());
                monitor.put("width", lead.active().width());
                monitor.put("height", lead.active().height());
                monitor.put("linkHz", link);
                monitor.put("fastestHz", lead.fastest().hz());
                monitor.put("fasterListed", lead.belowFastest());
                if (checkReconnect || reconnectedLinks.contains(lead.id())) monitor.put("reconnected", true);
                Float limited = limitedLinks.get(lead.id());
                if (limited != null && lead.belowFastest()) monitor.put("limitedHz", limited);
                Float lost = linkDrops.lostHz(lead.id());
                if (running && lost != null) monitor.put("lostHz", lost);
                if (phoneOnly) {
                    monitor.put("phonePaced", true);
                    if (!pacedRetried.contains(lead.id())) monitor.put("phonePacedRetry", true);
                }
                describeModes(lead, monitor);
                if (modeControl) {
                    monitor.put("id", lead.id());
                    monitor.put("modeKey", modeKey(lead.active()));
                    JSONArray choices = new JSONArray();
                    for (DexDisplayControl.Mode mode : modeChoices(lead)) {
                        if (!worthChoosing(lead, mode)) continue;
                        JSONObject choice = new JSONObject();
                        choice.put("width", mode.width());
                        choice.put("height", mode.height());
                        choice.put("hz", Math.round(mode.hz()));
                        choices.put(choice);
                    }
                    monitor.put("modeChoices", choices);
                    String kept = keptModes.get(lead.id());
                    if (kept != null && !kept.equals(modeKey(lead.active()))) kept = null;
                    if (kept != null) monitor.put("keptMode", kept);
                    DexStartModeRacer.Target start = keptStart;
                    boolean startKept = start != null && racer != null && start.fastestKey().equals(fastestKey(lead));
                    if (startKept) {
                        monitor.put("startMode", start.label());
                        monitor.put("startModeKey", start.key());
                    }
                    if (running && target == null && kept == null && !startKept) suggest(lead, monitor);
                }
                json.put("monitor", monitor);
            }
        } catch (Exception e) {
            json.put("notice", "Couldn't read the displays: " + DexDisplayControl.describe(e));
        }
        return json;
    }

    private static void describeModes(DexDisplayControl.Info display, JSONObject out) throws JSONException {
        DexDisplayControl.Mode active = display.active();
        Map<String, Integer> fastestBySize = new LinkedHashMap<>();
        List<DexDisplayControl.Mode> sorted = new ArrayList<>(display.modes());
        Collections.sort(sorted, (a, b) -> Long.compare((long) b.width() * b.height(), (long) a.width() * a.height()));
        TreeSet<Integer> activeRates = new TreeSet<>();
        int maxHz = 0;
        DexDisplayControl.Mode lower = null;
        long activePixels = (long) active.width() * active.height();
        for (DexDisplayControl.Mode mode : sorted) {
            int hz = Math.round(mode.hz());
            String sizeKey = mode.width() + "x" + mode.height();
            Integer prevMax = fastestBySize.get(sizeKey);
            if (prevMax == null || hz > prevMax) fastestBySize.put(sizeKey, hz);
            if (mode.width() == active.width() && mode.height() == active.height()) activeRates.add(hz);
            maxHz = Math.max(maxHz, hz);
            if ((long) mode.width() * mode.height() < activePixels && mode.hz() > active.hz() + 1f
                    && (lower == null || mode.hz() > lower.hz() + 1f)) {
                lower = mode;
            }
        }
        StringBuilder modes = new StringBuilder();
        for (Map.Entry<String, Integer> entry : fastestBySize.entrySet()) {
            if (modes.length() > 0) modes.append(", ");
            modes.append(entry.getKey()).append(" @ ").append(entry.getValue());
        }
        out.put("modes", modes.toString());
        out.put("rates", new JSONArray(activeRates));
        out.put("maxHz", maxHz);
        if (lower != null) {
            JSONObject hint = new JSONObject();
            hint.put("width", lower.width());
            hint.put("height", lower.height());
            hint.put("hz", lower.hz());
            out.put("lowerRes", hint);
        }
    }

    private static String readStartMode() throws Exception {
        return DexDisplayControl.run(2, "/system/bin/getprop", PROP);
    }

    private static void writeStartMode(String value) throws Exception {
        String target = value == null ? "" : value;
        try {
            DexDisplayControl.run(2, "/system/bin/setprop", PROP, target);
        } catch (IOException ignored) {
            // vendor_display_prop may need resetprop from root.
        }
        if (target.equals(readStartMode())) return;
        for (String helper : new String[]{"/data/adb/ksud", ROOT_LOCK_DIR + "/ksud", "/data/adb/magisk/resetprop"}) {
            if (!new File(helper).canExecute()) continue;
            if (helper.endsWith("resetprop")) DexDisplayControl.run(2, helper, PROP, target);
            else DexDisplayControl.run(2, helper, "resetprop", PROP, target);
            if (target.equals(readStartMode())) return;
        }
        throw new IOException("The vendor start-mode property rejected the write.");
    }

    private static void write(String path, String value) throws IOException {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(value);
        }
    }

    private static String readFileText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeFileText(File file, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static RandomAccessFile lock(File file) throws IOException {
        RandomAccessFile handle = new RandomAccessFile(file, "rw");
        FileLock lock = handle.getChannel().tryLock();
        if (lock != null) return handle;
        handle.close();
        return null;
    }

    private void closeLocks() {
        for (Closeable handle : new Closeable[]{shellLock, rootLock}) {
            if (handle == null) continue;
            try {
                handle.close();
            } catch (IOException ignored) {
                // Closing releases the process lock.
            }
        }
        shellLock = null;
        rootLock = null;
    }
}
