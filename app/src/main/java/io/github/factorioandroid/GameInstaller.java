package io.github.factorioandroid;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.util.zip.GZIPInputStream;

/**
 * Installs the user's own Factorio build.
 *
 * <p>The app deliberately ships without the game (it is proprietary): the launcher asks for
 * a tar archive of a Factorio Linux installation and unpacks it into {@code <rootfs>/opt/factorio}.
 * Accepted containers: plain {@code .tar}, {@code .tar.gz}, {@code .tar.xz} and {@code .tar.zst}
 * (the official download is a {@code .tar.xz}), sniffed by magic bytes, so the file name does
 * not matter.
 *
 * <p>The archive is unpacked into a staging directory first and only then swapped in, so a
 * broken or wrong archive never destroys a working installation.
 */
public final class GameInstaller {
    public interface Progress {
        void onProgress(String text, float fraction);
    }

    /** Marker file that tells us the game is present. */
    private static final String BIN = "bin/arm64/factorio";

    private GameInstaller() {
    }

    public static File gameDir(Context c) {
        return new File(Setup.root(c), "opt/factorio");
    }

    public static boolean installed(Context c) {
        return new File(gameDir(c), BIN).isFile();
    }

    /** Version from {@code data/base/info.json}, or an empty string. */
    public static String version(Context c) {
        JSONObject info = infoJson(c, "base");
        return info == null ? "" : info.optString("version", "");
    }

    /** True when the Space Age (or any) expansion data is installed. */
    public static boolean hasExpansion(Context c) {
        File data = new File(gameDir(c), "data");
        if (new File(data, "space-age").isDirectory())
            return true;
        File[] dirs = data.listFiles();
        if (dirs == null)
            return false;
        for (File d : dirs) {
            JSONObject info = infoJson(c, d.getName());
            if (info != null && "space-age".equals(info.optString("name")))
                return true;
        }
        return false;
    }

    private static JSONObject infoJson(Context c, String mod) {
        File f = new File(new File(new File(gameDir(c), "data"), mod), "info.json");
        if (!f.isFile())
            return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null)
                sb.append(line).append('\n');
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Unpacks {@code uri} into the game directory. On any failure the previous installation is
     * left untouched.
     */
    public static void install(Context c, Uri uri, Progress p) throws IOException {
        File opt = new File(Setup.root(c), "opt");
        File staging = new File(opt, ".factorio-import");
        File target = gameDir(c);
        TarExtractor.deleteRec(staging, new AndroidFs());
        if (!staging.mkdirs())
            throw new IOException("cannot create " + staging);

        p.onProgress("Reading the archive…", 0.02f);
        try (InputStream raw = open(c, uri);
             InputStream stream = decompress(raw)) {
            TarExtractor tar = new TarExtractor(stream, staging, new AndroidFs());
            tar.extract(done -> {
                float f = Math.min(0.9f, done / (600f * 1024 * 1024));
                p.onProgress("Unpacking… " + (done >> 20) + " MB", f);
            });
        } catch (IOException e) {
            TarExtractor.deleteRec(staging, new AndroidFs());
            throw e;
        }

        // The official archive contains a single top level folder ("factorio/"), a hand made
        // tar usually does not. Find the directory that really holds the game.
        File root = findGameRoot(staging);
        if (root == null) {
            TarExtractor.deleteRec(staging, new AndroidFs());
            throw new IOException("this archive does not look like a Factorio installation"
                    + " (no " + BIN + ")");
        }

        p.onProgress("Installing…", 0.92f);
        TarExtractor.deleteRec(target, new AndroidFs());
        if (!root.renameTo(target)) {
            TarExtractor.deleteRec(staging, new AndroidFs());
            throw new IOException("cannot move the unpacked game into " + target);
        }
        TarExtractor.deleteRec(staging, new AndroidFs());
        p.onProgress("Installed", 1f);
    }

    private static File findGameRoot(File staging) {
        if (new File(staging, BIN).isFile())
            return staging;
        File[] children = staging.listFiles();
        if (children == null)
            return null;
        File found = null;
        for (File child : children) {
            if (child.isDirectory() && new File(child, BIN).isFile()) {
                if (found != null)
                    return null; // ambiguous
                found = child;
            }
        }
        return found;
    }

    private static InputStream open(Context c, Uri uri) throws IOException {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null)
            throw new IOException("cannot open " + uri);
        return in;
    }

    /** Sniffs the first bytes so .tar / .tar.gz / .tar.xz / .tar.zst all work. */
    private static InputStream decompress(InputStream raw) throws IOException {
        PushbackInputStream in = new PushbackInputStream(raw, 6);
        byte[] magic = new byte[6];
        int n = 0;
        while (n < magic.length) {
            int r = in.read(magic, n, magic.length - n);
            if (r <= 0)
                break;
            n += r;
        }
        in.unread(magic, 0, n);

        if (n >= 2 && (magic[0] & 0xFF) == 0x1F && (magic[1] & 0xFF) == 0x8B)
            return new GZIPInputStream(in, 1 << 16);
        if (n >= 6 && (magic[0] & 0xFF) == 0xFD && magic[1] == '7' && magic[2] == 'z'
                && magic[3] == 'X' && magic[4] == 'Z' && magic[5] == 0)
            return new XZInputStream(in);
        if (n >= 4 && (magic[0] & 0xFF) == 0x28 && (magic[1] & 0xFF) == 0xB5
                && (magic[2] & 0xFF) == 0x2F && (magic[3] & 0xFF) == 0xFD) {
            try {
                return new ZstdInputStream(in, null);
            } catch (IOException e) {
                throw new IOException("zstd: " + e.getMessage(), e);
            }
        }
        return in; // plain tar
    }

    /** Copies a single file (a save or a mod) into the given game subdirectory. */
    public static void importFile(Context c, Uri uri, String subdir, String fallbackName) throws IOException {
        String name = fallbackName;
        if (uri.getLastPathSegment() != null) {
            String seg = uri.getLastPathSegment();
            int slash = seg.lastIndexOf('/');
            name = slash >= 0 ? seg.substring(slash + 1) : seg;
        }
        if (name.isEmpty())
            name = fallbackName;
        File dir = new File(gameDir(c), subdir);
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("cannot create " + dir);
        File out = new File(dir, name);
        try (InputStream in = open(c, uri); java.io.OutputStream os = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) > 0)
                os.write(buf, 0, r);
        }
    }
}
