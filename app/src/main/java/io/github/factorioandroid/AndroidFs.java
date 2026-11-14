package io.github.factorioandroid;

import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;

/** Android implementation of {@link Fs}. */
public final class AndroidFs implements Fs {
    @Override
    public void chmod(File f, int mode) {
        try {
            Os.chmod(f.getAbsolutePath(), mode & 07777);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean isSymlink(File f) {
        try {
            int mode = Os.lstat(f.getAbsolutePath()).st_mode;
            return (mode & OsConstants.S_IFMT) == OsConstants.S_IFLNK;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void symlink(String target, File link) throws IOException {
        try {
            Os.symlink(target, link.getAbsolutePath());
        } catch (Exception e) {
            throw new IOException("symlink " + link + " -> " + target + ": " + e);
        }
    }

    @Override
    public void hardlink(File source, File link) throws IOException {
        try {
            Os.link(source.getAbsolutePath(), link.getAbsolutePath());
        } catch (Exception e) {
            throw new IOException("link " + link + " -> " + source + ": " + e);
        }
    }
}
