package io.github.factorioandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.os.Build;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.system.Os;

import com.termux.x11.FactorioX11;

import java.io.File;

/**
 * Hosts the Lorie X server in a dedicated process ({@code :x11}) of this app.
 *
 * <p>Termux:X11 normally starts the server with {@code /system/bin/app_process} (a trick that
 * only works for apps targeting API 28 and that is blocked/brittle on modern Android builds).
 * A secondary process of our own app is safer: normal SELinux context, normal process start,
 * no exec at all.</p>
 */
public class XServerService extends Service {
    public static final String DISPLAY = ":0";

    private volatile boolean started;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (started)
            return START_STICKY;
        started = true;

        // a foreground service keeps the X server process out of the low-memory kill list
        String channel = "factorio";
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(channel,
                    getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channel)
                : new Notification.Builder(this);
        startForeground(4712, nb.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.x11_running))
                .setSmallIcon(R.drawable.ic_stat)
                .setOngoing(true)
                .build());

        final File root = Setup.root(this);
        final File tmp = Setup.tmpDir(this);

        final File xkb = xkbDir(root);
        //noinspection ResultOfMethodCallIgnored
        new File(tmp, ".X11-unix").mkdirs();

        // the X server writes its diagnostics (GLX/DRI/probe messages) to stderr
        try {
            File x11log = new File(Setup.logsDir(this), "x11.log");
            android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.open(x11log,
                    android.os.ParcelFileDescriptor.MODE_CREATE
                            | android.os.ParcelFileDescriptor.MODE_WRITE_ONLY
                            | android.os.ParcelFileDescriptor.MODE_APPEND);
            Os.dup2(pfd.getFileDescriptor(), 2);
            pfd.close();
        } catch (Exception e) {
            Logs.add(this, "x11", "cannot redirect stderr: " + e);
        }

        try {
            Os.setenv("TMPDIR", tmp.getAbsolutePath(), true);
            Os.setenv("TMP", tmp.getAbsolutePath(), true);
            Os.setenv("HOME", Setup.homeDir(this).getAbsolutePath(), true);
            Os.setenv("XKB_CONFIG_ROOT", xkb.getAbsolutePath(), true);
            Os.setenv("PATH", "/system/bin:/system/xbin", true);
        } catch (Exception e) {
            Logs.add(this, "x11", e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                Logs.add(this, "x11", "X server process is exiting")));

        Thread t = new Thread(() -> {
            Looper.prepare();
            try {
                FactorioX11.loadLibrary();
                Logs.add(XServerService.this, "x11", "libXlorie loaded");
            } catch (Throwable e) {
                Logs.add(XServerService.this, "x11", "cannot load libXlorie: " + e);
                return;
            }
            FactorioX11.setContext(getApplicationContext());
            Logs.add(XServerService.this, "x11", "starting X server on display " + DISPLAY
                    + " | TMPDIR=" + tmp.getAbsolutePath() + " (exists=" + tmp.isDirectory() + ")"
                    + " | XKB_CONFIG_ROOT=" + xkb.getAbsolutePath() + " (exists=" + xkb.isDirectory() + ")");
            boolean ok;
            try {
                ok = FactorioX11.start(new String[]{DISPLAY});
            } catch (Throwable e) {
                Logs.add(XServerService.this, "x11", "X server crashed: " + e);
                return;
            }
            Logs.add(XServerService.this, "x11", "X server start() returned " + ok);
            if (!ok)
                return;
            Looper.loop();
        }, "x11-server");
        t.start();

        return START_STICKY;
    }

    /**
     * usr/share/X11/xkb inside the rootfs is an absolute symlink to /usr/share/xkeyboard-config-2,
     * which does not exist on Android, so the server must be pointed at the real directory.
     */
    static File xkbDir(File root) {
        File dir = new File(root, "usr/share/xkeyboard-config-2");
        if (dir.isDirectory())
            return dir;
        return new File(root, "usr/share/X11/xkb");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
