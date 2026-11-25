package com.muimex11.official;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Build;
import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Privileged Shizuku UserService running as ADB shell (UID 2000) or root (UID 0),
 * managing Samsung DeX external display refresh rates.
 */
public final class DexHzService extends IDexHzService.Stub {
    static final int ROOT_UID = 0;
    static final int SHELL_UID = 2000;
    private static final File CRASH_FILE = new File("/data/local/tmp/muimex-dexhz-crash.txt");
    private static final int MAX_CRASH_BYTES = 64 * 1024;
    private static final File EVENTS_FILE = new File("/data/local/tmp/muimex-dexhz-events.txt");
    private static final File PREVIOUS_EVENTS_FILE = new File("/data/local/tmp/muimex-dexhz-events-previous.txt");

    private final int ownerUid;
    private final int serviceUid = Os.getuid();
    private final DexRefreshSession session;
    private final String device;
    private final String versionLabel;

    public DexHzService(Context context) {
        ownerUid = context.getApplicationInfo().uid;
        versionLabel = resolveVersion(context);
        DexDisplayControl.init(context);
        session = new DexRefreshSession(serviceUid == ROOT_UID, context.getPackageName());
        device = describeDevice();
        installCrashHandler();
        keepEventLog();
        session.log().add("DeX Hz service started (Muimex11 " + versionLabel + ", uid " + serviceUid + ").");
    }

