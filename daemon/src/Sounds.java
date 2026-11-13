import android.app.NotificationChannel;
import android.net.Uri;
import android.os.IBinder;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-app notification sounds. A sound lives on each of an app's notification channels, and only
 * the system may change another app's channel, so the settings app asks us. Commands arrive as
 * "tessera sound" followed by tab-separated arguments, because channel ids may contain spaces:
 *   list PKG            one line per channel: id, importance, sound, locked, eligible, name
 *   sum PKG...          one line per app: pkg, then "-" (nothing to set), "*" (mixed), "none" or the uri
 *   set PKG ID|* URI    URI may be "default" or "none"; "*" means every eligible channel and is remembered
 * Remembered app-wide choices are re-applied to channels the app creates later.
 */
public class Sounds {
    static final String DEFAULT = "content://settings/system/notification_sound";
    static final File RULES = new File("/data/adb/tessera/sounds");
    /** NotificationChannel.USER_LOCKED_SOUND: set once the user (or we) chose this channel's sound. */
    static final int LOCKED_SOUND = 0x20;

    static Object nm, pm;
    static Method channels, update, uidOf, list, lockedFields;

    static synchronized void init() throws Exception {
        if (nm != null) return;
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "notification");
        nm = Class.forName("android.app.INotificationManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
        channels = nm.getClass().getMethod("getNotificationChannelsForPackage", String.class, int.class, boolean.class);
        update = nm.getClass().getMethod("updateNotificationChannelForPackage", String.class, int.class, NotificationChannel.class);
        pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
        uidOf = pm.getClass().getMethod("getPackageUid", String.class, long.class, int.class);
        lockedFields = NotificationChannel.class.getMethod("getUserLockedFields");
    }

    static int uid(String pkg) throws Exception { return (Integer) uidOf.invoke(pm, pkg, 0L, 0); }

    static List<NotificationChannel> of(String pkg, int uid) throws Exception {
        Object slice = channels.invoke(nm, pkg, uid, false);
        if (list == null) list = slice.getClass().getMethod("getList");
        List<NotificationChannel> out = new ArrayList<>();
        for (Object o : (List<?>) list.invoke(slice)) out.add((NotificationChannel) o);
        return out;
    }

    static boolean locked(NotificationChannel c) {
        try { return ((Integer) lockedFields.invoke(c) & LOCKED_SOUND) != 0; } catch (Exception e) { return false; }
    }

    /**
     * Channels an app-wide choice applies to: ones that would make a sound and use a system or
     * media-library sound. Silent channels the app chose and the app's own bundled ringtones
     * (a call ring, say) are left alone; a channel we silenced earlier still counts.
     */
    static boolean eligible(NotificationChannel c) {
        if (c.getImportance() < 3) return false;
        Uri s = c.getSound();
        if (s == null) return locked(c);
        String a = s.getAuthority();
        return "settings".equals(a) || "media".equals(a);
    }

    static String str(Uri u) { return u == null ? "none" : u.toString(); }

    static void apply(String pkg, int uid, NotificationChannel c, String uri) throws Exception {
        Uri u = uri.equals("none") ? null : Uri.parse(uri.equals("default") ? DEFAULT : uri);
        c.setSound(u, c.getAudioAttributes());
        update.invoke(nm, pkg, uid, c);
    }

    static String handle(String cmd) {
        try {
            init();
            String[] a = cmd.split("\t");
            StringBuilder out = new StringBuilder();
            switch (a.length > 1 ? a[1] : "") {
                case "list": {
                    for (NotificationChannel c : of(a[2], uid(a[2]))) {
                        out.append(c.getId()).append('\t').append(c.getImportance()).append('\t').append(str(c.getSound()))
                           .append('\t').append(locked(c) ? 1 : 0).append('\t').append(eligible(c) ? 1 : 0)
                           .append('\t').append(String.valueOf(c.getName()).replace('\t', ' ').replace('\n', ' ')).append('\n');
                    }
                    break;
                }
                case "sum": {
                    for (int i = 2; i < a.length; i++) {
                        String v = "-";
                        try {
                            for (NotificationChannel c : of(a[i], uid(a[i]))) {
                                if (!eligible(c)) continue;
                                String s = str(c.getSound());
                                v = v.equals("-") ? s : v.equals(s) ? v : "*";
                            }
                        } catch (Exception ignored) {}
                        out.append(a[i]).append('\t').append(v).append('\n');
                    }
                    break;
                }
                case "set": {
                    String pkg = a[2], id = a[3], uri = a[4].equals(DEFAULT) ? "default" : a[4];
                    int uid = uid(pkg);
                    for (NotificationChannel c : of(pkg, uid)) {
                        if (id.equals("*") ? eligible(c) : c.getId().equals(id)) apply(pkg, uid, c, uri);
                    }
                    if (id.equals("*")) {
                        Map<String, String> r = rules();
                        if (uri.equals("default")) r.remove(pkg); else r.put(pkg, uri);
                        save(r);
                    }
                    out.append("ok\n");
                    break;
                }
                default: out.append("?\n");
            }
            return out.toString();
        } catch (Exception e) {
            return "error " + e + "\n";
        }
    }

    // ---- remembered app-wide choices ----

    static synchronized Map<String, String> rules() {
        Map<String, String> r = new LinkedHashMap<>();
        try {
            for (String l : new String(Files.readAllBytes(RULES.toPath()), StandardCharsets.UTF_8).split("\n")) {
                String[] kv = l.split("\t");
                if (kv.length == 2) r.put(kv[0], kv[1]);
            }
        } catch (Exception ignored) {}
        return r;
    }

    static synchronized void save(Map<String, String> r) throws Exception {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : r.entrySet()) b.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        File tmp = new File(RULES.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(b.toString().getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(RULES)) throw new Exception("rename");
    }

    /**
     * Apps add channels whenever they like (WeChat mints a new call channel per install), and a new
     * channel starts on the default sound. Every few minutes the remembered choice is copied onto
     * eligible channels nobody has set yet; a channel the user set on its own is locked and skipped.
     */
    static void keepApplied() {
        new Thread(() -> {
            while (true) {
                try {
                    init();
                    for (Map.Entry<String, String> e : rules().entrySet()) {
                        try {
                            int uid = uid(e.getKey());
                            for (NotificationChannel c : of(e.getKey(), uid)) {
                                if (eligible(c) && !locked(c) && !str(c.getSound()).equals(e.getValue())) apply(e.getKey(), uid, c, e.getValue());
                            }
                        } catch (Exception ignored) {}   // uninstalled since: the rule just sleeps
                    }
                    Thread.sleep(5 * 60 * 1000);
                } catch (Exception e) {
                    try { Thread.sleep(60 * 1000); } catch (InterruptedException ignored) {}
                }
            }
        }, "sounds").start();
    }
}
