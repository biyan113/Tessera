import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * ZUI's autostart manager stops the system from starting a process just to bind a notification
 * listener, so after a reboot, an app update or a kill our listeners stay unbound. Once the process
 * is running for some other reason (home is always running; the panel when opened), withdrawing and
 * granting the access again binds it at once. This watches for each listener app getting a new pid
 * and does that once per pid.
 */
public class Listeners {
    static final String[][] WATCH = {
        {"app.homepad", "app.homepad/app.homepad.BadgeService"},
        {"app.tessera.panel", "app.tessera.panel/app.tessera.panel.NoticeService"},
    };

    static void watch() {
        new Thread(() -> {
            Map<String, Integer> seen = new HashMap<>();
            while (true) {
                try {
                    Map<String, Integer> pids = pids();
                    for (String[] w : WATCH) {
                        Integer pid = pids.get(w[0]);
                        if (pid == null || pid.equals(seen.get(w[0]))) continue;
                        // Give a fresh process a moment to finish starting before binding into it.
                        Thread.sleep(3000);
                        Tesserad.run("cmd notification disallow_listener " + w[1] + "; cmd notification allow_listener " + w[1]);
                        seen.put(w[0], pid);
                    }
                    Thread.sleep(15_000);
                } catch (Exception e) {
                    try { Thread.sleep(15_000); } catch (InterruptedException ignored) {}
                }
            }
        }, "listeners").start();
    }

    /** Main-process pids of the watched packages, read straight from /proc (no shell spawned). */
    static Map<String, Integer> pids() {
        Map<String, Integer> out = new HashMap<>();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return out;
        for (File p : procs) {
            String n = p.getName();
            if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
            try {
                byte[] b = Files.readAllBytes(new File(p, "cmdline").toPath());
                int end = 0;
                while (end < b.length && b[end] != 0) end++;
                String cmd = new String(b, 0, end);
                for (String[] w : WATCH) if (cmd.equals(w[0])) out.put(w[0], Integer.parseInt(n));
            } catch (Exception ignored) {}
        }
        return out;
    }
}
