/*
 * Minimal JNI wrapper around zstd's streaming decompressor.
 * Used to unpack the bundled rootfs archive without any external binaries.
 */
#include <jni.h>
#include <stdint.h>
#include "zstd.h"

#define EXPORT __attribute__((visibility("default")))

EXPORT jlong JNICALL
Java_io_github_factorioandroid_ZstdStream_nativeCreate(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    ZSTD_DStream *ds = ZSTD_createDStream();
    if (ds == NULL)
        return 0;
    ZSTD_initDStream(ds);
    return (jlong) (intptr_t) ds;
}

EXPORT void JNICALL
Java_io_github_factorioandroid_ZstdStream_nativeFree(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    if (handle != 0)
        ZSTD_freeDStream((ZSTD_DStream *) (intptr_t) handle);
}

/**
 * Feeds inLen compressed bytes from `in`, writes up to outLen decompressed bytes to `out`.
 *
 * @return packed result: (consumed << 32) | produced, or -1 on error.
 */
EXPORT jlong JNICALL
Java_io_github_factorioandroid_ZstdStream_nativeDecompress(JNIEnv *env, jclass clazz,
                                                           jlong handle,
                                                           jbyteArray in, jint inLen,
                                                           jbyteArray out, jint outLen) {
    (void) clazz;
    if (handle == 0)
        return -1;

    jbyte *inBuf = (*env)->GetByteArrayElements(env, in, NULL);
    if (inBuf == NULL)
        return -1;
    jbyte *outBuf = (*env)->GetByteArrayElements(env, out, NULL);
    if (outBuf == NULL) {
        (*env)->ReleaseByteArrayElements(env, in, inBuf, JNI_ABORT);
        return -1;
    }

    ZSTD_inBuffer input = {inBuf, (size_t) inLen, 0};
    ZSTD_outBuffer output = {outBuf, (size_t) outLen, 0};
    size_t rc = ZSTD_decompressStream((ZSTD_DStream *) (intptr_t) handle, &output, &input);
    int error = ZSTD_isError(rc) != 0;
    size_t consumed = input.pos;
    size_t produced = output.pos;

    (*env)->ReleaseByteArrayElements(env, in, inBuf, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, outBuf, 0);

    if (error)
        return -1;
    return (jlong) (((uint64_t) (uint32_t) consumed << 32) | (uint32_t) produced);
}
