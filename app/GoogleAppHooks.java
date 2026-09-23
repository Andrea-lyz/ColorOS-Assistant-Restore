package io.github.wajahatnaeem056.oplusassistant;

import java.lang.reflect.Field;

/**
 * Makes the Google app believe it runs on a device Google ships Circle to Search for.
 *
 * <p>GSA decides whether Circle to Search is available from the device identity, so on a ColorOS
 * device the feature stays off even when the framework side is fixed. The values below are the
 * Samsung S24 Ultra identity that GSA accepts, applied only inside the Google app process because
 * that is the only process this hook is injected into.</p>
 *
 * <p>The technique follows the approach used by the Oplus-Assistant-Hook project.</p>
 */
final class GoogleAppHooks {
    private static final String MANUFACTURER = "samsung";
    private static final String BRAND = "samsung";
    private static final String MODEL = "SM-S928B";
    private static final String PRODUCT = "e3s";
    private static final String DEVICE = "e3s";

    private GoogleAppHooks() {
    }

    static void install(AssistRestoreModule module, ClassLoader classLoader) {
        if (!AssistConfig.isEnabled(HookPrefs.get())) {
            module.logInfo("google_app_identity_spoof_skipped reason=module_disabled");
            return;
        }
        if (!AssistConfig.spoofGoogleBuild(HookPrefs.get())) {
            module.logInfo("google_app_identity_spoof_skipped reason=switch_off");
            return;
        }
        try {
            Class<?> build = Class.forName("android.os.Build", true, classLoader);
            setStaticField(build, "MANUFACTURER", MANUFACTURER);
            setStaticField(build, "BRAND", BRAND);
            setStaticField(build, "MODEL", MODEL);
            setStaticField(build, "PRODUCT", PRODUCT);
            setStaticField(build, "DEVICE", DEVICE);
            module.logInfo("google_app_identity_spoofed model=" + MODEL
                    + " manufacturer=" + MANUFACTURER);
        } catch (Throwable t) {
            module.logError("google_app_identity_spoof_failed", t);
        }
    }

    private static void setStaticField(Class<?> owner, String name, String value)
            throws Throwable {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
