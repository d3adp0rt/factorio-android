package io.github.factorioandroid;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal streaming tar extractor (ustar + GNU long names + PAX "path"), independent of
 * external tools. Extracts into {@code root} through an {@link Fs} abstraction so the same
 * code can be exercised in a plain JVM test.
 */
public final class TarExtractor {

    public interface Progress {
        void onProgress(long done);
    }

    private final InputStream in;
    private final File root;
    private final Fs fs;
    private final byte[] buf = new byte[64 * 1024];
    private long done;

    private String pendingName;
    private String pendingLink;

    public TarExtractor(InputStream in, File root, Fs fs) {
        this.in = in;
        this.root = root;
        this.fs = fs;
    }

    /** Extracts the whole stream. Returns the number of bytes consumed. */
    public long extract(Progress p) throws IOException {
        byte[] hdr = new byte[512];
        while (true) {
            if (!readHeader(hdr))
                break;
            if (isZero(hdr))
                break;

            String name = str(hdr, 0, 100);
            String prefix = str(hdr, 345, 155);
            long size = number(hdr, 124, 12);
            int type = hdr[156] & 0xFF;
            long mode = number(hdr, 100, 8);
            String link = str(hdr, 157, 100);

            if (type == 'x' || type == 'g') { // PAX extended header
                byte[] data = new byte[(int) size];
                readFully(data, (int) size);
                skipPadding(size);
                String text = new String(data, StandardCharsets.UTF_8);
                for (String rec : text.split("\n")) {
                    int eq = rec.indexOf('=');
                    if (eq <= 0)
                        continue;
                    String k = rec.substring(0, eq).trim();
                    String v = rec.substring(eq + 1);
                    if (!v.isEmpty() && v.charAt(v.length() - 1) == '\n')
                        v = v.substring(0, v.length() - 1);
                    if (k.endsWith("path"))
                        pendingName = v;
                    else if (k.endsWith("linkpath"))
                        pendingLink = v;
                }
                continue;
            }
            if (type == 'L') { // GNU long name
                byte[] data = new byte[(int) size];
                readFully(data, (int) size);
                skipPadding(size);
                pendingName = new String(data, StandardCharsets.UTF_8).trim();
                continue;
            }
            if (type == 'K') { // GNU long link name
                byte[] data = new byte[(int) size];
                readFully(data, (int) size);
                skipPadding(size);
                pendingLink = new String(data, StandardCharsets.UTF_8).trim();
                continue;
            }
            if (type == 'V') { // volume label
                skipData(size);
                continue;
            }

            String fullName = pendingName != null ? pendingName
                    : (prefix.isEmpty() ? name : prefix + "/" + name);
            String fullLink = pendingLink != null ? pendingLink : link;
            pendingName = null;
            pendingLink = null;

            File target = resolve(fullName);
            if (target == null) {
                skipData(size);
                continue;
            }

            if (type == '5') {
                mkdirs(target);
                fs.chmod(target, (int) (mode | 0700));
                continue;
            }
            if (type == '2') { // symlink
                mkdirs(target.getParentFile());
                if (target.exists() || fs.isSymlink(target))
                    deleteRec(target, fs);
                try {
                    fs.symlink(fullLink, target);
                } catch (IOException ignored) {
                    // a missing symlink is rarely fatal, the file it points at is elsewhere
                }
                continue;
            }
            if (type == '1') { // hard link
                File src = resolve(fullLink);
                if (src != null && src.exists()) {
                    mkdirs(target.getParentFile());
                    if (target.exists())
                        deleteRec(target, fs);
                    try {
                        fs.hardlink(src, target);
                    } catch (IOException e) {
                        copyFile(src, target);
                    }
                }
                continue;
            }
            if (type == '0' || type == 0 || type == '7') { // regular file
                mkdirs(target.getParentFile());
                if (target.exists() && target.isDirectory())
                    deleteRec(target, fs);
                writeFile(target, size, p);
                fs.chmod(target, (int) mode);
                skipPadding(size);
                continue;
            }
            // devices, fifos and other special entries are meaningless here
            skipData(size);
        }
        return done;
    }

    private void writeFile(File target, long size, Progress p) throws IOException {
        try (OutputStream os = new FileOutputStream(target)) {
            long left = size;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0)
                    throw new IOException("unexpected end of archive while writing " + target);
                done += n;
                left -= n;
                os.write(buf, 0, n);
                if (p != null)
                    p.onProgress(done);
            }
        }
    }

    private void skipData(long size) throws IOException {
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0)
                throw new IOException("unexpected end of archive");
            done += n;
            left -= n;
        }
        skipPadding(size);
    }

    private void skipPadding(long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        while (pad > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, pad));
            if (n < 0)
                throw new IOException("unexpected end of archive");
            done += n;
            pad -= n;
        }
    }

    private boolean readHeader(byte[] b) throws IOException {
        int off = 0;
        while (off < 512) {
            int n = in.read(b, off, 512 - off);
            if (n < 0)
                return off != 0 && fail("truncated header");
            off += n;
            done += n;
        }
        return true;
    }

    private void readFully(byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0)
                throw new IOException("unexpected end of archive");
            off += n;
            done += n;
        }
    }

    private static boolean fail(String msg) throws IOException {
        throw new IOException(msg);
    }

    private File resolve(String name) {
        String n = name;
        while (n.startsWith("./"))
            n = n.substring(2);
        while (n.startsWith("/"))
            n = n.substring(1);
        if (n.isEmpty() || n.contains(".."))
            return null;
        return new File(root, n);
    }

    private static void mkdirs(File dir) {
        if (dir != null && !dir.isDirectory())
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
    }

    private static void copyFile(File src, File dst) {
        try (InputStream is = new java.io.FileInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = is.read(b)) > 0)
                os.write(b, 0, n);
        } catch (IOException ignored) {
        }
    }

    public static void deleteRec(File f, Fs fs) {
        if (f.isDirectory() && !fs.isSymlink(f)) {
            File[] kids = f.listFiles();
            if (kids != null)
                for (File k : kids)
                    deleteRec(k, fs);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static boolean isZero(byte[] b) {
        for (byte v : b)
            if (v != 0)
                return false;
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0)
            end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    /** Octal header field, with support for GNU base-256 encoding of large values. */
    private static long number(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) {
            long v = b[off] & 0x7F;
            for (int i = off + 1; i < off + len; i++)
                v = (v << 8) | (b[i] & 0xFF);
            return v;
        }
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ')
                continue;
            if (c < '0' || c > '7')
                continue;
            v = v * 8 + (c - '0');
        }
        return v;
    }
}
