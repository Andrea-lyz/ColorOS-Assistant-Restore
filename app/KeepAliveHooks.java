package io.github.wajahatnaeem056.oplusassistant;

import android.content.SharedPreferences;
import android.os.Binder;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * Keeps chosen apps out of ColorOS' background force-stop, and records who stops them.
 *
 * <p>ColorOS force-stops apps on its own: {@code dumpsys activity exit-info} shows the entry as
 * {@code stop <package> due to o-stop(N)}. A force-stopped package is put in the "stopped" state,
 * and a stopped package receives no FCM push until the user opens it again.</p>
 *
 * <p><b>Status: observe first.</b> Two things are not yet confirmed on ColorOS 17:</p>
 * <ul>
 *   <li>{@code forceStopPackageInternalLocked} runs {@code IActivityManagerServiceExt
 *   .updateStopReasonIfNeeded(reason)} itself, and the text used for the exit-info description is
 *   the result. The {@code o-stop(N)} label may therefore be created <i>inside</i> the method, in
 *   which case the argument seen on entry never starts with {@code o-stop} and the blocking rule
 *   below never fires.</li>
 *   <li>The "stopped" flag is set outside that method (in the public {@code forceStopPackage} and
 *   possibly by OEM code), so blocking the method alone may not clear it.</li>
 * </ul>
 * <p>So every relevant call is logged with its caller stack. The blocking rule stays in place for
 * the case where the label does arrive as an argument; the log settles the rest.</p>
 *
 * <p>User force-stops (Settings, app info) and uninstalls are never blocked.</p>
 */
final class KeepAliveHooks {
    private static final String AMS = "com.android.server.am.ActivityManagerService";
    private static final String TARGET_NAME = "forceStopPackageInternalLocked";
    private static final String PUBLIC_NAME = "forceStopPackage";
    private static final String PM_INTERNAL = "com.android.server.pm.PackageManagerInternalBase";
    private static final String PM_BINDER =
            "com.android.server.pm.PackageManagerService$IPackageManagerImpl";
    private static final String SET_STOPPED = "setPackageStoppedState";

    /** Reason prefix ColorOS shows in exit-info: {@code o-stop(40)}. */
    private static final String OEM_STOP_PREFIX = "o-stop";

    /* Argument positions of forceStopPackageInternalLocked(String packageName, int appId,
     * boolean callerWillRestart, boolean purgeCache, boolean doit, boolean evenPersistent,
     * boolean uninstalling, boolean packageStateStopped, int userId, String reasonString, ...). */
    private static final int ARG_PACKAGE = 0;
    private static final int ARG_UNINSTALLING = 6;
    private static final int ARG_REASON = 9;

    private static final long LOG_INTERVAL_MS = 30_000L;
    private static final int MAX_FRAMES = 14;
    private static final int MAX_LOG_KEYS = 512;

    private static final ConcurrentHashMap<String, Long> LAST_LOGGED = new ConcurrentHashMap<>();

    private KeepAliveHooks() {
    }

    static void install(AssistRestoreModule module, ClassLoader loader) {
        installBlocker(module, loader);
        installPublicObserver(module, loader);
        installStoppedObserver(module, loader, PM_INTERNAL);
        installStoppedObserver(module, loader, PM_BINDER);
    }

