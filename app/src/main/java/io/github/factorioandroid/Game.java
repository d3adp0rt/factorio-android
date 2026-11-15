package io.github.factorioandroid;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Starts and stops the three processes that make the game run:
 * <ul>
 *   <li>the X11 server (Lorie, runs as {@code app_process com.termux.x11.CmdEntryPoint :0}),</li>
 *   <li>optionally the VirGL render server (hardware accelerated OpenGL for the game),</li>
 *   <li>optionally PulseAudio (sound via Android OpenSL ES),</li>
 *   <li>the game itself inside a proot'ed Arch Linux ARM rootfs.</li>
 * </ul>
 */
public final class Game {
    private static final String TAG = "FactorioAndroid";
    public static final String DISPLAY = ":0";

    private static Process game, virgl, pulse;
    private static volatile boolean xStarted;
    private Game() {
    }

    private static File xSocket(Context c) {
        return new File(new File(Setup.tmpDir(c), ".X11-unix"), "X0");
    }

    /**
     * True while the X server is actually alive. The socket is the source of truth: the flag
     * alone lies when the :x11 process was killed (which happens when the system reclaims the
     * process, or when the user quits the game and the server is gone for any reason).
     */
    public static boolean isXRunning(Context c) {
        if (!xStarted)
            return false;
        if (!xSocket(c).exists()) {
            Logs.add(c, "x11", "the X server socket is gone - the server is not running");
            xStarted = false;
            return false;
        }
        return true;
    }

    /** Called from the launcher: keeps the UI in sync with a server that died. */
    public static void syncXState(Context c) {
        isXRunning(c);
    }

    public static synchronized boolean isGameRunning() {
        return game != null && game.isAlive();
    }

    private static void log(Context c, String tag, String msg) {
        Logs.add(c, tag, msg);
    }

    /** Turns the cryptic failures into one line the user can act on. */
    private static void hint(Context c, String tag, String line) {
        if (!"game".equals(tag))
            return;
        if (line.contains("fatal IO error") && line.contains("X server")) {
            log(c, "game", ">> lost the X server. If this happened while loading sprites the"
                    + " device ran out of memory: lower the resolution or restart the app");
        } else if (line.contains("lost connection to rendering server")) {
            log(c, "game", ">> the virgl render server died, see the [virgl] lines above");
        }
    }

    private static void pump(final Context c, final String tag, final Process p) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            StringBuilder line = new StringBuilder();
            try (InputStream is = p.getInputStream()) {
                int n;
                while ((n = is.read(buf)) > 0) {
                    for (int i = 0; i < n; i++) {
                        char ch = (char) (buf[i] & 0xFF);
                        if (ch == '\n') {
                            String s = line.toString();
                            log(c, tag, s);
                            hint(c, tag, s);
                            line.setLength(0);
                        } else if (line.length() < 4000) {
                            line.append(ch);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
            if (line.length() > 0)
                log(c, tag, line.toString());
            try {
                log(c, tag, "<process ended, exit code " + p.exitValue() + ">");
            } catch (IllegalThreadStateException e) {
                log(c, tag, "<process ended>");
            }
        }, "log-" + tag);
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- X server

    public static synchronized void startX(final Context c) {
        if (xStarted)
            return;
        //noinspection ResultOfMethodCallIgnored
        new File(Setup.tmpDir(c), ".X11-unix").mkdirs();
        log(c, "x11", "starting X server in the :x11 process");
        c.startService(new Intent(c, XServerService.class));
        xStarted = true;
    }

    /** Waits until the X server created its unix socket. */
    public static boolean waitForX(Context c, long timeoutMs) throws InterruptedException {
        File sock = xSocket(c);
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (sock.exists())
                return true;
            Thread.sleep(200);
        }
        return sock.exists();
    }

    // ---------------------------------------------------------------- helpers

    private static void startVirgl(final Context c) {
        if (virgl != null && virgl.isAlive())
            return;
        File bin = Setup.binDir(c);
        File sock = new File(Setup.tmpDir(c), ".virgl_test");
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(bin, "virgl_test_server").getAbsolutePath());
        cmd.add("--socket-path");
        cmd.add(sock.getAbsolutePath());
        // Android/mali only exposes OpenGL ES through EGL; upstream vrend defaults to a
        // desktop GL context, which simply cannot be created there.
        cmd.add("--use-gles");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", new File(Setup.androidDir(c), "virgl").getAbsolutePath());
        env.put("TMPDIR", Setup.tmpDir(c).getAbsolutePath());
        env.put("HOME", Setup.homeDir(c).getAbsolutePath());
        env.put("PATH", "/system/bin:/system/xbin");
        env.put("VIRGL_LOG_LEVEL", "info");
        pb.redirectErrorStream(true);
        try {
            log(c, "virgl", "starting virgl server: " + String.join(" ", cmd));
            virgl = pb.start();
            pump(c, "virgl", virgl);
        } catch (IOException e) {
            log(c, "virgl", "failed to start: " + e);
        }
    }

