package com.muimex11.official;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Finds the binder transaction numbers of SurfaceFlinger's forcePacesetter and
 * resetForcedPacesetter on the running firmware, instead of hard-coding them.
 */
final class DexSurfaceFlingerCodes {
    private static final String[] LIBRARIES = {"/system/lib64/libgui.so"};
    private static final String FORCE = "_ZN7android3gui17BpSurfaceComposer15forcePacesetterEl";
    private static final String RESET = "_ZN7android3gui17BpSurfaceComposer21resetForcedPacesetterEv";

    static final class Codes {
        private final int force;
        private final int reset;

        Codes(int force, int reset) {
            this.force = force;
            this.reset = reset;
        }

        int force() { return force; }
        int reset() { return reset; }
    }

    private DexSurfaceFlingerCodes() {}

    /** The verified-shape codes, or null when this firmware doesn't have the calls. */
    static Codes discover() {
        for (String path : LIBRARIES) {
            try {
                Codes codes = scan(path);
                if (codes != null) return codes;
            } catch (Exception ignored) {
                // Unreadable or unexpected library layout: treat as unsupported.
            }
        }
        return null;
    }

    private static Codes scan(String path) throws IOException {
        ByteBuffer elf;
        try (RandomAccessFile file = new RandomAccessFile(path, "r"); FileChannel channel = file.getChannel()) {
            elf = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN);
        }
        if (elf.getInt(0) != 0x464C457F || elf.get(4) != 2 || elf.get(5) != 1) return null; // ELF64, little-endian
        if (elf.getShort(0x12) != 183) return null; // EM_AARCH64

        long phoff = elf.getLong(0x20);
        long shoff = elf.getLong(0x28);
        int phentsize = elf.getShort(0x36) & 0xFFFF;
        int phnum = elf.getShort(0x38) & 0xFFFF;
        int shentsize = elf.getShort(0x3A) & 0xFFFF;
        int shnum = elf.getShort(0x3C) & 0xFFFF;

        long symOff = -1, symSize = 0, strOff = -1;
        for (int i = 0; i < shnum; i++) {
            int base = (int) (shoff + (long) i * shentsize);
            if (elf.getInt(base + 4) != 11) continue; // SHT_DYNSYM
            symOff = elf.getLong(base + 0x18);
            symSize = elf.getLong(base + 0x20);
            int link = elf.getInt(base + 0x28);
            strOff = elf.getLong((int) (shoff + (long) link * shentsize) + 0x18);
        }
        if (symOff < 0 || strOff < 0) return null;

        Map<String, long[]> wanted = new HashMap<>();
        for (long i = 0; i < symSize / 24; i++) {
            int sym = (int) (symOff + i * 24);
            String name = cString(elf, (int) (strOff + (elf.getInt(sym) & 0xFFFFFFFFL)));
            if (name.equals(FORCE) || name.equals(RESET)) {
                wanted.put(name, new long[]{elf.getLong(sym + 8), elf.getLong(sym + 16)});
            }
        }
        if (wanted.size() != 2) return null;

        Integer force = transactionCode(elf, phoff, phentsize, phnum, wanted.get(FORCE));
        Integer reset = transactionCode(elf, phoff, phentsize, phnum, wanted.get(RESET));
        if (force == null || reset == null || reset != force + 1) return null;
        return new Codes(force, reset);
    }

    /** The single {@code MOVZ W1, #imm} constant in the function, or null if absent or ambiguous. */
    private static Integer transactionCode(ByteBuffer elf, long phoff, int phentsize, int phnum, long[] symbol) {
        long vaddr = symbol[0];
        long size = Math.min(symbol[1], 4096);
        long offset = -1;
        for (int i = 0; i < phnum; i++) {
            int base = (int) (phoff + (long) i * phentsize);
            if (elf.getInt(base) != 1) continue; // PT_LOAD
            long segOffset = elf.getLong(base + 0x08);
            long segVaddr = elf.getLong(base + 0x10);
            long segFilesz = elf.getLong(base + 0x20);
            if (vaddr >= segVaddr && vaddr + size <= segVaddr + segFilesz) offset = vaddr - segVaddr + segOffset;
        }
        if (offset < 0 || size < 8) return null;
        Set<Integer> found = new HashSet<>();
        for (long k = 0; k + 4 <= size; k += 4) {
            int ins = elf.getInt((int) (offset + k));
            if ((ins & 0xFF80001F) == 0x52800001) { // MOVZ W1, #imm16, LSL #hw*16
                int hw = (ins >>> 21) & 3;
                found.add(((ins >>> 5) & 0xFFFF) << (16 * hw));
            }
        }
        if (found.size() != 1) return null;
        int code = found.iterator().next();
        // Binder user transactions live in [FIRST_CALL_TRANSACTION, LAST_CALL_TRANSACTION].
        return code >= 1 && code <= 0x00FFFFFF ? code : null;
    }

    private static String cString(ByteBuffer elf, int at) {
        int end = at;
        while (end < elf.limit() && elf.get(end) != 0) end++;
        byte[] bytes = new byte[end - at];
        for (int i = 0; i < bytes.length; i++) bytes[i] = elf.get(at + i);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
