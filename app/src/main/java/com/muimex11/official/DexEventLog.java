package com.muimex11.official;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Timestamped history of what the DeX refresh session did, kept in the service process for
 * diagnostics and optionally persisted to storage.
 */
final class DexEventLog {
    private static final int CAPACITY = 200;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private final ArrayDeque<String> entries = new ArrayDeque<>();
    private String lastText;
    private String lastStamp;
    private int repeats;
    private File file;
    private int linesInFile;

    synchronized void persistTo(File target) {
        file = target;
        rewriteFile();
    }

    synchronized void add(String text) {
        String stamp = timeFmt.format(new Date());
        if (text.equals(lastText)) {
            repeats++;
            entries.pollLast();
            entries.addLast(lastStamp + "  " + text + "  (×" + (repeats + 1) + ", last " + stamp + ")");
            return;
        }
        lastText = text;
        lastStamp = stamp;
        repeats = 0;
        entries.addLast(stamp + "  " + text);
        while (entries.size() > CAPACITY) entries.pollFirst();
        if (file == null) return;
        if (linesInFile >= CAPACITY * 4) rewriteFile();
        else write(stamp + "  " + text + "\n", true);
    }

    private void rewriteFile() {
        StringBuilder text = new StringBuilder();
        for (String entry : entries) text.append(entry).append('\n');
        linesInFile = 0;
        write(text.toString(), false);
        linesInFile = entries.size();
    }

    private void write(String text, boolean append) {
        try (FileOutputStream out = new FileOutputStream(file, append)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            if (append) linesInFile++;
        } catch (IOException e) {
            file = null;
        }
    }

    synchronized List<String> entries() {
        return new ArrayList<>(entries);
    }

    synchronized String text() {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String entry : entries) {
            if (i++ > 0) sb.append('\n');
            sb.append(entry);
        }
        return sb.toString();
    }
}
