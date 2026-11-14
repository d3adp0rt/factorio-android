package io.github.factorioandroid;

import java.io.IOException;
import java.io.InputStream;

/**
 * Decompresses a zstd stream on top of another InputStream (JNI, zstd 1.5.7 compiled
 * into the APK), so the tar archive can be read as if it were a plain file.
 */
public final class ZstdInputStream extends InputStream {
    /** Called with the number of compressed bytes consumed so far. */
    public interface Progress {
        void onCompressed(long consumed);
    }

    private final InputStream src;
    private final ZstdStream zstd;
    private final Progress progress;

    private final byte[] in = new byte[1 << 20];
    private final byte[] out = new byte[1 << 20];
    private int outPos, outLen;
    private int inPos, inLen;
    private long consumed;
    private boolean srcEof;
    private boolean streamEnd;

    public ZstdInputStream(InputStream src, Progress progress) throws IOException {
        this.src = src;
        this.zstd = new ZstdStream();
        this.progress = progress;
    }

    private boolean fill() throws IOException {
        while (outPos >= outLen) {
            if (streamEnd)
                return false;

            if (inLen == 0) {
                if (srcEof) {
                    streamEnd = true;
                    return false;
                }
                int n = src.read(in, 0, in.length);
                if (n <= 0) {
                    srcEof = true;
                    streamEnd = true;
                    return false;
                }
                inLen = n;
                consumed += n;
                if (progress != null)
                    progress.onCompressed(consumed);
            }

            long r = zstd.decompress(in, inLen, out, out.length);
            if (r < 0)
                throw new IOException("corrupted zstd archive");
            int used = (int) (r >>> 32);
            int produced = (int) (r & 0xFFFFFFFFL);

            if (used > 0 && used < inLen)
                System.arraycopy(in, used, in, 0, inLen - used);
            inLen -= used;

            if (produced > 0) {
                outPos = 0;
                outLen = produced;
            } else if (used == 0) {
                streamEnd = true;
                return false;
            }
        }
        return true;
    }

    @Override
    public int read() throws IOException {
        if (!fill())
            return -1;
        return out[outPos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0)
            return 0;
        if (!fill())
            return -1;
        int n = Math.min(len, outLen - outPos);
        System.arraycopy(out, outPos, b, off, n);
        outPos += n;
        return n;
    }

    @Override
    public void close() throws IOException {
        zstd.close();
        src.close();
    }
}
