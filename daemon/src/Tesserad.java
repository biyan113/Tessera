import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.IBinder;
import android.system.Os;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

/**
 * Root helper for the Tessera apps. It does the two things an ordinary app cannot:
 * run privileged commands for the panel and settings apps, and notice a swipe in from the top
 * edge without putting any window over other apps.
 * Run: su -c 'CLASSPATH=/data/adb/tessera/tesserad.dex app_process / Tesserad'
 */
public class Tesserad {
    static final String[] CLIENTS = {"app.tessera.panel", "app.tessera.settings", "app.homepad"};
    static final String PANEL = "app.tessera.panel";
    /** Present while the panels replace the system shade. Delete it to hand the shade back. */
    static final File TAKEOVER = new File("/data/adb/tessera/takeover");

    public static void main(String[] a) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> run("cmd statusbar send-disable-flag none")));
        applyTakeover();
        new Thread(Tesserad::serve, "socket").start();
        Sounds.keepApplied();
        Listeners.watch();
        watchTouch();
    }

    static boolean takeover() { return TAKEOVER.exists(); }

    static void applyTakeover() {
        run("cmd statusbar send-disable-flag " + (takeover() ? "statusbar-expansion" : "none"));
    }

    // ---- command socket ----

    static boolean trusted(int uid) {
        if (uid == 0 || uid == 2000) return true;
        for (String p : CLIENTS) {
            try { if (Os.stat("/data/data/" + p).st_uid == uid) return true; } catch (Exception ignored) {}
        }
        return false;
    }

    static void serve() {
        try {
            LocalServerSocket server = new LocalServerSocket("tesserad");
            while (true) {
                LocalSocket s = server.accept();
                new Thread(() -> handle(s)).start();
            }
        } catch (Exception e) {
            System.err.println("socket: " + e);
        }
    }

    static void handle(LocalSocket s) {
        try {
            if (!trusted(s.getPeerCredentials().getUid())) return;
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            String cmd = b.toString("UTF-8").trim();
            String out;
            if (cmd.equals("tessera takeover on")) { TAKEOVER.createNewFile(); applyTakeover(); out = "on\n"; }
            else if (cmd.equals("tessera takeover off")) { TAKEOVER.delete(); applyTakeover(); out = "off\n"; }
            else if (cmd.startsWith("tessera sound\t")) out = Sounds.handle(cmd);
            else out = run(cmd);
            OutputStream o = s.getOutputStream();
            o.write(out.getBytes("UTF-8"));
            o.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    static String run(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroyForcibly();
            return b.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ---- top-edge swipe ----

    static Object wm;
    static Method rotation, locked;

    static int call(Method m) {
        try {
            Object r = m.invoke(wm);
            return r instanceof Boolean ? ((Boolean) r ? 1 : 0) : (Integer) r;
        } catch (Exception e) {
            return 0;
        }
    }

    static String touchDevice() throws Exception {
        String name = null, found = null;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/bus/input/devices"))) {
            for (String l; (l = r.readLine()) != null; ) {
                if (l.startsWith("N: ")) name = l;
                if (l.startsWith("H: ") && name != null && name.contains("TouchScreen")) {
                    for (String t : l.split("[ =]")) if (t.startsWith("event")) found = "/dev/input/" + t;
                }
            }
        }
        return found;
    }

    /** Raw axis maximum, read once from getevent because Java has no ioctl. */
    static int axisMax(String dev, String axis) {
        for (String l : run("getevent -pl " + dev).split("\n")) {
            if (!l.contains(axis)) continue;
            int i = l.indexOf("max ");
            if (i > 0) return Integer.parseInt(l.substring(i + 4, l.indexOf(',', i)).trim());
        }
        return 0;
    }

    static void watchTouch() throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "window");
        wm = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
        rotation = wm.getClass().getMethod("getDefaultDisplayRotation");
        locked = wm.getClass().getMethod("isKeyguardLocked");

        String dev = touchDevice();
        if (dev == null) { System.err.println("no touchscreen"); Thread.sleep(Long.MAX_VALUE); }
        float maxX = axisMax(dev, "ABS_MT_POSITION_X"), maxY = axisMax(dev, "ABS_MT_POSITION_Y");
        String[] size = run("wm size").replaceAll("[^0-9x\n]", "").trim().split("\n")[0].split("x");
        // Physical size is reported for the natural (portrait) orientation, the same frame the panel uses.
        float w = Integer.parseInt(size[0]), h = Integer.parseInt(size[1]);
        float density = Float.parseFloat(run("wm density").replaceAll("[^0-9\n]", "").trim().split("\n")[0]) / 160f;
        float edge = 26 * density, pull = 22 * density;

        FileInputStream in = new FileInputStream(dev);
        ByteBuffer ev = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        byte[] raw = ev.array();
        int slot = 0, fingers = 0;
        float x = 0, y = 0, x0 = 0, y0 = 0;
        boolean fresh = false, armed = false, fired = false;
        int rot = 0;
        float dw = w, dh = h;
        while (true) {
            int got = 0;
            while (got < 24) { int n = in.read(raw, got, 24 - got); if (n < 0) return; got += n; }
            int type = ev.getShort(16) & 0xffff, code = ev.getShort(18) & 0xffff, value = ev.getInt(20);
            if (type == 3) {
                if (code == 0x2f) slot = value;
                else if (code == 0x39) {
                    if (value >= 0) { fingers++; if (slot == 0 && fingers == 1) fresh = true; }
                    else { fingers = Math.max(0, fingers - 1); if (slot == 0) armed = false; }
                } else if (slot == 0 && code == 0x35) x = value * w / maxX;
                else if (slot == 0 && code == 0x36) y = value * h / maxY;
            } else if (type == 0 && code == 0) {
                if (fresh) {
                    fresh = false;
                    fired = false;
                    armed = false;
                    // Only a touch that lands on some physical edge is worth a binder call.
                    if (x < edge || y < edge || x > w - edge || y > h - edge) {
                        rot = call(rotation);
                        dw = rot % 2 == 0 ? w : h;
                        dh = rot % 2 == 0 ? h : w;
                        x0 = dx(x, y, rot, w, h);
                        y0 = dy(x, y, rot, w, h);
                        armed = y0 < edge;
                    }
                } else if (armed && !fired && fingers == 1) {
                    float cx = dx(x, y, rot, w, h), cy = dy(x, y, rot, w, h);
                    if (cy - y0 > pull && cy - y0 > Math.abs(cx - x0)) {
                        fired = true;
                        if (takeover() && call(locked) == 0) open(x0 > dw * 0.7f ? ".ControlActivity" : ".NoticeActivity");
                    }
                }
            }
        }
    }

    static float dx(float x, float y, int rot, float w, float h) {
        return rot == 0 ? x : rot == 1 ? y : rot == 2 ? w - x : h - y;
    }

    static float dy(float x, float y, int rot, float w, float h) {
        return rot == 0 ? y : rot == 1 ? w - x : rot == 2 ? h - y : x;
    }

    static void open(String activity) {
        try {
            // `cmd` is a small native binary; spawning `am` would cost a whole runtime start per swipe.
            new ProcessBuilder("cmd", "activity", "start-activity", "--user", "0", "-n", PANEL + "/" + activity, "-f", "0x10010000")
                    .redirectErrorStream(true).start();
        } catch (Exception ignored) {
        }
    }
}
