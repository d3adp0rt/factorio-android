package io.github.factorioandroid;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * First-run setup: unpacks the Android-side helpers (proot/virgl/pulseaudio) and the
 * Linux rootfs (Arch Linux ARM + Factorio) from the APK assets into the app data dir.
 */
public final class Setup {
    /** Bump this whenever the bundled assets change, it forces a re-extract. */
    private static final String SETUP_VERSION = "17";

    public interface Progress {
        /** @param text current step, @param fraction 0..1 or negative if unknown */
        void onProgress(String text, float fraction);
    }

    private Setup() {
    }

    public static File root(Context c) {
        return new File(c.getFilesDir(), "rootfs");
    }

    public static File androidDir(Context c) {
        return new File(c.getFilesDir(), "android");
    }

    public static File binDir(Context c) {
        return new File(c.getFilesDir(), "android/bin");
    }

    public static File tmpDir(Context c) {
        return new File(c.getFilesDir(), "tmp");
    }

    public static File homeDir(Context c) {
        return new File(c.getFilesDir(), "home");
    }

    public static File logsDir(Context c) {
        return new File(c.getFilesDir(), "logs");
    }

    private static File marker(Context c) {
        return new File(c.getFilesDir(), "setup.version");
    }

    private static String readMarker(File f) {
        if (!f.isFile())
            return "";
        try (InputStream is = new java.io.FileInputStream(f)) {
            byte[] b = new byte[64];
            int n = is.read(b);
            return n > 0 ? new String(b, 0, n).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    public static boolean isReady(Context c) {
        return SETUP_VERSION.equals(readMarker(marker(c)));
    }

    private static void writeMarker(File f, String value) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(value.getBytes("UTF-8"));
        }
    }

    public static void run(Context c, Progress p) throws IOException {
        logsDir(c).mkdirs();
        homeDir(c).mkdirs();
        tmpDir(c).mkdirs();
        new File(tmpDir(c), ".X11-unix").mkdirs();
        androidDir(c).mkdirs();

        Logs.add(c, "setup", "unpacking helper binaries");
        // always start from a clean helper tree: stale .so files from older versions
        // could shadow system libraries (that already happened with ANGLE's libEGL.so)
        TarExtractor.deleteRec(androidDir(c), new AndroidFs());
        androidDir(c).mkdirs();
        copyAssets(c.getAssets(), "bin", binDir(c), true);
        // the proot loader is exec'ed as the interpreter of every traced binary -> must be 0755
        copyAssets(c.getAssets(), "proot", new File(androidDir(c), "proot"), true);
        copyAssets(c.getAssets(), "pulse", new File(androidDir(c), "pulse"), false);
        copyAssets(c.getAssets(), "virgl", new File(androidDir(c), "virgl"), false);

        File loader = new File(androidDir(c), "proot/libexec/loader");
        try {
            Logs.add(c, "setup", "proot loader: exists=" + loader.isFile()
                    + " executable=" + android.system.Os.access(loader.getAbsolutePath(),
                    android.system.OsConstants.X_OK));
        } catch (Exception ignored) {
        }

        if (rootfsLooksExtracted(c)) {
            p.onProgress("Root filesystem already extracted", 0.95f);
            Logs.add(c, "setup", "rootfs already present, skipping extraction");
        } else {
            if (root(c).isDirectory()) {
                Logs.add(c, "setup", "removing leftovers of an interrupted extraction");
                TarExtractor.deleteRec(root(c), new AndroidFs());
            }
            extractRootfs(c, p);
        }

        writeRunScript(c);
        writeMarker(marker(c), SETUP_VERSION);
        Logs.add(c, "setup", "setup finished");
        p.onProgress("Ready", 1f);
    }

