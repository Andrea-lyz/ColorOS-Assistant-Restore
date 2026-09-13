package io.github.andrealtb.assistrestore;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * SystemUI side restoration.
 *
 * <p>Verified against ColorOS 16 (SystemUI 16.99.12) and the OPlus framework jars pulled from the
 * device. Each hook repairs one diversion point instead of re-implementing the assistant stack:</p>
 *
 * <ol>
 *   <li>{@code AssistManager.startAssist} wraps its entire dispatch in
 *   {@code if (FeatureOption.isExpRegion() && ...)}, so a China build logs telemetry and returns
 *   without ever reaching {@code startAssistInternal}. The hook runs the original method and, when
 *   the region gate rejected the request, performs the same dispatch the OEM performs on an export
 *   build.</li>
 *   <li>{@code NavBarUtils.isAssistantAvailable} begins with {@code !FeatureOption.isExpRegion()},
 *   so {@code LauncherProxyService} always reports "assistant unavailable" and the launcher never
 *   enables the bottom-corner swipe region. The hook answers with AOSP semantics.</li>
 *   <li>{@code SpeedChassistMainBusiness.onLongPressed} starts the OEM assistant service directly,
 *   without touching the assist stack. The hook routes the gesture-handle long press into the
 *   assist stack.</li>
 * </ol>
 */
final class SystemUiHooks {
    private static final String ASSIST_MANAGER = "com.android.systemui.assist.AssistManager";
    private static final String NAV_BAR_UTILS = "com.oplus.systemui.navigationbar.utils.NavBarUtils";
    private static final String FEATURE_OPTION = "com.oplusos.systemui.common.feature.FeatureOption";
    private static final String CUSTOMIZE_FEATURE_OPTION =
            "com.oplusos.systemui.common.feature.CustomizeFeatureOption";
    private static final String SPEED_CHASSIST =
            "com.oplus.systemui.navigationbar.gesture.otherbusiness.SpeedChassistMainBusiness";
    /**
     * The gesture-handle long press on this build is handled by the OCR-screen business, not by
     * {@code SpeedChassistMainBusiness}: the nav bar handle dispatches
     * {@code GestureHomeHandleEventController.onLongClick()} to its listeners and the registered
     * listener is {@code OplusOcrScreenBusiness}, which forwards to
     * {@code OplusOcrScreenServiceHandler}, whose {@code onPreLongPress()} binds and preloads the OEM
     * screen-recognition service and whose {@code handleLongPressAction()} finally starts it.
     * Confirmed from a device log where every bottom-centre long press produced
     * {@code OcrScreenService-->getServiceIntent} and no {@code SpeedChassist} activity.
     */
    private static final String OCR_SCREEN_HANDLER =
            "com.oplus.systemui.navigationbar.ocrscreen.OplusOcrScreenServiceHandler";
    private static final String QUICK_STEP_CONTRACT =
            "com.android.systemui.shared.system.QuickStepContract";
    private static final String ASSIST_UTILS = "com.android.internal.app.AssistUtils";
    private static final String DEPENDENCY = "com.android.systemui.Dependency";
    private static final String INTERNAL_BOOL_RES = "com.android.internal.R$bool";
    private static final String CTS_INTERFACE = "android.app.contextualsearch.IContextualSearchManager";

    private static final String ASSIST_TOUCH_GESTURE_ENABLED = "assist_touch_gesture_enabled";
    private static final String EXTRA_INVOCATION_TYPE = "invocation_type";
    private static final String SETTING_ASSISTANT = "assistant";

    /**
     * The China-only nav-bar switch "长按手势指示条唤醒小布识屏" ({@code gesture_side_wake_cui} in
     * Settings) writes one of these two keys, depending on whether the build supports screen
     * recognition. SystemUI itself never reads them, so without honouring them here the switch would
     * stop having any visible effect once the module routes the gesture to the system assistant.
     */
    private static final String KEY_HANDLE_WAKE_OCR = "oplus_home_handle_wake_up_ocr_enable";
    private static final String KEY_HANDLE_WAKE_CUI = "oplus_gesture_handle_cui_enable";

    /** Mirrors the OEM and AOSP numbering for a long press on the gesture handle. */
    private static final int INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS = 5;
    /** {@code launchAssistAction} invocation type produced by the power key. */
    private static final int INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6;

    /**
     * The OCR handler can reach {@code handleLongPressAction()} from two places (the long-press
     * runnable and the late service connection), so keep the dispatch single-shot per gesture.
     */
    private static volatile long lastHandleDispatchAtMs;
    private static final long HANDLE_DISPATCH_DEBOUNCE_MS = 800L;

