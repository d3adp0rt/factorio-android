package io.github.factorioandroid;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Collects the output of every stage (setup, X server, virgl, audio, game) for the in-app log. */
public final class Logs {
    private static final String TAG = "FactorioAndroid";
    private static final int TAIL_MAX = 400_000;
    private static final StringBuilder tail = new StringBuilder();
    private static File logFile;

    private Logs() {
    }

    /**
     * Returns the end of the shared log file. The file is read instead of the in-memory
     * buffer because the X server runs in another process (:x11) and writes to the same file.
     */
    public static synchronized String tail(Context c) {
        String x11 = readTail(new File(Setup.logsDir(c), "x11.log"), 120_000);
        File f = file(c);
        if (!f.isFile())
            return tail.toString();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - 300_000);
            raf.seek(start);
            byte[] b = new byte[(int) (len - start)];
            raf.readFully(b);
            String main = new String(b, "UTF-8");
            return x11.isEmpty() ? main
                    : "===== X server stderr =====\n" + x11 + "\n===== app log =====\n" + main;
        } catch (IOException e) {
            return x11.isEmpty() ? tail.toString() : x11 + "\n" + tail;
        }
    }

    private static String readTail(File f, int max) {
        if (!f.isFile())
            return "";
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - max);
            raf.seek(start);
            byte[] b = new byte[(int) (len - start)];
            raf.readFully(b);
            return new String(b, "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    public static synchronized void clear(Context c) {
        tail.setLength(0);
        File f = file(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static File file(Context c) {
        if (logFile == null) {
            Setup.logsDir(c).mkdirs();
            logFile = new File(Setup.logsDir(c), "session.log");
        }
        return logFile;
    }

    public static synchronized void add(Context c, String tag, String msg) {
        String line = String.format(Locale.US, "[%s %s] %s\n", tag,
                new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()), msg);
        Log.i(TAG, line.trim());
        tail.append(line);
        if (tail.length() > TAIL_MAX)
            tail.delete(0, tail.length() - TAIL_MAX);
        try (OutputStream os = new FileOutputStream(file(c), true)) {
            os.write(line.getBytes("UTF-8"));
        } catch (IOException ignored) {
        }
    }

    public static void add(Context c, String tag, Throwable t) {
        add(c, tag, t + "\n" + Log.getStackTraceString(t));
    }
}
