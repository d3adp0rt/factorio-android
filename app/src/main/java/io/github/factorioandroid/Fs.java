package io.github.factorioandroid;

import java.io.File;
import java.io.IOException;

/** The few file system primitives the tar extractor needs (so it stays testable on a plain JVM). */
public interface Fs {
    void chmod(File f, int mode);

    boolean isSymlink(File f);

    void symlink(String target, File link) throws IOException;

    void hardlink(File source, File link) throws IOException;
}
