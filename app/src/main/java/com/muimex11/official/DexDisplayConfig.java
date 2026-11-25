package com.muimex11.official;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

/**
 * Qualcomm's display config HAL ({@code vendor.qti.hardware.display.config.IDisplayConfig/default}),
 * which the ADB shell user may call. Reads and sets the external display's mode in the composer
 * directly on Snapdragon devices.
 */
final class DexDisplayConfig {
    static final String SERVICE = "vendor.qti.hardware.display.config.IDisplayConfig/default";
    static final int EXTERNAL = 2;
    private static final int IS_CONNECTED = 1;
    private static final int CONFIG_COUNT = 4;
    private static final int ACTIVE = 5;
    private static final int SET_ACTIVE = 6;
    private static final int ATTRIBUTES = 7;
    static final int FAILED = Integer.MIN_VALUE;

    static final class Config {
        private final int index;
        private final int width;
        private final int height;
        private final int vsyncNs;

        Config(int index, int width, int height, int vsyncNs) {
            this.index = index;
            this.width = width;
            this.height = height;
            this.vsyncNs = vsyncNs;
        }

        int index() { return index; }
        int width() { return width; }
        int height() { return height; }
        int vsyncNs() { return vsyncNs; }

        float hz() { return 1e9f / vsyncNs; }
        int roundHz() { return Math.round(hz()); }

        boolean is(int w, int h, int rate) {
            return width == w && height == h && roundHz() == rate;
        }

        String key() { return width + "x" + height + "@" + roundHz(); }
    }

    private final IBinder binder;
    private final String descriptor;

    private DexDisplayConfig(IBinder binder, String descriptor) {
        this.binder = binder;
        this.descriptor = descriptor;
    }

    static DexDisplayConfig open() {
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("checkService", String.class).invoke(null, SERVICE);
            if (binder == null) return null;
            DexDisplayConfig config = new DexDisplayConfig(binder, binder.getInterfaceDescriptor());
            config.connected();
            return config;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    boolean connected() throws RemoteException {
        return call(IS_CONNECTED, EXTERNAL) == 1;
    }

    int count() throws RemoteException {
        return call(CONFIG_COUNT, EXTERNAL);
    }

    int active() throws RemoteException {
        return call(ACTIVE, EXTERNAL);
    }

    boolean set(int index) throws RemoteException {
        return call(SET_ACTIVE, EXTERNAL, index) != FAILED;
    }

    Config config(int index) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(index);
            data.writeInt(EXTERNAL);
            if (!binder.transact(ATTRIBUTES, data, reply, 0)) return null;
            if (reply.readInt() != 0) return null;
            if (reply.readInt() == 0) return null;
            reply.readInt();
            int vsync = reply.readInt();
            int width = reply.readInt();
            int height = reply.readInt();
            boolean plausible = vsync >= 2_000_000 && vsync <= 100_000_000
                    && width >= 320 && width <= 16384 && height >= 200 && height <= 16384;
            return plausible ? new Config(index, width, height, vsync) : null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    List<Config> configs() throws RemoteException {
        List<Config> out = new ArrayList<>();
        int count = count();
        if (count <= 0 || count > 512) return out;
        for (int i = 0; i < count; i++) {
            Config config = config(i);
            if (config != null) out.add(config);
        }
        return out;
    }

    static String fastestKey(List<Config> configs) {
        Config best = null;
        for (Config config : configs) {
            if (best == null || config.roundHz() > best.roundHz() || config.roundHz() == best.roundHz()
                    && (long) config.width() * config.height() > (long) best.width() * best.height()) {
                best = config;
            }
        }
        return best == null ? "" : best.key();
    }

    private int call(int code, int... args) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            for (int arg : args) data.writeInt(arg);
            if (!binder.transact(code, data, reply, 0)) return FAILED;
            if (reply.readInt() != 0) return FAILED;
            return reply.dataAvail() >= 4 ? reply.readInt() : 0;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