    private static String resolveVersion(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            try {
                StringWriter trace = new StringWriter();
                failure.printStackTrace(new PrintWriter(trace));
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
                try (FileWriter writer = new FileWriter(CRASH_FILE)) {
                    writer.write("Muimex11 " + versionLabel + " DeX Hz service crashed at " + ts
                            + " on thread " + thread.getName() + "\n" + trace + "\nRecent events:\n" + session.log().text() + "\n");
                }
            } catch (Throwable ignored) {
                // Best effort while process dies.
            }
            if (previous != null) previous.uncaughtException(thread, failure);
            else System.exit(10);
        });
    }

    private void keepEventLog() {
        if (EVENTS_FILE.isFile() && EVENTS_FILE.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            PREVIOUS_EVENTS_FILE.delete();
            //noinspection ResultOfMethodCallIgnored
            EVENTS_FILE.renameTo(PREVIOUS_EVENTS_FILE);
        }
        session.log().persistTo(EVENTS_FILE);
    }

    private static String describeDevice() {
        String text = Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE;
        try {
            int oneUi = Integer.parseInt(DexDisplayControl.run(2, "/system/bin/getprop", "ro.build.version.oneui"));
            text += String.format(Locale.US, " · One UI %d.%d", oneUi / 10000, (oneUi / 100) % 100);
        } catch (Exception ignored) {
            // Not a Samsung build, or property missing.
        }
        return text + " · " + Build.DISPLAY;
    }

    @Override
    public int getServiceUid() {
        requireOwner();
        return serviceUid;
    }

    @Override
    public String getState() {
        requireOwner();
        long identity = Binder.clearCallingIdentity();
        try {
            JSONObject state = session.state();
            state.put("uid", serviceUid);
            state.put("device", device);
            if (CRASH_FILE.isFile()) state.put("crashAt", CRASH_FILE.lastModified());
            return state.toString();
        } catch (Exception e) {
            return "{\"notice\":" + JSONObject.quote(DexDisplayControl.describe(e)) + "}";
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String startBoost(float targetHz, boolean autoReconnect, boolean coolDown) {
        requireOwner();
        if (!privileged()) return "DeX Hz Unlock needs Shizuku running as ADB shell or root.";
        long identity = Binder.clearCallingIdentity();
        try {
            return session.start(targetHz, autoReconnect, coolDown);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String stopBoost() {
        requireOwner();
        long identity = Binder.clearCallingIdentity();
        try {
            return session.stop(true);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void configure(float targetHz, boolean autoReconnect, boolean coolDown) {
        requireOwner();
        long identity = Binder.clearCallingIdentity();
        try {
            session.configure(targetHz, autoReconnect, coolDown);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String reconnectMonitor() {
        requireOwner();
        if (!privileged()) return "Reconnecting needs Shizuku running as ADB shell or root.";
        long identity = Binder.clearCallingIdentity();
        try {
            return session.reconnect();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String switchMonitorMode(long displayId, int width, int height, int hz) {
        requireOwner();
        if (!privileged()) return "Choosing the monitor's mode needs Shizuku running as ADB shell or root.";
        long identity = Binder.clearCallingIdentity();
        try {
            return session.switchMode(displayId, width, height, hz);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String confirmMonitorMode(boolean keep) {
        requireOwner();
        long identity = Binder.clearCallingIdentity();
        try {
            return session.confirmMode(keep);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String forgetMonitorStartMode() {
        requireOwner();
        long identity = Binder.clearCallingIdentity();
        try {
            return session.forgetStartMode();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String getDiagnostics(boolean includeSystemLog) {
        requireOwner();
        long identity = Binder.clearCallingIdentity();
        try {
            JSONObject out = new JSONObject();
            out.put("events", new JSONArray(session.log().entries()));
            if (PREVIOUS_EVENTS_FILE.isFile()) {
                out.put("previousEventsAt", PREVIOUS_EVENTS_FILE.lastModified());
                out.put("previousEvents", readTail(PREVIOUS_EVENTS_FILE));
            }
            if (CRASH_FILE.isFile()) {
                out.put("crashAt", CRASH_FILE.lastModified());
                out.put("crash", readCrash());
            }
            if (includeSystemLog) {
                try {
                    String raw = DexDisplayControl.run(6, "/system/bin/logcat", "-d", "-b", "main,system,crash",
                            "-v", "threadtime", "-t", "2000");
                    out.put("systemLog", filterLog(raw));
                } catch (Exception e) {
                    out.put("systemLogError", DexDisplayControl.describe(e));
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "{\"error\":" + JSONObject.quote(DexDisplayControl.describe(e)) + "}";
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static String filterLog(String raw) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        String[] lines = raw.split("\n");
        for (int i = Math.max(0, lines.length - 1200); i < lines.length; i++) {
            String low = lines[i].toLowerCase(Locale.US);
            if (low.contains("surfaceflinger") || low.contains("dex") || low.contains("display")
                    || low.contains("hwc") || low.contains("secdp") || low.contains("sdm")) {
                if (out.length() > 0) out.append('\n');
                out.append(lines[i]);
                if (++count >= 250) break;
            }
        }
        return out.toString();
    }

    private static String readCrash() throws IOException {
        byte[] bytes = readAllBytes(CRASH_FILE);
        int length = Math.min(bytes.length, MAX_CRASH_BYTES);
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    private static String readTail(File file) throws IOException {
        byte[] bytes = readAllBytes(file);
        int start = Math.max(0, bytes.length - MAX_CRASH_BYTES);
        String text = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
        int newline = start > 0 ? text.indexOf('\n') : -1;
        return (newline >= 0 ? text.substring(newline + 1) : text).trim();
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    @Override
    public void clearCrash() {
        requireOwner();
        long identity = Binder.clearCallingIdentity();
        try {
            if (CRASH_FILE.isFile() && !CRASH_FILE.delete()) {
                session.log().add("Couldn't delete the saved crash report.");
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void destroy() {
        int caller = Binder.getCallingUid();
        if (caller != serviceUid && caller != ownerUid) {
            throw new SecurityException("This caller cannot stop the DeX Hz service.");
        }
        Binder.clearCallingIdentity();
        try {
            session.stop(false);
        } finally {
            System.exit(0);
        }
    }

    private boolean privileged() {
        return serviceUid == ROOT_UID || serviceUid == SHELL_UID;
    }

    private void requireOwner() {
        if (Binder.getCallingUid() != ownerUid) {
            throw new SecurityException("This service accepts requests from Muimex11 only.");
        }
    }
}
