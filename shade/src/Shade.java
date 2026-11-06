import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.os.IBinder;
import android.util.TypedValue;
import java.lang.reflect.Method;

/**
 * iOS-style shade for ZUI SystemUI as a fabricated overlay: no system files touched.
 * `cmd overlay fabricate` refuses dimensions, so this calls the same service from root.
 * Run: su -c 'CLASSPATH=shade.dex app_process / Shade [off]'
 */
public class Shade {
    static final String TARGET = "com.android.systemui";

    static void dimen(FabricatedOverlay o, String target, String name, float v, int unit, String config) throws Exception {
        FabricatedOverlay.class.getMethod("setResourceValue", String.class, float.class, int.class, String.class)
                .invoke(o, target + ":dimen/" + name, v, unit, config);
    }

    static void dp(FabricatedOverlay o, String name, float v, String config) throws Exception {
        dimen(o, TARGET, name, v, TypedValue.COMPLEX_UNIT_DIP, config);
    }

    static void color(FabricatedOverlay o, String name, int argb, String config) throws Exception {
        FabricatedOverlay.class.getMethod("setResourceValue", String.class, int.class, int.class, String.class)
                .invoke(o, TARGET + ":color/" + name, TypedValue.TYPE_INT_COLOR_ARGB8, argb, config);
    }

    static FabricatedOverlay overlay(String name, String target) throws Exception {
        Class<?> ob = Class.forName("android.content.om.FabricatedOverlay$Builder");
        Object b = ob.getConstructor(String.class, String.class, String.class)
                .newInstance("com.android.shell", name, target);
        return (FabricatedOverlay) ob.getMethod("build").invoke(b);
    }

    public static void main(String[] args) throws Exception {
        FabricatedOverlay o = overlay("TesseraShade", TARGET);
        FabricatedOverlay n = overlay("TesseraNotif", "android");

        Class<?> tb = Class.forName("android.content.om.OverlayManagerTransaction$Builder");
        Object t = tb.getConstructor().newInstance();
        if (args.length > 0 && args[0].equals("off")) {
            for (FabricatedOverlay x : new FabricatedOverlay[] {o, n})
                tb.getMethod("unregisterFabricatedOverlay", OverlayIdentifier.class).invoke(t, x.getIdentifier());
        } else {
            // ZUI caps both columns near 382dp, half of this tablet's portrait width.
            dp(o, "qs_frame_constraint_width_max", 640, "sw600dp");
            dp(o, "notification_stack_scroller_constraint_width_max", 640, "sw600dp");
            dp(o, "notification_corner_radius", 24, "");
            dp(o, "notification_scrim_corner_radius", 24, "");
            dp(o, "notification_divider_height", 8, "");
            // Frosted cards: SystemUI's own blur shows through instead of a flat fill.
            for (String c : new String[] {"notification_material_background_color", "notification_background_color"}) {
                color(o, c, 0xB8F4F4F6, "");
                color(o, c, 0xA0303034, "night");
            }

            // Card layout lives in the framework: a large icon on the left, text beside it.
            int DP = TypedValue.COMPLEX_UNIT_DIP, SP = TypedValue.COMPLEX_UNIT_SP;
            dimen(n, "android", "notification_icon_circle_size", 40, DP, "");
            dimen(n, "android", "notification_icon_circle_padding", 6, DP, "");
            dimen(n, "android", "notification_icon_circle_start", 14, DP, "");
            dimen(n, "android", "notification_content_margin_start", 68, DP, "");
            dimen(n, "android", "notification_title_text_size", 16, SP, "");
            dimen(n, "android", "notification_text_size", 15, SP, "");
            dimen(n, "android", "notification_subtext_size", 13, SP, "");

            for (FabricatedOverlay x : new FabricatedOverlay[] {o, n}) {
                tb.getMethod("registerFabricatedOverlay", FabricatedOverlay.class).invoke(t, x);
                tb.getMethod("setEnabled", OverlayIdentifier.class, boolean.class, int.class).invoke(t, x.getIdentifier(), true, 0);
            }
        }
        Object tx = tb.getMethod("build").invoke(t);

        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "overlay");
        Object om = Class.forName("android.content.om.IOverlayManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Method commit = Class.forName("android.content.om.IOverlayManager")
                .getMethod("commit", Class.forName("android.content.om.OverlayManagerTransaction"));
        commit.invoke(om, tx);
        System.out.println("ok");
    }
}
