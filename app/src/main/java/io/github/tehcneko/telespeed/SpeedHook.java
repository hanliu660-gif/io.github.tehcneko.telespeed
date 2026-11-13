package io.github.tehcneko.telespeed;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public class SpeedHook extends XposedModule {
    private static final String TAG = "SpeedHook";
    private static final String KEY_SPEED = "speed";
    public static final String BOOST_NONE = "none";
    public static final String BOOST_AVERAGE = "average";
    public static final String BOOST_EXTREME = "extreme";
    private static final int TYPE_ERROR_SUBTITLE = 4;
    private static final long DEFAULT_MAX_FILE_SIZE = 1024L * 1024L * 2000L;
    private static final long SHOW_INTERVAL_MS = 1000L * 60L * 5L;
    private static final int SIZE_THRESHOLD_FOR_TOAST = 15 * 1024 * 1024;
    private static final String TARGET_CLASS = "org.telegram.messenger.FileLoadOperation";
    private static final String TARGET_METHOD = "updateParams";

    private static long sSpeedUpShown = 0;

    private final boolean mZh = Locale.getDefault().getLanguage().startsWith("zh");

    public SpeedHook() {
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(Log.INFO, TAG, "TeleSpeed loaded in " + param.getProcessName()
                + " by " + getFrameworkName() + " " + getFrameworkVersionCode()
                + " (api " + getApiVersion() + ")");
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (getModuleApplicationInfo() != null
                && getModuleApplicationInfo().packageName.equals(param.getPackageName())) {
            return;
        }
        if (!param.isFirstPackage()) {
            return;
        }
        try {
            ClassLoader cl = param.getClassLoader();
            Class<?> fileLoadOperation = Class.forName(TARGET_CLASS, true, cl);
            Method updateParams = fileLoadOperation.getDeclaredMethod(TARGET_METHOD);
            Field chunkSizeBig = fileLoadOperation.getDeclaredField("downloadChunkSizeBig");
            Field maxRequests = fileLoadOperation.getDeclaredField("maxDownloadRequests");
            Field maxRequestsBig = fileLoadOperation.getDeclaredField("maxDownloadRequestsBig");
            Field maxCdnParts = fileLoadOperation.getDeclaredField("maxCdnParts");
            Field totalBytes = fileLoadOperation.getDeclaredField("totalBytesCount");
            for (Field f : new Field[]{chunkSizeBig, maxRequests, maxRequestsBig, maxCdnParts, totalBytes}) {
                f.setAccessible(true);
            }

            // Bulletin parts are optional: if Telegram renamed them, the boost still works,
            // just without the toast.
            Method getGlobalInstance = null;
            Field showBulletin = null;
            Method postNotificationName = null;
            try {
                Class<?> notificationCenter = Class.forName("org.telegram.messenger.NotificationCenter", true, cl);
                getGlobalInstance = notificationCenter.getDeclaredMethod("getGlobalInstance");
                showBulletin = notificationCenter.getField("showBulletin");
                for (Method m : notificationCenter.getMethods()) {
                    if (m.getName().equals("postNotificationName") && m.getParameterCount() == 2) {
                        postNotificationName = m;
                        break;
                    }
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "NotificationCenter not available, bulletin disabled", t);
            }

            SharedPreferences prefs = getRemotePreferences("conf");
            HookContext ctx = new HookContext(chunkSizeBig, maxRequests, maxRequestsBig, maxCdnParts,
                    totalBytes, getGlobalInstance, showBulletin, postNotificationName);

            hook(updateParams).intercept(chain -> {
                chain.proceed();
                try {
                    applySpeed(prefs, chain.getThisObject(), ctx);
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "applySpeed failed", t);
                }
                return null;
            });
            log(Log.INFO, TAG, "hooked " + TARGET_CLASS + "." + TARGET_METHOD + " in " + param.getPackageName());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to hook " + TARGET_CLASS + "." + TARGET_METHOD, t);
        }
    }

    private void applySpeed(SharedPreferences prefs, Object self, HookContext ctx) throws Throwable {
        int downloadChunkSizeBig;
        int maxDownloadRequests;
        int maxDownloadRequestsBig;
        String speed = prefs.getString(KEY_SPEED, BOOST_AVERAGE);
        if (BOOST_AVERAGE.equals(speed)) {
            downloadChunkSizeBig = 1024 * 512;
            maxDownloadRequests = 8;
            maxDownloadRequestsBig = 8;
        } else if (BOOST_EXTREME.equals(speed)) {
            downloadChunkSizeBig = 1024 * 1024;
            maxDownloadRequests = 12;
            maxDownloadRequestsBig = 12;
        } else {
            downloadChunkSizeBig = 1024 * 128;
            maxDownloadRequests = 4;
            maxDownloadRequestsBig = 4;
        }
        int maxCdnParts = (int) (DEFAULT_MAX_FILE_SIZE / downloadChunkSizeBig);
        ctx.chunkSizeBig.setInt(self, downloadChunkSizeBig);
        ctx.maxRequests.setInt(self, maxDownloadRequests);
        ctx.maxRequestsBig.setInt(self, maxDownloadRequestsBig);
        ctx.maxCdnParts.setInt(self, maxCdnParts);

        if (!BOOST_NONE.equals(speed) && ctx.getGlobalInstance != null && ctx.postNotificationName != null) {
            long now = System.currentTimeMillis();
            long fileSize = ctx.totalBytes.getLong(self);
            if (fileSize > SIZE_THRESHOLD_FOR_TOAST && now - sSpeedUpShown > SHOW_INTERVAL_MS) {
                sSpeedUpShown = now;
                showBoostBulletin(ctx, speed);
            }
        }
    }

    private void showBoostBulletin(HookContext ctx, String speed) {
        String boost;
        if (BOOST_AVERAGE.equals(speed)) {
            boost = mZh ? "开启" : "Enabled";
        } else if (BOOST_EXTREME.equals(speed)) {
            boost = mZh ? "极高" : "Extreme";
        } else {
            boost = mZh ? "关闭" : "Disabled";
        }
        String title = mZh ? "下载速度已被 TeleSpeed 提升！" : "Download speed boosted by TeleSpeed!";
        String subtitle = (mZh ? "源于 %s, 加速：%s" : "By %s, Boost: %s").formatted("Nekogram", boost);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Object globalInstance = ctx.getGlobalInstance.invoke(null);
                int showBulletinId = ctx.showBulletin.getInt(null);
                Object[] args = {TYPE_ERROR_SUBTITLE, title, subtitle};
                // postNotificationName(int id, Object... args) is reflected as (int, Object[]);
                // two positional arguments avoid varargs spreading of `args`.
                ctx.postNotificationName.invoke(globalInstance, showBulletinId, args);
            } catch (Throwable t) {
                log(Log.WARN, TAG, "show bulletin failed", t);
            }
        });
    }

    private static final class HookContext {
        final Field chunkSizeBig;
        final Field maxRequests;
        final Field maxRequestsBig;
        final Field maxCdnParts;
        final Field totalBytes;
        final Method getGlobalInstance;
        final Field showBulletin;
        final Method postNotificationName;

        HookContext(Field chunkSizeBig, Field maxRequests, Field maxRequestsBig, Field maxCdnParts,
                    Field totalBytes, Method getGlobalInstance, Field showBulletin, Method postNotificationName) {
            this.chunkSizeBig = chunkSizeBig;
            this.maxRequests = maxRequests;
            this.maxRequestsBig = maxRequestsBig;
            this.maxCdnParts = maxCdnParts;
            this.totalBytes = totalBytes;
            this.getGlobalInstance = getGlobalInstance;
            this.showBulletin = showBulletin;
            this.postNotificationName = postNotificationName;
        }
    }
}
