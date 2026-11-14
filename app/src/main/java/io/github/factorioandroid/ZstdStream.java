package io.github.factorioandroid;

import java.io.IOException;
import java.io.InputStream;

/**
 * Streaming zstd decoder (JNI, decompressor half of zstd 1.5.7 compiled into the APK).
 * Keeps the whole unpacking path free of external executables.
 */
public final class ZstdStream {
    static {
        System.loadLibrary("zstdjni");
    }

    private long handle;

    public ZstdStream() throws IOException {
        handle = nativeCreate();
        if (handle == 0)
            throw new IOException("cannot create zstd decompression stream");
    }

    /** @return packed (consumed &lt;&lt; 32) | produced, or -1 on a corrupted stream */
    public long decompress(byte[] in, int inLen, byte[] out, int outLen) {
        if (handle == 0)
            return -1;
        return nativeDecompress(handle, in, inLen, out, outLen);
    }

    public void close() {
        if (handle != 0) {
            nativeFree(handle);
            handle = 0;
        }
    }

    private static native long nativeCreate();

    private static native void nativeFree(long handle);

    private static native long nativeDecompress(long handle, byte[] in, int inLen, byte[] out, int outLen);
}
