package io.github.wajahatnaeem056.oplusassistant;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

/**
 * Launcher side of the corner-swipe gesture.
 *
 * <p>The launcher decides whether the bottom-corner swipe may summon the assistant in
 * {@code QuickStepContract.isAssistantGestureDisabled(long systemUiStateFlags)}. On this build the
 * mask is {@code 3083}, i.e. the gesture is disabled while any of these are set:</p>
 *
 * <pre>
 * 1     SYSUI_STATE_SCREEN_PINNING             screen pinning
 * 2     SYSUI_STATE_NAV_BAR_HIDDEN             nav bar hidden
 * 8     SYSUI_STATE_BOUNCER_SHOWING            lock screen credential UI
 * 128   SYSUI_STATE_OVERVIEW_DISABLED          requested by the focused app
 * 1024  SYSUI_STATE_SEARCH_DISABLED            requested by the focused app
 * 2048  SYSUI_STATE_QUICK_SETTINGS_EXPANDED    quick settings expanded
 * </pre>
 *
 * plus "notification shade expanded while not on the lock screen".
 *
 * <p>The two application-requested flags (128 and 1024) are what make individual system pages,
 * such as pages inside Settings, refuse the gesture. Those carry no gesture conflict, so the hook
 * keeps the state-based blocks (pinning, nav bar hidden, lock screen, shade, quick settings) and
 * only clears the page-level ones.</p>
 */
final class LauncherHooks {
    private static final String QUICK_STEP_CONTRACT =
            "com.android.systemui.shared.system.QuickStepContract";

    private static final long STATE_SCREEN_PINNING = 1L;
    private static final long STATE_NAV_BAR_HIDDEN = 2L;
    private static final long STATE_NOTIFICATION_PANEL_EXPANDED = 4L;
    private static final long STATE_BOUNCER_SHOWING = 8L;
    private static final long STATE_STATUS_BAR_KEYGUARD_SHOWING = 64L;
    private static final long STATE_OVERVIEW_DISABLED = 128L;
    private static final long STATE_SEARCH_DISABLED = 1024L;
    private static final long STATE_QUICK_SETTINGS_EXPANDED = 2048L;
    private static final long STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY = 131072L;

    /** States that keep the gesture disabled even after the page-level flags are cleared. */
    private static final long BLOCKING_STATES = STATE_SCREEN_PINNING
            | STATE_NAV_BAR_HIDDEN
            | STATE_BOUNCER_SHOWING
            | STATE_QUICK_SETTINGS_EXPANDED;

    /** Flags the focused application can request; they only suppress the gesture, nothing else. */
    private static final long PAGE_LEVEL_STATES = STATE_OVERVIEW_DISABLED | STATE_SEARCH_DISABLED;

    private LauncherHooks() {
    }

    static void install(AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> contract = Class.forName(QUICK_STEP_CONTRACT, true, classLoader);
            Method isAssistantGestureDisabled =
                    contract.getMethod("isAssistantGestureDisabled", long.class);

            module.hook(isAssistantGestureDisabled)
                    .setId("assistant_gesture_disabled")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!AssistConfig.isEnabled(HookPrefs.get())) {
                            return chain.proceed();
                        }
                        Object result = chain.proceed();
                        if (!Boolean.TRUE.equals(result)) {
                            return result;
                        }
                        if (!AssistConfig.unblockPageFlags(HookPrefs.get())) {
                            return result;
                        }
                        long flags = (Long) chain.getArg(0);
                        long mask = flags;
                        if ((mask & STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY) != 0) {
                            mask &= ~STATE_NAV_BAR_HIDDEN;
                        }
                        boolean stateBlocked = (mask & BLOCKING_STATES) != 0
                                || ((mask & STATE_NOTIFICATION_PANEL_EXPANDED) != 0
                                        && (mask & STATE_STATUS_BAR_KEYGUARD_SHOWING) == 0);
                        if (stateBlocked) {
                            return result;
                        }
                        long pageOnly = mask & PAGE_LEVEL_STATES;
                        if (pageOnly == 0) {
                            // The original decision came from something this hook does not model;
                            // leave it alone instead of silently enabling the gesture.
                            module.logWarn("assist_gesture_keep_disabled flags=0x"
                                    + Long.toHexString(flags));
                            return result;
                        }
                        module.logInfo("assist_gesture_unblocked pageFlags=0x"
                                + Long.toHexString(pageOnly)
                                + " flags=0x" + Long.toHexString(flags));
                        return Boolean.FALSE;
                    });
            module.logInfo("hook_installed target=" + QUICK_STEP_CONTRACT
                    + ".isAssistantGestureDisabled");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + QUICK_STEP_CONTRACT
                    + ".isAssistantGestureDisabled", t);
        }
    }
}