    private static void installBlocker(AssistRestoreModule module, ClassLoader loader) {
        try {
            Class<?> ams = Class.forName(AMS, false, loader);
            Method target = findTarget(ams);
            if (target == null) {
                module.logWarn("hook_skipped target=" + AMS + "." + TARGET_NAME
                        + " reason=signature_not_found");
                return;
            }
            target.setAccessible(true);
            module.hook(target)
                    .setId("keep_alive_force_stop")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object packageArg = chain.getArg(ARG_PACKAGE);
                        Object reasonArg = chain.getArg(ARG_REASON);
                        String packageName =
                                packageArg instanceof String ? (String) packageArg : null;
                        String reason = reasonArg instanceof String ? (String) reasonArg : "";
                        boolean uninstalling =
                                Boolean.TRUE.equals(chain.getArg(ARG_UNINSTALLING));
                        boolean oemStop = reason.startsWith(OEM_STOP_PREFIX);
                        boolean tracked = false;
                        boolean protect = false;
                        if (packageName != null) {
                            SharedPreferences prefs = HookPrefs.get();
                            tracked = AssistConfig.keepAliveApps(prefs).contains(packageName);
                            protect = tracked && oemStop && !uninstalling
                                    && AssistConfig.keepAlive(prefs);
                        }
                        if (tracked || oemStop) {
                            report(module, "force_stop_internal", packageName,
                                    "reason=" + reason + " uninstalling=" + uninstalling,
                                    protect ? "blocked" : "allowed");
                        }
                        if (protect) {
                            // Report "nothing was stopped": the original never runs, so the
                            // process survives and the cleanup inside the method is skipped whole.
                            return Boolean.FALSE;
                        }
                        return chain.proceed();
                    });
            module.logInfo("hook_installed target=" + AMS + "." + TARGET_NAME);
        } catch (Throwable error) {
            module.logError("hook_failed target=" + AMS, error);
        }
    }

    /** Log-only: the public entry point, where AOSP itself sets the stopped flag. */
    private static void installPublicObserver(AssistRestoreModule module, ClassLoader loader) {
        try {
            Class<?> ams = Class.forName(AMS, false, loader);
            Method target = null;
            for (Method method : ams.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (PUBLIC_NAME.equals(method.getName()) && types.length == 4
                        && types[0] == String.class && types[1] == int.class
                        && types[2] == int.class && types[3] == String.class) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                module.logWarn("hook_skipped target=" + AMS + "." + PUBLIC_NAME
                        + " reason=signature_not_found");
                return;
            }
            target.setAccessible(true);
            module.hook(target)
                    .setId("keep_alive_observe_public")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object packageArg = chain.getArg(0);
                        if (packageArg instanceof String && isTracked((String) packageArg)) {
                            report(module, "force_stop_public", (String) packageArg,
                                    "user=" + chain.getArg(1) + " flags=" + chain.getArg(2)
                                            + " reason=" + chain.getArg(3), "observed");
                        }
                        return chain.proceed();
                    });
            module.logInfo("hook_installed target=" + AMS + "." + PUBLIC_NAME);
        } catch (Throwable error) {
            module.logError("hook_failed target=" + AMS + "." + PUBLIC_NAME, error);
        }
    }

    /** Log-only: who marks a tracked package as stopped. */
    private static void installStoppedObserver(AssistRestoreModule module, ClassLoader loader,
            String className) {
        try {
            Class<?> cls = Class.forName(className, false, loader);
            boolean installed = false;
            for (Method method : cls.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!SET_STOPPED.equals(method.getName()) || types.length != 3
                        || types[0] != String.class || types[1] != boolean.class
                        || types[2] != int.class) {
                    continue;
                }
                method.setAccessible(true);
                module.hook(method)
                        .setId("keep_alive_observe_stopped")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object packageArg = chain.getArg(0);
                            if (packageArg instanceof String
                                    && Boolean.TRUE.equals(chain.getArg(1))
                                    && isTracked((String) packageArg)) {
                                report(module, "set_stopped", (String) packageArg,
                                        "user=" + chain.getArg(2), "observed");
                            }
                            return chain.proceed();
                        });
                installed = true;
            }
            if (installed) {
                module.logInfo("hook_installed target=" + className + "." + SET_STOPPED);
            } else {
                module.logWarn("hook_skipped target=" + className + "." + SET_STOPPED
                        + " reason=signature_not_found");
            }
        } catch (Throwable error) {
            module.logError("hook_failed target=" + className, error);
        }
    }

    /**
     * Matches on name and the parameter types the hook reads, not on the exact parameter count, so
     * a build that appends another trailing argument still resolves.
     */
    private static Method findTarget(Class<?> ams) {
        for (Method method : ams.getDeclaredMethods()) {
            if (!TARGET_NAME.equals(method.getName())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length > ARG_REASON
                    && types[ARG_PACKAGE] == String.class
                    && types[ARG_UNINSTALLING] == boolean.class
                    && types[ARG_REASON] == String.class
                    && method.getReturnType() == boolean.class) {
                return method;
            }
        }
        return null;
    }

    private static boolean isTracked(String packageName) {
        return AssistConfig.keepAliveApps(HookPrefs.get()).contains(packageName);
    }

    private static void report(AssistRestoreModule module, String op, String packageName,
            String detail, String action) {
        long now = System.currentTimeMillis();
        String key = op + "|" + packageName + "|" + detail;
        Long last = LAST_LOGGED.get(key);
        if (last != null && now - last < LOG_INTERVAL_MS) {
            return;
        }
        if (LAST_LOGGED.size() > MAX_LOG_KEYS) {
            LAST_LOGGED.clear();
        }
        LAST_LOGGED.put(key, now);
        module.logInfo("keep_alive op=" + op + " package=" + packageName + " " + detail
                + " action=" + action + " binder=" + Binder.getCallingUid() + "/"
                + Binder.getCallingPid() + " thread=" + Thread.currentThread().getName()
                + " stack=" + callerStack());
    }

    /** A compact caller chain, without the hooking machinery, so the OEM entry point shows up. */
    private static String callerStack() {
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.startsWith("java.lang.reflect") || cls.startsWith("jdk.internal")
                    || cls.startsWith("io.github.libxposed") || cls.startsWith("org.lsposed")
                    || cls.startsWith("de.robv") || cls.startsWith("LSP")
                    || cls.startsWith("io.github.wajahatnaeem056")) {
                continue;
            }
            if (cls.startsWith("com.android.server.")) {
                cls = cls.substring("com.android.server.".length());
            }
            if (kept > 0) {
                out.append(" < ");
            }
            out.append(cls).append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            if (++kept >= MAX_FRAMES) {
                break;
            }
        }
        return out.toString();
    }
}