    private SystemUiHooks() {
    }

    static void install(AssistRestoreModule module, ClassLoader classLoader) {
        AssistPipeline pipeline = resolveAssistPipeline(module, classLoader);
        CtsPipeline cts = resolveCtsPipeline(module, classLoader);
        installAssistDispatch(module, classLoader, pipeline, cts);
        installAssistantAvailability(module, classLoader);
        installGestureHandleLongPress(module, classLoader, pipeline, cts);
        installOcrScreenHandleLongPress(module, classLoader, pipeline, cts);
    }

    /**
     * Circle to Search is addressed through the framework service rather than an assist
     * invocation: on this build the SystemUI side of that integration is stubbed out
     * ({@code OplusCircleToSearchManagerEx.interceptStartAssistInternal} always returns false), so
     * the service is the only working entry point.
     */
    private static final class CtsPipeline {
        private final Method getService;
        private final Method asInterface;
        private final Method startContextualSearch;

        private CtsPipeline(Method getService, Method asInterface, Method startContextualSearch) {
            this.getService = getService;
            this.asInterface = asInterface;
            this.startContextualSearch = startContextualSearch;
        }

        /** @return {@code true} when the service accepted the request */
        boolean trigger() throws Throwable {
            Object binder = getService.invoke(null, "contextual_search");
            if (binder == null) {
                return false;
            }
            Object service = asInterface.invoke(null, binder);
            if (service == null) {
                return false;
            }
            startContextualSearch.invoke(service, CtsHooks.CONTEXTUAL_SEARCH_ENTRYPOINT);
            return true;
        }
    }