    private static void startPulse(final Context c) {
        if (pulse != null && pulse.isAlive())
            return;
        File bin = Setup.binDir(c);
        File modules = new File(Setup.androidDir(c), "pulse/modules");
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(bin, "pulseaudio").getAbsolutePath());
        cmd.add("-n");                                     // do not read config files
        cmd.add("--exit-idle-time=-1");
        cmd.add("-L");
        cmd.add(new File(modules, "module-native-protocol-tcp.so").getAbsolutePath()
                + " auth-anonymous=1 port=4713 listen=127.0.0.1");
        cmd.add("-L");
        cmd.add(new File(modules, "module-sles-sink.so").getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", new File(Setup.androidDir(c), "pulse").getAbsolutePath());
        env.put("TMPDIR", Setup.tmpDir(c).getAbsolutePath());
        env.put("HOME", Setup.homeDir(c).getAbsolutePath());
        env.put("PATH", "/system/bin:/system/xbin");
        pb.redirectErrorStream(true);
        try {
            log(c, "audio", "starting pulseaudio");
            pulse = pb.start();
            pump(c, "audio", pulse);
        } catch (IOException e) {
            log(c, "audio", "failed to start pulseaudio: " + e);
        }
    }

    // ---------------------------------------------------------------- the game

    public static synchronized void startGame(final Context c) throws IOException {
        if (isGameRunning()) {
            log(c, "game", "already running");
            return;
        }
        File root = Setup.root(c);
        File bin = Setup.binDir(c);
        File tmp = Setup.tmpDir(c);

        boolean gpu = Config.isGpu(c);
        String backend = Config.renderer(c);
        if (gpu)
            startVirgl(c);
        if (Config.audio(c))
            startPulse(c);

        List<String> cmd = new ArrayList<>();
        cmd.add(new File(bin, "proot").getAbsolutePath());
        cmd.add("--kill-on-exit");
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(root.getAbsolutePath());
        cmd.add("-b");
        cmd.add(tmp.getAbsolutePath() + ":/tmp");
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        File shm = new File(tmp, "shm");
        //noinspection ResultOfMethodCallIgnored
        shm.mkdirs();
        cmd.add("-b");
        cmd.add(shm.getAbsolutePath() + ":/dev/shm");
        cmd.add("-w");
        cmd.add("/opt/factorio");
        cmd.add("/bin/bash");
        cmd.add("/opt/factorio/run.sh");

        String extra = Config.extraArgs(c).trim();
        if (!extra.isEmpty())
            for (String a : extra.split("\\s+"))
                cmd.add(a);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        File prootLibs = new File(Setup.androidDir(c), "proot");
        env.put("LD_LIBRARY_PATH", prootLibs.getAbsolutePath());
        env.put("PROOT_LOADER", new File(prootLibs, "libexec/loader").getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        if (Config.noSeccomp(c))
            env.put("PROOT_NO_SECCOMP", "1");
        env.put("HOME", Setup.homeDir(c).getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("PATH", "/system/bin:/system/xbin");
        env.put("DISPLAY", DISPLAY);
        env.put("XDG_RUNTIME_DIR", "/tmp");
        env.put("PULSE_SERVER", "tcp:127.0.0.1:4713");
        // there is no window manager in the container, so SDL may think its window is not
        // focused and then silently drops mouse clicks and the wheel
        env.put("SDL_MOUSE_FOCUS_CLICKTHROUGH", "1");
        if (Config.CPU.equals(backend)) {
            env.put("GALLIUM_DRIVER", "llvmpipe");
            env.put("LIBGL_ALWAYS_SOFTWARE", "1");
            env.put("MESA_GL_VERSION_OVERRIDE", "4.5");
            env.put("MESA_GLSL_VERSION_OVERRIDE", "450");
        } else {
            env.put("GALLIUM_DRIVER", "virpipe");
            env.put("MESA_GL_VERSION_OVERRIDE", "4.3");
            env.put("MESA_GLSL_VERSION_OVERRIDE", "430");
        }
        // run.sh prints glxinfo/xdpyinfo diagnostics into the app log before starting the game
        env.put("DIAG", "1");
        if (gpu)
            env.put("GPU", "1");
        env.put("BACKEND", backend);
        pb.redirectErrorStream(true);

        log(c, "game", "starting: " + String.join(" ", cmd));
        game = pb.start();
        pump(c, "game", game);
        watchForFastFailure(c, backend);
    }

    /**
     * Does not switch anything by itself any more: the renderer is an explicit choice in the
     * settings now, and silently jumping to another backend makes benchmarking impossible.
     */
    private static void watchForFastFailure(final Context c, final String backend) {
        if (Config.CPU.equals(backend))
            return;
        final Process proc = game;
        final long started = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            try {
                proc.waitFor();
            } catch (InterruptedException e) {
                return;
            }
            long lived = System.currentTimeMillis() - started;
            if (lived > 30_000) {
                log(c, "game", backend + " run finished after " + (lived / 1000) + "s");
                return;
            }
            log(c, "game", "renderer '" + backend + "' died after " + lived
                    + " ms - see the log above, then pick another renderer in the settings");
        }, "gpu-watch");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stopAll(Context c) {
        log(c, "main", "stopping everything");
        kill(game);
        kill(virgl);
        kill(pulse);
        game = virgl = pulse = null;
        if (xStarted) {
            c.stopService(new Intent(c, XServerService.class));
            xStarted = false;
        }
    }

    private static void kill(Process p) {
        if (p == null)
            return;
        try {
            p.destroy();
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                p.destroyForcibly();
        } catch (Exception ignored) {
        }
    }
}
