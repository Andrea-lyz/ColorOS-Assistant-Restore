package io.github.andrea_lyz.assistrestore;

import android.content.SharedPreferences;

/**
 * The configuration contract shared by the settings UI and the hooks.
 *
 * <p>The UI writes these keys through {@code XposedService.getRemotePreferences}; hooked processes
 * read the very same group through {@code XposedInterface.getRemotePreferences}, so both sides
 * always agree without touching files or SELinux labels.</p>
 *
 * <p>Every getter returns the historical behaviour when the key is missing, so a module that has
 * never been configured behaves exactly like the version that had no settings at all.</p>
 */
public final class AssistConfig {
    /** RemotePreferences group name. */
    public static final String PREFS = "assist_restore";

    /* Entry identifiers. */
    public static final String ENTRY_POWER = "power";
    public static final String ENTRY_HANDLE = "handle";
    public static final String ENTRY_CORNER = "corner";

    /* What an entry wakes. */
    public static final String MODE_DEFAULT = "default";
    public static final String MODE_CTS = "cts";
    public static final String MODE_APP = "app";
    /** Explicit component / intent, described by the custom screen. */
    public static final String MODE_CUSTOM = "custom";
    /** Do not take the entry over at all: ColorOS keeps handling it (小布识屏 on the handle). */
    public static final String MODE_OEM = "oem";
    /** Wake nothing for this entry: the module also suppresses the OEM action. */
    public static final String MODE_NONE = "none";

    /* How a pinned target is started. */
    public static final String METHOD_AUTO = "auto";
    public static final String METHOD_ASSIST = "assist";
    public static final String METHOD_INTENT = "intent";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SKIP_OCR_PRELOAD = "skip_ocr_preload";
    public static final String KEY_UNBLOCK_PAGE_FLAGS = "unblock_page_flags";
    public static final String KEY_SPOOF_GOOGLE_BUILD = "spoof_google_build";
    public static final String KEY_HANDLE_WHEN_BAR_HIDDEN = "handle_when_bar_hidden";

    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean DEFAULT_SKIP_OCR_PRELOAD = true;
    public static final boolean DEFAULT_UNBLOCK_PAGE_FLAGS = true;
    public static final boolean DEFAULT_SPOOF_GOOGLE_BUILD = true;
    public static final boolean DEFAULT_HANDLE_WHEN_BAR_HIDDEN = true;

    private AssistConfig() {
    }

    /** Master switch: when it is off every hook falls back to the OEM behaviour. */
    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs == null || prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    public static String mode(SharedPreferences prefs, String entry) {
        return prefs == null ? MODE_DEFAULT : prefs.getString(entry + "_mode", MODE_DEFAULT);
    }

    public static String targetPackage(SharedPreferences prefs, String entry) {
        return prefs == null ? "" : prefs.getString(entry + "_package", "");
    }

    public static String targetComponent(SharedPreferences prefs, String entry) {
        return prefs == null ? "" : prefs.getString(entry + "_component", "");
    }

    public static String targetMethod(SharedPreferences prefs, String entry) {
        return prefs == null ? METHOD_AUTO : prefs.getString(entry + "_method", METHOD_AUTO);
    }

    public static String targetArgs(SharedPreferences prefs, String entry) {
        return prefs == null ? "" : prefs.getString(entry + "_args", "");
    }

    public static boolean skipOcrPreload(SharedPreferences prefs) {
        return prefs == null
                || prefs.getBoolean(KEY_SKIP_OCR_PRELOAD, DEFAULT_SKIP_OCR_PRELOAD);
    }

    public static boolean unblockPageFlags(SharedPreferences prefs) {
        return prefs == null
                || prefs.getBoolean(KEY_UNBLOCK_PAGE_FLAGS, DEFAULT_UNBLOCK_PAGE_FLAGS);
    }

    public static boolean spoofGoogleBuild(SharedPreferences prefs) {
        return prefs == null
                || prefs.getBoolean(KEY_SPOOF_GOOGLE_BUILD, DEFAULT_SPOOF_GOOGLE_BUILD);
    }

    /**
     * Whether the gesture-handle long press survives hiding the gesture bar. ColorOS stops feeding
     * the handle once the bar is hidden; with this on the handle keeps its touches and the entry
     * wakes whatever it is configured to wake.
     */
    public static boolean handleWhenBarHidden(SharedPreferences prefs) {
        return prefs == null
                || prefs.getBoolean(KEY_HANDLE_WHEN_BAR_HIDDEN, DEFAULT_HANDLE_WHEN_BAR_HIDDEN);
    }
}
