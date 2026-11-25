package com.muimex11.official;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Starts the monitor in a chosen mode without root on Snapdragon devices by setting
 * Qualcomm's IDisplayConfig mode during the brief hotplug window before SurfaceFlinger
 * reads the external display's active mode.
 */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi"})
final class DexStartModeRacer {
    static final class Target {
        private final int width;
        private final int height;
        private final int hz;
        private final String fastestKey;

        Target(int width, int height, int hz, String fastestKey) {
            this.width = width;
            this.height = height;
            this.hz = hz;
            this.fastestKey = fastestKey;
        }

        int width() { return width; }
        int height() { return height; }
        int hz() { return hz; }
        String fastestKey() { return fastestKey; }

        String key() { return width + ":" + height + ":" + hz; }
        String label() { return width + " × " + height + " at " + hz + " Hz"; }
        String serialize() { return width + ":" + height + ":" + hz + ":" + fastestKey; }

        static Target parse(String text) {
            try {
                if (text == null) return null;
                String[] parts = text.trim().split(":", 4);
                if (parts.length != 4 || parts[3].isEmpty()) return null;
                return new Target(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        parts[3]
                );
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    enum Outcome { SET, ALREADY, OTHER, REFUSED }

    static final class Attempt {
        private final Target target;
        private final long at;
        private final Outcome outcome;
        private final int index;
        private final int previousIndex;
        private final long micros;

        Attempt(Target target, long at, Outcome outcome, int index, int previousIndex, long micros) {
            this.target = target;
            this.at = at;
            this.outcome = outcome;
            this.index = index;
            this.previousIndex = previousIndex;
            this.micros = micros;
        }

        Target target() { return target; }
        long at() { return at; }
        Outcome outcome() { return outcome; }
        int index() { return index; }
        int previousIndex() { return previousIndex; }
        long micros() { return micros; }
    }

    private static final long FAST_NS = 1_000_000L;
    private static final long SLOW_NS = 25_000_000L;
    private static final long UNKNOWN_NS = 4_000_000L;
    private static final long CONNECTED_NS = 50_000_000L;
    private static final long IDLE_NS = 500_000_000L;
    static final long AFTER_DISCONNECT_MS = 30_000;
    private static final long USB_CHECK_MS = 250;

    private final DexDisplayConfig config;
    private volatile Target target;
    private volatile long fastUntil;
    private volatile Attempt last;
    private final AtomicInteger matches = new AtomicInteger();
    private Thread thread;

    private Object usb;
    private boolean usbUnreadable;
    private long usbCheckedAt;
    private boolean usbAttached;

    DexStartModeRacer(DexDisplayConfig config) {
        this.config = config;
    }

    synchronized void arm(Target target, long fastMs) {
        this.target = target;
        fastUntil = Math.max(fastUntil, SystemClock.elapsedRealtime() + fastMs);
        if (thread == null) {
            thread = new Thread(this::run, "dexhz-start-mode");
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        } else {
            LockSupport.unpark(thread);
        }
    }

    void disarm() { target = null; }
    Target target() { return target; }
    Attempt last() { return last; }
    int matches() { return matches.get(); }

    synchronized void close() {
        target = null;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void run() {
        boolean wasConnected = true;
        boolean primed = false;
        while (!Thread.currentThread().isInterrupted()) {
            Target armed = target;
            if (armed == null) {
                primed = false;
                LockSupport.parkNanos(IDLE_NS);
                continue;
            }
            try {
                boolean connected = config.connected();
                if (!primed) {
                    primed = true;
                    wasConnected = connected;
                }
                if (connected && !wasConnected) attempt(armed);
                if (!connected && wasConnected) {
                    fastUntil = Math.max(fastUntil, SystemClock.elapsedRealtime() + AFTER_DISCONNECT_MS);
                }
                wasConnected = connected;
                LockSupport.parkNanos(connected ? CONNECTED_NS : likely() ? FAST_NS : usbUnreadable ? UNKNOWN_NS : SLOW_NS);
            } catch (RemoteException | RuntimeException e) {
                LockSupport.parkNanos(IDLE_NS);
            }
        }
    }

    private void attempt(Target armed) throws RemoteException {
        long seen = SystemClock.elapsedRealtimeNanos();
        List<DexDisplayConfig.Config> configs = config.configs();
        DexDisplayConfig.Config hit = null;
        for (DexDisplayConfig.Config c : configs) {
            if (c.is(armed.width(), armed.height(), armed.hz())) {
                hit = c;
                break;
            }
        }
        int previous = config.active();
        Outcome outcome;
        if (hit == null || !DexDisplayConfig.fastestKey(configs).equals(armed.fastestKey())) {
            outcome = Outcome.OTHER;
        } else if (previous == hit.index()) {
            outcome = Outcome.ALREADY;
        } else {
            outcome = config.set(hit.index()) ? Outcome.SET : Outcome.REFUSED;
        }
        long micros = (SystemClock.elapsedRealtimeNanos() - seen) / 1_000;
        last = new Attempt(armed, SystemClock.elapsedRealtime(), outcome, hit == null ? -1 : hit.index(), previous, micros);
        if (outcome == Outcome.SET || outcome == Outcome.ALREADY) matches.incrementAndGet();
    }

    private boolean likely() {
        long now = SystemClock.elapsedRealtime();
        if (now < fastUntil) return true;
        if (now - usbCheckedAt >= USB_CHECK_MS) {
            usbCheckedAt = now;
            usbAttached = usbPartner();
        }
        return usbAttached;
    }

    private boolean usbPartner() {
        if (usbUnreadable) return false;
        try {
            if (usb == null) {
                IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, "usb");
                usb = Class.forName("android.hardware.usb.IUsbManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, binder);
            }
            List<?> ports = (List<?>) usb.getClass().getMethod("getPorts").invoke(usb);
            if (ports == null) return false;
            for (Object port : ports) {
                String id = (String) port.getClass().getMethod("getId").invoke(port);
                Object status = usb.getClass().getMethod("getPortStatus", String.class).invoke(usb, id);
                if (status != null && (Boolean) status.getClass().getMethod("isConnected").invoke(status)) return true;
            }
            return false;
        } catch (Exception | LinkageError e) {
            usbUnreadable = true;
            return false;
        }
    }
}