    private static void copyAssets(AssetManager am, String assetDir, File outDir, boolean executable)
            throws IOException {
        List<String> entries = listAssets(am, assetDir);
        if (entries.isEmpty())
            return;
        if (!outDir.isDirectory() && !outDir.mkdirs())
            throw new IOException("cannot create " + outDir);
        for (String path : entries) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            File out = new File(outDir, name);
            if (!listAssets(am, path).isEmpty()) {
                copyAssets(am, path, out, executable);
                continue;
            }
            try (InputStream is = am.open(path); OutputStream os = new FileOutputStream(out)) {
                byte[] b = new byte[256 * 1024];
                int n;
                while ((n = is.read(b)) > 0)
                    os.write(b, 0, n);
            }
            try {
                android.system.Os.chmod(out.getAbsolutePath(), executable ? 0755 : 0644);
            } catch (Exception ignored) {
            }
        }
    }

    /** Development builds ship without the 1.5 GB archive; it can be dropped into Downloads. */
    private static File findExternalArchive(Context c) {
        List<File> candidates = new ArrayList<>();
        File ext = c.getExternalFilesDir(null);
        if (ext != null) {
            candidates.add(new File(ext, "rootfs.tar.zst"));
            candidates.add(new File(ext, "rootfs.tar"));
        }
        candidates.add(new File("/sdcard/Download/rootfs.tar.zst"));
        candidates.add(new File("/sdcard/Download/rootfs.tar"));
        candidates.add(new File("/storage/emulated/0/Download/rootfs.tar.zst"));
        candidates.add(new File("/sdcard/rootfs.tar.zst"));
        for (File f : candidates)
            if (f.isFile() && f.length() > 100_000_000)
                return f;
        return null;
    }

    private static void extractRootfs(Context c, Progress p) throws IOException {
        AssetManager am = c.getAssets();
        List<String> parts = new ArrayList<>();
        for (String n : listAssets(am, "")) {
            if (n.startsWith("rootfs.tar"))
                parts.add(n);
        }
        Collections.sort(parts);

        InputStream external = null;
        File externalFile = null;
        if (parts.isEmpty()) {
            externalFile = findExternalArchive(c);
            if (externalFile == null)
                throw new IOException("В APK нет rootfs.tar*, и файл не найден в /sdcard/Download/");
            Logs.add(c, "setup", "using rootfs archive from " + externalFile);
            external = new java.io.FileInputStream(externalFile);
        }

        File root = root(c);
        if (!root.isDirectory() && !root.mkdirs())
            throw new IOException("cannot create " + root);

        long total = 0;
        List<InputStream> streams = new ArrayList<>();
        try {
            if (external != null) {
                total = externalFile.length();
                streams.add(external);
            }
            for (String part : parts) {
                try {
                    android.content.res.AssetFileDescriptor afd = am.openFd(part);
                    total += afd.getLength();
                    afd.close();
                } catch (IOException e) {
                    total = -1;
                }
                streams.add(am.open(part, AssetManager.ACCESS_STREAMING));
            }

            long needed = total > 0 ? (long) (total * 1.45) : 2_500_000_000L;
            StatFs sf = new StatFs(root.getAbsolutePath());
            long free = sf.getAvailableBytes();
            Logs.add(c, "setup", "archive parts=" + parts + " total=" + (total / (1024 * 1024))
                    + " MB free=" + (free / (1024 * 1024)) + " MB");
            if (free < needed + 256L * 1024 * 1024)
                throw new IOException("Недостаточно места: нужно ~"
                        + ((needed + 256L * 1024 * 1024) / (1024 * 1024)) + " МБ, свободно "
                        + (free / (1024 * 1024)) + " МБ");

            final long totalForProgress = total;
            InputStream raw = new BufferedInputStream(
                    new SequenceInputStream(Collections.enumeration(streams)), 1 << 20);

            boolean compressed = (external != null)
                    || parts.get(0).endsWith(".zst");
            InputStream tar = compressed
                    ? new ZstdInputStream(raw, consumed -> {
                if (totalForProgress > 0)
                    p.onProgress("Распаковка файлов игры… "
                                    + (consumed / (1024 * 1024)) + " / " + (totalForProgress / (1024 * 1024)) + " МБ",
                            Math.min(0.99f, consumed / (float) totalForProgress));
            })
                    : raw;

            TarExtractor ex = new TarExtractor(tar, root, new AndroidFs());
            long written = ex.extract(done -> {
                if (done % (256L * 1024 * 1024) < 64 * 1024)
                    Logs.add(c, "setup", "extracted " + (done / (1024 * 1024)) + " MB");
            });
            Logs.add(c, "setup", "extraction done, " + (written / (1024 * 1024)) + " MB of tar processed");
        } catch (IOException e) {
            Logs.add(c, "setup", e);
            throw e;
        } finally {
            for (InputStream is : streams) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Installs the launcher script into the game directory. It lives in the APK assets because
     * the game directory is replaced wholesale when the user installs another archive.
     */
    public static void writeRunScript(Context c) {
        File dir = new File(root(c), "opt/factorio");
        if (!dir.isDirectory() && !dir.mkdirs())
            return;
        File out = new File(dir, "run.sh");
        try (InputStream in = c.getAssets().open("run.sh");
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0)
                os.write(buf, 0, n);
            android.system.Os.chmod(out.getAbsolutePath(), 0755);
        } catch (Exception e) {
            Logs.add(c, "setup", "cannot write run.sh: " + e);
        }
    }

    /** True when the Linux environment is unpacked (the game itself is installed separately). */
    private static boolean rootfsLooksExtracted(Context c) {
        File root = root(c);
        return new File(root, "usr/lib").isDirectory()
                && new File(root, "usr/lib/libgallium-26.2.6-arch1.1.so").isFile();
    }

    /** Recursively lists asset paths below {@code path} (empty string = the whole asset tree). */
    private static List<String> listAssets(AssetManager am, String path) {
        try {
            String[] list = am.list(path);
            if (list == null)
                return Collections.emptyList();
            List<String> out = new ArrayList<>();
            for (String n : list)
                out.add(path.isEmpty() ? n : path + "/" + n);
            return out;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /** Removes the unpacked environment; the next start unpacks it again. */
    public static void wipe(Context c) {
        TarExtractor.deleteRec(root(c), new AndroidFs());
        TarExtractor.deleteRec(androidDir(c), new AndroidFs());
        //noinspection ResultOfMethodCallIgnored
        marker(c).delete();
    }
}