    private static CtsPipeline resolveCtsPipeline(
            AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Method getService = Class.forName("android.os.ServiceManager", false, classLoader)
                    .getMethod("getService", String.class);
            Class<?> iface = Class.forName(CTS_INTERFACE, false, classLoader);
            Class<?> stub = Class.forName(CTS_INTERFACE + "$Stub", false, classLoader);
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            Method startContextualSearch = iface.getMethod("startContextualSearch", int.class);
            return new CtsPipeline(getService, asInterface, startContextualSearch);
        } catch (Throwable t) {
            module.logError("circle_to_search_pipeline_failed", t);
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Assist stack access
    // ---------------------------------------------------------------------------------------------

    /**
     * Holds the three {@code AssistManager} members that perform the real dispatch. The gesture
     * handle hook calls them directly so the result never depends on whether a reflection based
     * invocation of an already hooked method passes through the hook chain.
     */
    private static final class AssistPipeline {
        private final Method getAssistInfo;
        private final Method getVoiceInteractorComponentName;
        private final Method startAssistInternal;

        private AssistPipeline(Method getAssistInfo, Method getVoiceInteractorComponentName,
                Method startAssistInternal) {
            this.getAssistInfo = getAssistInfo;
            this.getVoiceInteractorComponentName = getVoiceInteractorComponentName;
            this.startAssistInternal = startAssistInternal;
        }

        /** @return the component the request was sent to, or {@code null} when none is configured */
        Object dispatch(AssistRestoreModule module, Object assistManager, Bundle args)
                throws Throwable {
            Object assistInfo = getAssistInfo.invoke(assistManager);
            if (assistInfo == null) {
                module.logWarn("assist_dispatch_skipped reason=no_assistant_configured");
                return null;
            }
            boolean isService =
                    assistInfo.equals(getVoiceInteractorComponentName.invoke(assistManager));
            module.logInfo("assist_dispatch component=" + assistInfo
                    + " isService=" + isService
                    + " invocationType=" + args.getInt(EXTRA_INVOCATION_TYPE, 0));
            startAssistInternal.invoke(assistManager, args, assistInfo, isService);
            return assistInfo;
        }

        /**
         * Runs the very same dispatch for an explicitly chosen component: this is how a pinned app
         * that ships a voice interaction service gets a real assist session even while the system
         * default assistant stays untouched.
         */
        Object dispatchTo(AssistRestoreModule module, Object assistManager, Bundle args,
                ComponentName component, boolean isService) throws Throwable {
            module.logInfo("assist_dispatch component=" + component
                    + " isService=" + isService
                    + " invocationType=" + args.getInt(EXTRA_INVOCATION_TYPE, 0)
                    + " pinned=true");
            startAssistInternal.invoke(assistManager, args, component, isService);
            return component;
        }
    }

    private static AssistPipeline resolveAssistPipeline(
            AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> assistManager = Class.forName(ASSIST_MANAGER, true, classLoader);
            return new AssistPipeline(
                    assistManager.getMethod("getAssistInfo"),
                    assistManager.getMethod("getVoiceInteractorComponentName"),
                    assistManager.getMethod("startAssistInternal",
                            Bundle.class, ComponentName.class, boolean.class));
        } catch (Throwable t) {
            module.logError("assist_pipeline_resolve_failed", t);
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 1. AssistManager.startAssist
    // ---------------------------------------------------------------------------------------------

    private static void installAssistDispatch(
            AssistRestoreModule module, ClassLoader classLoader, AssistPipeline pipeline,
            CtsPipeline cts) {
        if (pipeline == null) {
            module.logError("hook_skipped target=" + ASSIST_MANAGER + ".startAssist"
                    + " reason=assist_pipeline_unavailable");
            return;
        }
        try {
            Class<?> assistManager = Class.forName(ASSIST_MANAGER, true, classLoader);
            Method startAssist = assistManager.getMethod("startAssist", Bundle.class);
            Field overrideInvocationTypes = assistManager.getField("mAssistOverrideInvocationTypes");
            Field activityManager = assistManager.getField("mActivityManager");
            Method isExpRegion = Refl.staticMethod(classLoader, FEATURE_OPTION, "isExpRegion");
            if (isExpRegion == null) {
                module.logWarn("region_gate_unresolved target=" + FEATURE_OPTION
                        + ".isExpRegion -> treating the region gate as closed");
            }

            module.hook(startAssist)
                    .setId("assist_manager_start_assist")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object manager = chain.getThisObject();
                        Bundle bundle = (Bundle) chain.getArg(0);
                        Object result = chain.proceed();

                        if (!AssistConfig.isEnabled(HookPrefs.get())) {
                            module.logInfo("assist_skip reason=module_disabled");
                            return result;
                        }
                        if (isExpRegionEnabled(isExpRegion)) {
                            // Export-region behaviour is already active: the OEM path dispatched.
                            module.logInfo("assist_skip reason=exp_region_active");
                            return result;
                        }
                        if (isLockTaskMode(activityManager.get(manager))) {
                            module.logInfo("assist_skip reason=lock_task_mode");
                            return result;
                        }
                        if (isHandledByLauncherOverride(overrideInvocationTypes.get(manager), bundle)) {
                            module.logInfo("assist_skip reason=launcher_override invocationType="
                                    + (bundle != null ? bundle.getInt(EXTRA_INVOCATION_TYPE, 0) : 0));
                            return result;
                        }
                        String entry = entryForInvocationType(bundle);
                        String mode = AssistConfig.mode(HookPrefs.get(), entry);
                        if (AssistConfig.MODE_NONE.equals(mode)) {
                            module.logInfo("assist_skip reason=disabled entry=" + entry);
                            return result;
                        }
                        if (AssistConfig.MODE_CTS.equals(mode)) {
                            if (triggerCircleToSearch(module, cts)) {
                                return result;
                            }
                            module.logWarn("circle_to_search_unavailable entry=" + entry);
                        } else if (AssistConfig.MODE_APP.equals(mode)
                                || AssistConfig.MODE_CUSTOM.equals(mode)) {
                            if (startConfiguredTarget(module, TargetIntents.appContext(), entry,
                                    pipeline, manager)) {
                                return result;
                            }
                        }
                        pipeline.dispatch(module, manager, bundle != null ? bundle : new Bundle());
                        return result;
                    });
            module.logInfo("hook_installed target=" + ASSIST_MANAGER + ".startAssist");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + ASSIST_MANAGER + ".startAssist", t);
        }
    }

    private static boolean isExpRegionEnabled(Method isExpRegion) {
        if (isExpRegion == null) {
            return false;
        }
        try {
            Object value = isExpRegion.invoke(null);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isLockTaskMode(Object activityManager) {
        if (activityManager == null) {
            return false;
        }
        try {
            Method getState = activityManager.getClass().getMethod("getLockTaskModeState");
            Object state = getState.invoke(activityManager);
            return state instanceof Integer && (Integer) state == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isHandledByLauncherOverride(Object invocationTypes, Bundle bundle) {
        if (!(invocationTypes instanceof int[]) || bundle == null
                || !bundle.containsKey(EXTRA_INVOCATION_TYPE)) {
            return false;
        }
        int requested = bundle.getInt(EXTRA_INVOCATION_TYPE, 0);
        for (int candidate : (int[]) invocationTypes) {
            if (candidate == requested) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // 2. NavBarUtils.isAssistantAvailable
    // ---------------------------------------------------------------------------------------------

    private static void installAssistantAvailability(
            AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> navBarUtils = Class.forName(NAV_BAR_UTILS, true, classLoader);
            Method isAssistantAvailable =
                    navBarUtils.getMethod("isAssistantAvailable", Context.class, int.class, int.class);

            Method quickStepIsGesturalMode =
                    Refl.staticMethod(classLoader, QUICK_STEP_CONTRACT, "isGesturalMode", int.class);
            Class<?> assistUtilsClass = Class.forName(ASSIST_UTILS, false, classLoader);
            Constructor<?> assistUtilsConstructor = assistUtilsClass.getConstructor(Context.class);
            Method getAssistComponentForUser =
                    assistUtilsClass.getMethod("getAssistComponentForUser", int.class);

            module.hook(isAssistantAvailable)
                    .setId("assistant_availability")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!AssistConfig.isEnabled(HookPrefs.get())) {
                            return chain.proceed();
                        }
                        if (AssistConfig.MODE_NONE.equals(AssistConfig.mode(
                                HookPrefs.get(), AssistConfig.ENTRY_CORNER))) {
                            // Corner entry disabled: report it unavailable so the launcher does not
                            // even arm the corner region.
                            module.logInfo("assistant_availability available=false reason=disabled");
                            return Boolean.FALSE;
                        }
                        Context context = (Context) chain.getArg(0);
                        int navBarMode = (Integer) chain.getArg(1);
                        int userId = (Integer) chain.getArg(2);
                        Boolean available = evaluateAssistantAvailable(module, classLoader, context,
                                navBarMode, userId, quickStepIsGesturalMode, assistUtilsConstructor,
                                getAssistComponentForUser);
                        if (available == null) {
                            return chain.proceed();
                        }
                        return available;
                    });
            module.logInfo("hook_installed target=" + NAV_BAR_UTILS + ".isAssistantAvailable");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + NAV_BAR_UTILS + ".isAssistantAvailable", t);
        }
    }

    /**
     * Reimplements the AOSP availability rule without the region gate.
     *
     * @return the availability to report, or {@code null} to fall back to the original method
     */
    private static Boolean evaluateAssistantAvailable(
            AssistRestoreModule module,
            ClassLoader classLoader,
            Context context,
            int navBarMode,
            int userId,
            Method quickStepIsGesturalMode,
            Constructor<?> assistUtilsConstructor,
            Method getAssistComponentForUser) {
        try {
            Boolean circleToSearch = Refl.staticBooleanField(
                    classLoader, CUSTOMIZE_FEATURE_OPTION, "sIsSupportCircleToSearch");
            if (Boolean.TRUE.equals(circleToSearch)) {
                // Circle to Search owns the corner gesture on builds that ship it.
                return Boolean.FALSE;
            }
            if (quickStepIsGesturalMode != null
                    && !Boolean.TRUE.equals(quickStepIsGesturalMode.invoke(null, navBarMode))) {
                return Boolean.FALSE;
            }
            Object assistUtils = assistUtilsConstructor.newInstance(context);
            Object component = getAssistComponentForUser.invoke(assistUtils, userId);
            if (component == null) {
                module.logInfo("assistant_availability available=false"
                        + " reason=no_assistant_configured");
                return Boolean.FALSE;
            }
            int stored = getSecureSettingForUser(
                    context,
                    ASSIST_TOUCH_GESTURE_ENABLED,
                    assistTouchGestureDefaultValue(context),
                    userId);
            boolean enabled = stored != 0;
            module.logInfo("assistant_availability available=" + enabled
                    + " component=" + component
                    + " navBarMode=" + navBarMode
                    + " userId=" + userId);
            return enabled;
        } catch (Throwable t) {
            module.logError("assistant_availability_failed", t);
            return null;
        }
    }

    /** Reads the framework default that the OEM method itself would fall back to. */
    private static int assistTouchGestureDefaultValue(Context context) {
        try {
            Class<?> boolRes = Class.forName(INTERNAL_BOOL_RES);
            int resourceId =
                    boolRes.getField("config_assistTouchGestureEnabledDefault").getInt(null);
            if (resourceId != 0) {
                return context.getResources().getBoolean(resourceId) ? 1 : 0;
            }
        } catch (Throwable ignored) {
            // Falls through to the AOSP default.
        }
        return 1;
    }

    /**
     * {@code Settings.Secure.getIntForUser} is a hidden API. SystemUI is a platform process, but this
     * module compiles against the public SDK, so the hidden overload is reached reflectively with
     * the documented calling-user overload as a fallback.
     */
    private static int getSecureSettingForUser(
            Context context, String name, int defaultValue, int userId) {
        ContentResolver resolver = context.getContentResolver();
        try {
            Method getIntForUser = Settings.Secure.class.getMethod(
                    "getIntForUser", ContentResolver.class, String.class, int.class, int.class);
            Object value = getIntForUser.invoke(null, resolver, name, defaultValue, userId);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Throwable ignored) {
            // Falls through to the public API.
        }
        return Settings.Secure.getInt(resolver, name, defaultValue);
    }

    // ---------------------------------------------------------------------------------------------
    // 3. SpeedChassistMainBusiness.onLongPressed
    // ---------------------------------------------------------------------------------------------

    private static void installGestureHandleLongPress(
            AssistRestoreModule module, ClassLoader classLoader, AssistPipeline pipeline,
            CtsPipeline cts) {
        if (pipeline == null) {
            module.logError("hook_skipped target=" + SPEED_CHASSIST + ".onLongPressed"
                    + " reason=assist_pipeline_unavailable");
            return;
        }
        try {
            Class<?> business = Class.forName(SPEED_CHASSIST, true, classLoader);
            Method onLongPressed = business.getMethod("onLongPressed");
            Class<?> assistManagerClass = Class.forName(ASSIST_MANAGER, true, classLoader);
            Field contextField = business.getDeclaredField("mContext");
            contextField.setAccessible(true);

            module.hook(onLongPressed)
                    .setId("gesture_handle_long_press")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Context context = (Context) contextField.get(chain.getThisObject());
                        return dispatchGestureHandleLongPress(
                                module, classLoader, pipeline, assistManagerClass, cts, context)
                                ? null : chain.proceed();
                    });
            module.logInfo("hook_installed target=" + SPEED_CHASSIST + ".onLongPressed");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + SPEED_CHASSIST + ".onLongPressed", t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. OplusOcrScreenServiceHandler.handleLongPressAction
    // ---------------------------------------------------------------------------------------------

    /**
     * The gesture-handle long press that actually runs on the tested build. Replacing the action
     * keeps the OEM haptics and first-run dialog logic intact while sending the gesture to the
     * configured assistant instead of the screen-recognition service.
     */
    private static void installOcrScreenHandleLongPress(
            AssistRestoreModule module, ClassLoader classLoader, AssistPipeline pipeline,
            CtsPipeline cts) {
        if (pipeline == null) {
            module.logError("hook_skipped target=" + OCR_SCREEN_HANDLER + ".onLongPressed"
                    + " reason=assist_pipeline_unavailable");
            return;
        }
        try {
            Class<?> handler = Class.forName(OCR_SCREEN_HANDLER, true, classLoader);
            Class<?> assistManagerClass = Class.forName(ASSIST_MANAGER, true, classLoader);
            Field contextField = handler.getDeclaredField("context");
            contextField.setAccessible(true);

            // The OEM long press runs: onShowPress (new handler + haptics) -> onPreLongPress (bind and
            // preload the screen-recognition service) -> onLongPressed (haptics, set the handled flag,
            // then post a task) -> handleLongPressAction.
            //
            // That posted task calls handleLongPressAction() ONLY when
            // getEntranceServiceInterface() != null, i.e. only when onPreLongPress already bound the
            // service. Skipping the preload therefore also cuts off handleLongPressAction(), which is
            // why the dispatch hangs off onLongPressed() instead: proceed() keeps the OEM haptics and
            // flags, and the posted task then finds no connected service and does nothing.
            Method onPreLongPress = handler.getMethod("onPreLongPress");
            module.hook(onPreLongPress)
                    .setId("ocr_screen_handle_preload")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!AssistConfig.isEnabled(HookPrefs.get())
                                || !AssistConfig.skipOcrPreload(HookPrefs.get())
                                || isHandleOemMode()) {
                            return chain.proceed();
                        }
                        module.logInfo("gesture_handle_ocr_preload_skipped");
                        return null;
                    });
            module.logInfo("hook_installed target=" + OCR_SCREEN_HANDLER + ".onPreLongPress");

            Method onLongPressed = handler.getMethod("onLongPressed");
            module.hook(onLongPressed)
                    .setId("ocr_screen_handle_long_press")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Context context = (Context) contextField.get(chain.getThisObject());
                        Object result = chain.proceed();
                        dispatchGestureHandleLongPress(
                                module, classLoader, pipeline, assistManagerClass, cts, context);
                        return result;
                    });
            module.logInfo("hook_installed target=" + OCR_SCREEN_HANDLER + ".onLongPressed");
            // Skipping the preload only stops *this* press from connecting the screen-recognition
            // service; the service may already be connected from an earlier OEM press, and then the
            // OEM action runs anyway. Neutralise the action itself for every non-OEM mode.
            Method handleLongPressAction = null;
            try {
                handleLongPressAction = handler.getMethod("handleLongPressAction");
            } catch (NoSuchMethodException notPublic) {
                try {
                    handleLongPressAction = handler.getDeclaredMethod("handleLongPressAction");
                    handleLongPressAction.setAccessible(true);
                } catch (Throwable ignored) {
                    // Not present on this build.
                }
            }
            if (handleLongPressAction != null) {
                module.hook(handleLongPressAction)
                        .setId("ocr_screen_handle_action")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (!isHandleOemMode()) {
                                module.logInfo("gesture_handle_ocr_action_skipped");
                                return null;
                            }
                            return chain.proceed();
                        });
                module.logInfo("hook_installed target=" + OCR_SCREEN_HANDLER
                        + ".handleLongPressAction");
            } else {
                module.logWarn("hook_skipped target=" + OCR_SCREEN_HANDLER
                        + ".handleLongPressAction reason=method_missing");
            }
        } catch (Throwable t) {
            module.logError("hook_failed target=" + OCR_SCREEN_HANDLER
                    + ".onLongPressed", t);
        }
    }

    /**
     * Shared gesture-handle handling for both OEM variants.
     *
     * @return {@code true} when the assistant replaced the OEM action; {@code false} when the caller
     *         should let the original implementation run (callers that already invoked
     *         {@code chain.proceed()} simply do nothing in that case)
     */
    private static boolean dispatchGestureHandleLongPress(
            AssistRestoreModule module,
            ClassLoader classLoader,
            AssistPipeline pipeline,
            Class<?> assistManagerClass,
            CtsPipeline cts,
            Context context) throws Throwable {
        long now = SystemClock.uptimeMillis();
        if (now - lastHandleDispatchAtMs < HANDLE_DISPATCH_DEBOUNCE_MS) {
            module.logInfo("gesture_handle_long_press_skipped reason=debounce");
            return true;
        }
        if (isHandleOemMode()) {
            // "小布识屏" is the OEM behaviour itself, so the module stays out of the way entirely:
            // the OEM service preload and the OEM action both run as shipped.
            module.logInfo("gesture_handle_long_press_skipped reason=oem_mode");
            return false;
        }
        if (AssistConfig.MODE_NONE.equals(
                AssistConfig.mode(HookPrefs.get(), AssistConfig.ENTRY_HANDLE))) {
            // Nothing is configured for this entry: the gesture stays silent, including the OEM
            // screen recognition the preload skip already cut off.
            module.logInfo("gesture_handle_long_press_skipped reason=disabled");
            return true;
        }
        if (context != null && isHandleWakeSwitchOff(module, context)) {
            // The user turned the nav-bar switch off; the gesture stays silent, matching what the
            // OEM switch is expected to do.
            return true;
        }
        if (!AssistConfig.isEnabled(HookPrefs.get())) {
            module.logInfo("gesture_handle_long_press_skipped reason=module_disabled");
            return false;
        }
        String configured = AssistConfig.mode(HookPrefs.get(), AssistConfig.ENTRY_HANDLE);
        Object assistManager = lookupAssistManager(module, classLoader, assistManagerClass);
        if (AssistConfig.MODE_CTS.equals(configured)) {
            if (triggerCircleToSearch(module, cts)) {
                lastHandleDispatchAtMs = now;
                return true;
            }
            module.logWarn("circle_to_search_unavailable entry=" + AssistConfig.ENTRY_HANDLE);
        } else if (AssistConfig.MODE_APP.equals(configured)
                || AssistConfig.MODE_CUSTOM.equals(configured)) {
            if (startConfiguredTarget(module, context, AssistConfig.ENTRY_HANDLE, pipeline,
                    assistManager)) {
                lastHandleDispatchAtMs = now;
                return true;
            }
            module.logWarn("gesture_handle_fallback reason=target_unavailable entry="
                    + AssistConfig.ENTRY_HANDLE);
        }
        // AOSP dispatch on this gesture simply goes to whatever the default assistant is; Circle to
        // Search appears because Google's assistant turns that invocation into it. This build,
        // however, routes a Google assistant invocation to the voice UI instead, so Circle to Search
        // is triggered through its own service - but only while the Google app really is the
        // configured assistant, otherwise the gesture must keep following the user's setting.
        if (!AssistConfig.MODE_DEFAULT.equals(configured)) {
            // The user pinned something else and it could not be started; do not silently fall
            // through to the default assistant on the Circle to Search branch.
            module.logInfo("gesture_handle_assistant mode=" + configured);
        } else if (cts != null && isGoogleAssistantConfigured(module, classLoader, context)) {
            try {
                if (cts.trigger()) {
                    module.logInfo("circle_to_search_triggered entrypoint="
                            + CtsHooks.CONTEXTUAL_SEARCH_ENTRYPOINT);
                    lastHandleDispatchAtMs = now;
                    return true;
                }
                module.logWarn("circle_to_search_unavailable reason=no_service");
            } catch (Throwable t) {
                module.logWarn("circle_to_search_failed " + t);
            }
        }
        if (assistManager == null) {
            module.logWarn("gesture_handle_long_press_fallback reason=no_assist_manager");
            return false;
        }
        Bundle args = new Bundle();
        args.putInt(EXTRA_INVOCATION_TYPE, INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS);
        module.logInfo("gesture_handle_long_press invocationType="
                + INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS);
        pipeline.dispatch(module, assistManager, args);
        lastHandleDispatchAtMs = now;
        return true;
    }

    /**
     * @return {@code true} when the configured default assistant is the Google app, i.e. when the
     *         navigation-handle gesture is expected to become Circle to Search
     */
    private static boolean isGoogleAssistantConfigured(
            AssistRestoreModule module, ClassLoader classLoader, Context context) {
        try {
            Class<?> assistUtilsClass = Class.forName(ASSIST_UTILS, false, classLoader);
            Constructor<?> constructor = assistUtilsClass.getConstructor(Context.class);
            Method getAssistComponentForUser =
                    assistUtilsClass.getMethod("getAssistComponentForUser", int.class);
            // -2 is the value the OEM code itself passes when it wants the current user's assistant.
            Object component = getAssistComponentForUser.invoke(constructor.newInstance(context), -2);
            if (component != null) {
                boolean google = component.toString().contains(CtsHooks.PKG_GOOGLE);
                module.logInfo("gesture_handle_assistant component=" + component
                        + " circleToSearch=" + google);
                return google;
            }
        } catch (Throwable t) {
            module.logWarn("gesture_handle_assistant_lookup_failed " + t);
        }
        if (context != null) {
            try {
                String configured = Settings.Secure.getString(
                        context.getContentResolver(), SETTING_ASSISTANT);
                if (configured != null) {
                    boolean google = configured.contains(CtsHooks.PKG_GOOGLE);
                    module.logInfo("gesture_handle_assistant setting=" + configured
                            + " circleToSearch=" + google);
                    return google;
                }
            } catch (Throwable t) {
                module.logWarn("gesture_handle_assistant_setting_failed " + t);
            }
        }
        // Assistant unknown: keep the Circle to Search path, which is what this gesture does on a
        // Google-assistant device, and fall back to the assistant dispatch if the service fails.
        module.logWarn("gesture_handle_assistant_unknown assuming google");
        return true;
    }

    /**
     * Mirrors the Settings rule for {@code gesture_side_wake_cui}: the switch stores its state in the
     * screen-recognition key when the build supports it, otherwise in the CUI key. Only an explicit
     * {@code 0} disables the gesture; an unset key keeps the pre-existing behaviour.
     */
    private static boolean isHandleWakeSwitchOff(AssistRestoreModule module, Context context) {
        // Settings writes both keys with user -2 (all users), so the calling-user lookup is enough.
        ContentResolver resolver = context.getContentResolver();
        int ocr = Settings.Secure.getInt(resolver, KEY_HANDLE_WAKE_OCR, -1);
        int cui = Settings.Secure.getInt(resolver, KEY_HANDLE_WAKE_CUI, -1);
        boolean anyOn = ocr == 1 || cui == 1;
        boolean anyOff = ocr == 0 || cui == 0;
        boolean off = !anyOn && anyOff;
        if (off) {
            module.logInfo("gesture_handle_long_press_skipped reason=nav_bar_switch_off"
                    + " ocr=" + ocr + " cui=" + cui);
        }
        return off;
    }

    /** {@code true} when the gesture handle is left to ColorOS' own screen recognition. */
    private static boolean isHandleOemMode() {
        return AssistConfig.MODE_OEM.equals(
                AssistConfig.mode(HookPrefs.get(), AssistConfig.ENTRY_HANDLE));
    }


    /** Maps the AOSP invocation type back to the entry that produced it. */
    private static String entryForInvocationType(Bundle bundle) {
        int invocationType = bundle == null ? 0 : bundle.getInt(EXTRA_INVOCATION_TYPE, 0);
        if (invocationType == INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS) {
            return AssistConfig.ENTRY_POWER;
        }
        if (invocationType == INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS) {
            return AssistConfig.ENTRY_HANDLE;
        }
        return AssistConfig.ENTRY_CORNER;
    }

    /** Runs the Circle to Search entry point; {@code false} tells the caller to fall back. */
    private static boolean triggerCircleToSearch(AssistRestoreModule module, CtsPipeline cts) {
        if (cts == null) {
            return false;
        }
        try {
            if (cts.trigger()) {
                module.logInfo("circle_to_search_triggered entrypoint="
                        + CtsHooks.CONTEXTUAL_SEARCH_ENTRYPOINT);
                return true;
            }
        } catch (Throwable t) {
            module.logWarn("circle_to_search_failed " + t);
        }
        return false;
    }

    /** The package's voice interaction service, when it declares one. */
    private static ComponentName resolveVoiceInteractionComponent(
            Context context, String packageName) {
        try {
            Intent probe = new Intent("android.service.voice.VoiceInteractionService")
                    .setPackage(packageName);
            List<ResolveInfo> matches =
                    context.getPackageManager().queryIntentServices(probe, 0);
            if (matches == null || matches.isEmpty()) {
                return null;
            }
            ServiceInfo info = matches.get(0).serviceInfo;
            return info == null ? null : new ComponentName(info.packageName, info.name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The AOSP invocation type an entry produces, reused for pinned assist sessions. */
    private static int invocationTypeForEntry(String entry) {
        if (AssistConfig.ENTRY_POWER.equals(entry)) {
            return INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS;
        }
        if (AssistConfig.ENTRY_HANDLE.equals(entry)) {
            return INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS;
        }
        return 1;
    }

    /** Starts the app pinned to {@code entry}; {@code false} tells the caller to fall back. */
    private static boolean startConfiguredTarget(
            AssistRestoreModule module, Context context, String entry, AssistPipeline pipeline,
            Object assistManager) {
        String packageName = AssistConfig.targetPackage(HookPrefs.get(), entry);
        if (context == null || packageName.isEmpty()) {
            module.logWarn("target_start_skipped entry=" + entry
                    + " reason=" + (context == null ? "no_context" : "no_package"));
            return false;
        }
        // Pinned voice interaction services are intentionally NOT dispatched through the assist
        // stack: startAssistInternal ignores the component for the service branch and opens the
        // session on whichever assistant currently holds the role, i.e. it would wake the wrong
        // app. Such a target therefore falls through to its own assist activity below.
        Intent intent = TargetIntents.build(context, HookPrefs.get(), entry);
        if (intent == null) {
            module.logWarn("target_start_skipped entry=" + entry + " reason=no_intent package="
                    + packageName);
            return false;
        }
        try {
            context.startActivity(intent);
            module.logInfo("target_started entry=" + entry
                    + " method=" + AssistConfig.targetMethod(HookPrefs.get(), entry)
                    + " component=" + intent.getComponent()
                    + " action=" + intent.getAction()
                    + " package=" + packageName);
            return true;
        } catch (Throwable t) {
            module.logWarn("target_start_failed entry=" + entry + " " + t);
            return false;
        }
    }

    private static Object lookupAssistManager(
            AssistRestoreModule module, ClassLoader classLoader, Class<?> assistManagerClass) {
        try {
            Class<?> dependency = Class.forName(DEPENDENCY, false, classLoader);
            Object container = dependency.getField("sDependency").get(null);
            if (container == null) {
                return null;
            }
            Method getDependency = dependency.getMethod("getDependencyInner", Object.class);
            return getDependency.invoke(container, assistManagerClass);
        } catch (Throwable t) {
            module.logError("assist_manager_lookup_failed", t);
            return null;
        }
    }
}
