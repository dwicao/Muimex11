package com.muimex11.official;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Hidden SurfaceControl, SurfaceFlinger, and Samsung DeX calls, looked up at runtime so that a
 * missing or differently shaped API turns a feature off instead of crashing. Works from the ADB
 * shell UID (2000), which holds ACCESS_SURFACE_FLINGER, and from root.
 */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi"})
final class DexDisplayControl {
    private static final Class<?> SC = load("android.view.SurfaceControl");
    private static final Method IDS = find(SC, "getPhysicalDisplayIds");
    private static final Method TOKEN = find(SC, "getPhysicalDisplayToken", long.class);
    private static final Method STATIC_INFO = find(SC, "getStaticDisplayInfo", long.class);
    private static final Method DYNAMIC_INFO = find(SC, "getDynamicDisplayInfo", long.class);
    private static final Method GET_SPECS = find(SC, "getDesiredDisplayModeSpecs", IBinder.class);
    private static final Method SET_SPECS = findSetSpecs();
    private static final DexSurfaceFlingerCodes.Codes CODES = DexSurfaceFlingerCodes.discover();
    private static final ExecutorService EXEC_WAIT_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dexhz-exec-wait");
        t.setDaemon(true);
        return t;
    });

    private static DisplayManager displayManager;

    private DexDisplayControl() {}

    static void init(Context context) {
        displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    }

    static final class Mode {
        private final int id;
        private final int width;
        private final int height;
        private final float hz;

        Mode(int id, int width, int height, float hz) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.hz = hz;
        }

        int id() { return id; }
        int width() { return width; }
        int height() { return height; }
        float hz() { return hz; }
    }

    static final class Info {
        private final long id;
        private final boolean internal;
        private final boolean on;
        private final String name;
        private final Mode active;
        private final Mode fastest;
        private final List<Mode> modes;

        Info(long id, boolean internal, boolean on, String name, Mode active, Mode fastest, List<Mode> modes) {
            this.id = id;
            this.internal = internal;
            this.on = on;
            this.name = name;
            this.active = active;
            this.fastest = fastest;
            this.modes = modes;
        }

        long id() { return id; }
        boolean internal() { return internal; }
        boolean on() { return on; }
        String name() { return name; }
        Mode active() { return active; }
        Mode fastest() { return fastest; }
        List<Mode> modes() { return modes; }

        boolean belowFastest() { return fastest.hz() > active.hz() + 1f; }
    }

    static boolean canReadModes() {
        return IDS != null && STATIC_INFO != null && DYNAMIC_INFO != null;
    }

    static boolean canHoldPolicy() {
        return canReadModes() && TOKEN != null && GET_SPECS != null && SET_SPECS != null;
    }

    static boolean hasPacesetterCalls() {
        return CODES != null;
    }

    static String pacesetterCodes() {
        return CODES == null ? "none" : String.format("0x%x/0x%x", CODES.force(), CODES.reset());
    }

    static boolean hasHighResolutionSwitch() {
        try {
            dexService().getClass().getMethod("enableHighResolutionsForExternalDesktop", boolean.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static List<Info> displays() throws Exception {
        Map<Long, Boolean> power = powerStates();
        List<Info> result = new ArrayList<>();
        if (canReadModes()) {
            long[] ids = (long[]) IDS.invoke(null);
            if (ids != null) {
                for (long id : ids) {
                    Boolean on = power.get(id);
                    Info info = sfInfo(id, on == null || on);
                    if (info != null) result.add(info);
                }
            }
            return result;
        }
        if (displayManager == null) return result;
        for (Display display : displayManager.getDisplays()) {
            Long physicalId = physicalId(display);
            if (physicalId == null) continue;
            Display.Mode active = display.getMode();
            List<Mode> modes = new ArrayList<>();
            Mode activeMode = null;
            for (Display.Mode m : display.getSupportedModes()) {
                Mode mode = new Mode(-1, m.getPhysicalWidth(), m.getPhysicalHeight(), m.getRefreshRate());
                modes.add(mode);
                if (m.getModeId() == active.getModeId()) activeMode = mode;
            }
            if (activeMode == null) continue;
            boolean internal = display.getDisplayId() == Display.DEFAULT_DISPLAY;
            result.add(new Info(physicalId, internal, display.getState() == Display.STATE_ON,
                    internal ? "Phone screen" : display.getName(), activeMode, fastest(activeMode, modes), modes));
        }
        return result;
    }

    private static Info sfInfo(long id, boolean on) throws ReflectiveOperationException {
        Object stat = STATIC_INFO.invoke(null, id);
        Object dyn = DYNAMIC_INFO.invoke(null, id);
        if (stat == null || dyn == null) return null;
        boolean internal = (Boolean) field(stat, "isInternal");
        String name = internal ? "Phone screen" : "External display";
        Object product = optional(stat, "deviceProductInfo");
        if (product != null) {
            Object productName = product.getClass().getMethod("getName").invoke(product);
            if (productName instanceof String && !((String) productName).trim().isEmpty()) {
                name = (String) productName;
            }
        }
        int activeId = (Integer) field(dyn, "activeDisplayModeId");
        List<Mode> modes = new ArrayList<>();
        Mode active = null;
        for (Object raw : (Object[]) field(dyn, "supportedDisplayModes")) {
            Object hz = optional(raw, "peakRefreshRate");
            if (hz == null) hz = field(raw, "refreshRate");
            Mode mode = new Mode((Integer) field(raw, "id"), (Integer) field(raw, "width"),
                    (Integer) field(raw, "height"), (Float) hz);
            modes.add(mode);
            if (mode.id() == activeId) active = mode;
        }
        if (active == null) return null;
        return new Info(id, internal, on, name, active, fastest(active, modes), modes);
    }

    private static Mode fastest(Mode active, List<Mode> modes) {
        Mode fastest = active;
        for (Mode mode : modes) {
            if (mode.width() == active.width() && mode.height() == active.height() && mode.hz() > fastest.hz()) {
                fastest = mode;
            }
        }
        return fastest;
    }

    private static Map<Long, Boolean> powerStates() {
        Map<Long, Boolean> states = new HashMap<>();
        if (displayManager == null) return states;
        try {
            for (Display display : displayManager.getDisplays()) {
                Long physicalId = physicalId(display);
                if (physicalId != null) states.put(physicalId, display.getState() == Display.STATE_ON);
            }
        } catch (RuntimeException ignored) {
            // Power state is a hint for picking the active phone panel on foldables.
        }
        return states;
    }

    private static Long physicalId(Display display) {
        try {
            Object address = Display.class.getMethod("getAddress").invoke(display);
            if (address == null || !address.getClass().getName().endsWith("$Physical")) return null;
            return (Long) address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static IBinder token(long id) throws ReflectiveOperationException {
        return TOKEN == null ? null : (IBinder) TOKEN.invoke(null, id);
    }

    static Object readSpecs(IBinder token) throws ReflectiveOperationException {
        return GET_SPECS.invoke(null, token);
    }

    static void writeSpecs(IBinder token, Object specs) throws ReflectiveOperationException, IOException {
        Object argument = specs;
        if (SET_SPECS.getParameterTypes()[1].isArray()) {
            argument = Array.newInstance(specs.getClass(), 1);
            Array.set(argument, 0, specs);
        }
        Object result = SET_SPECS.invoke(null, token, argument);
        if (Boolean.FALSE.equals(result)) throw new IOException("SurfaceFlinger rejected the refresh policy.");
    }

    static Object pinnedSpecs(IBinder token, int modeId, float physicalHz, float renderHz)
            throws ReflectiveOperationException {
        Object specs = readSpecs(token);
        specs.getClass().getField("defaultMode").setInt(specs, modeId);
        float[] values = {physicalHz - 0.5f, physicalHz + 0.5f, renderHz - 0.5f, renderHz + 0.5f};
        if (optional(specs, "primaryRanges") != null) {
            for (String group : new String[]{"primaryRanges", "appRequestRanges"}) {
                Object ranges = field(specs, group);
                setRange(field(ranges, "physical"), values[0], values[1]);
                setRange(field(ranges, "render"), values[2], values[3]);
            }
        } else {
            for (String group : new String[]{"primaryRefreshRate", "appRequestRefreshRate"}) {
                specs.getClass().getField(group + "Min").setFloat(specs, values[0]);
                specs.getClass().getField(group + "Max").setFloat(specs, values[1]);
            }
        }
        return specs;
    }

    private static void setRange(Object range, float min, float max) throws ReflectiveOperationException {
        range.getClass().getField("min").setFloat(range, min);
        range.getClass().getField("max").setFloat(range, max);
    }

    private static float[] ranges(Object specs) throws ReflectiveOperationException {
        float[] out = new float[8];
        if (optional(specs, "primaryRanges") != null) {
            int i = 0;
            for (String group : new String[]{"primaryRanges", "appRequestRanges"}) {
                Object ranges = field(specs, group);
                for (String kind : new String[]{"physical", "render"}) {
                    Object range = field(ranges, kind);
                    out[i++] = (Float) field(range, "min");
                    out[i++] = (Float) field(range, "max");
                }
            }
        } else {
            float pMin = (Float) field(specs, "primaryRefreshRateMin"), pMax = (Float) field(specs, "primaryRefreshRateMax");
            float aMin = (Float) field(specs, "appRequestRefreshRateMin"), aMax = (Float) field(specs, "appRequestRefreshRateMax");
            out = new float[]{pMin, pMax, pMin, pMax, aMin, aMax, aMin, aMax};
        }
        return out;
    }

    static boolean isPinned(Object specs, int modeId, float physicalHz, float renderHz)
            throws ReflectiveOperationException {
        if ((Integer) field(specs, "defaultMode") != modeId) return false;
        float[] r = ranges(specs);
        for (int i = 0; i < 8; i += 4) {
            if (!near(r[i], r[i + 1], physicalHz) || !near(r[i + 2], r[i + 3], renderHz)) return false;
        }
        return true;
    }

    private static boolean near(float min, float max, float hz) {
        return Math.abs(min - (hz - 0.5f)) < 0.3f && Math.abs(max - (hz + 0.5f)) < 0.3f;
    }

    static String signature(Object specs) throws ReflectiveOperationException {
        StringBuilder result = new StringBuilder().append(field(specs, "defaultMode"));
        for (float value : ranges(specs)) result.append('/').append(value);
        return result.toString();
    }

    static void forcePacesetter(long displayId) throws Exception {
        if (CODES == null) throw new IOException("This firmware has no pacesetter control.");
        surfaceFlingerCall(CODES.force(), displayId);
    }

    static void resetPacesetter() throws Exception {
        if (CODES == null) throw new IOException("This firmware has no pacesetter control.");
        surfaceFlingerCall(CODES.reset(), null);
    }

    private static void surfaceFlingerCall(int code, Long displayId) throws Exception {
        IBinder flinger = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "SurfaceFlingerAIDL");
        if (flinger == null) throw new IOException("SurfaceFlinger's AIDL service is unavailable.");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(flinger.getInterfaceDescriptor());
            if (displayId != null) data.writeLong(displayId);
            if (!flinger.transact(code, data, reply, 0)) throw new IOException("SurfaceFlinger ignored the timing request.");
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    static final class Forced {
        private final boolean supported;
        private final Long display;

        Forced(boolean supported, Long display) {
            this.supported = supported;
            this.display = display;
        }

        boolean supported() { return supported; }
        Long display() { return display; }
    }

    static Forced forcedPacesetter() {
        try {
            for (String line : run(4, "/system/bin/dumpsys", "SurfaceFlinger", "--scheduler").split("\n")) {
                String text = line.trim();
                if (!text.startsWith("forcedPacesetterDisplayId=")) continue;
                String value = text.substring("forcedPacesetterDisplayId=".length()).trim();
                if (value.equals("nullopt") || value.isEmpty()) return new Forced(true, null);
                long parsed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? Long.parseUnsignedLong(value)
                        : new BigInteger(value, 10).longValue();
                return new Forced(true, parsed);
            }
        } catch (Exception ignored) {
            // Treated as unsupported below.
        }
        return new Forced(false, null);
    }

    static int thermalStatus() {
        try {
            for (String line : run(3, "/system/bin/dumpsys", "thermalservice").split("\n")) {
                String text = line.trim();
                if (text.startsWith("Thermal Status:")) return Integer.parseInt(text.substring(15).trim());
            }
        } catch (Exception ignored) {
            // Unknown.
        }
        return -1;
    }

    private static Object dexService() throws ReflectiveOperationException {
        Object taskManager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Object samsung = taskManager.getClass().getMethod("getMultiTaskingBinder").invoke(taskManager);
        if (samsung == null) throw new IllegalStateException("Samsung's DeX service is unavailable.");
        return samsung;
    }

    static void setHighResolution(boolean enabled) throws ReflectiveOperationException {
        Object samsung = dexService();
        samsung.getClass().getMethod("enableHighResolutionsForExternalDesktop", boolean.class).invoke(samsung, enabled);
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Method find(Class<?> owner, String name, Class<?>... parameters) {
        if (owner == null) return null;
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Method findSetSpecs() {
        if (SC == null) return null;
        for (Method method : SC.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("setDesiredDisplayModeSpecs") && types.length == 2 && types[0] == IBinder.class) {
                return method;
            }
        }
        return null;
    }

    static Object field(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getField(name).get(owner);
    }

    private static Object optional(Object owner, String name) {
        try {
            return field(owner, name);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    static String run(int timeoutSeconds, String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[4096];
            try (InputStream in = process.getInputStream()) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    output.write(buf, 0, n);
                }
            } catch (IOException ignored) {
                // Process closed output.
            }
        }, "dexhz-exec");
        reader.start();
        Future<Integer> waitFuture = EXEC_WAIT_POOL.submit(process::waitFor);
        try {
            int exitCode;
            try {
                exitCode = waitFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                waitFuture.cancel(true);
                throw new IOException(command[0] + " timed out.");
            }
            reader.join(1_000);
            if (exitCode != 0) throw new IOException(command[0] + " exited with " + exitCode + ".");
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (process.isAlive()) process.destroyForcibly();
            } else {
                try {
                    process.exitValue();
                } catch (IllegalThreadStateException alive) {
                    process.destroy();
                }
            }
        }
    }

    static String describe(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof InvocationTargetException || cause instanceof ExceptionInInitializerError)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        String type = cause.getClass().getSimpleName();
        if (message == null || message.trim().isEmpty()) return type;
        return type + ": " + message.substring(0, Math.min(message.length(), 180));
    }
}
