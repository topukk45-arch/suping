package com.baofu.suping;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 网页层和原生层之间唯一的通道。
 *
 * 刻意做得很薄：所有业务逻辑都在 WallpaperUpdater 里，这里只负责读写
 * SharedPreferences 和转发调用。SharedPreferences 是两层之间的唯一真相源。
 *
 * JS 侧用法：
 *   getState()                      -> { autoEnabled, target, lastTitle, lastTime, imagePath, lastError }
 *   getRecent()                     -> { imgHost, imgSuffix, images:[{id,urlbase,title,date}], cached }
 *   setConfig({autoEnabled, target})
 *   updateNow({force})              -> { ok, changed, title, homeOk, lockOk, error }
 *   openPowerSettings()             -> 跳转系统电池优化白名单页
 *   openAppSettings()               -> 跳转本应用详情页（自启动管理入口在这里面）
 */
@CapacitorPlugin(name = "Wallpaper")
public class WallpaperPlugin extends Plugin {

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(
            WallpaperUpdater.PREFS, Context.MODE_PRIVATE);
    }

    @PluginMethod
    public void getState(PluginCall call) {
        SharedPreferences sp = prefs();
        File img = new File(getContext().getFilesDir(), "current.jpg");

        JSObject ret = new JSObject();
        ret.put("autoEnabled", sp.getBoolean(WallpaperUpdater.KEY_AUTO, true));
        ret.put("target",      sp.getString(WallpaperUpdater.KEY_TARGET, "both"));
        ret.put("lastTitle",   sp.getString(WallpaperUpdater.KEY_LAST_TITLE, ""));
        ret.put("lastTime",    sp.getLong(WallpaperUpdater.KEY_LAST_TIME, 0));
        ret.put("lastError",   sp.getString(WallpaperUpdater.KEY_LAST_ERROR, ""));
        // 返回绝对路径，JS 侧要用 Capacitor.convertFileSrc() 转成 capacitor:// 才能加载。
        // 直接写 <img src="file:///data/..."> 在 WebView 里是加载不出来的。
        ret.put("imagePath",   img.exists() ? img.getAbsolutePath() : "");
        call.resolve(ret);
    }

    /**
     * 最近 8 天的列表，给左右滑动浏览用。
     *
     * 这个必须走原生层：必应的接口不返回跨域头，网页层直接 fetch 会被 CORS 挡下来。
     * 但图片本身不受同源限制，网页层拿到 urlbase 后直接 <img src="https://..."> 就能加载，
     * 所以往期图片完全不落地，不占存储。
     *
     * 缓存一小时，避免每次打开 App 都发请求。
     */
    @PluginMethod
    public void getRecent(PluginCall call) {
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            SharedPreferences sp = app.getSharedPreferences(
                WallpaperUpdater.PREFS, Context.MODE_PRIVATE);
            JSObject ret = new JSObject();
            ret.put("imgHost", WallpaperUpdater.IMG_HOST);
            ret.put("imgSuffix", WallpaperUpdater.IMG_SUFFIX);

            String cached = sp.getString(WallpaperUpdater.KEY_RECENT, "");
            long at = sp.getLong(WallpaperUpdater.KEY_RECENT_AT, 0);
            boolean fresh = !cached.isEmpty()
                && System.currentTimeMillis() - at < 60 * 60 * 1000L;

            if (fresh) {
                try {
                    ret.put("images", new JSONArray(cached));
                    ret.put("cached", true);
                    call.resolve(ret);
                    return;
                } catch (Exception ignored) {}
            }

            try {
                JSONArray raw = WallpaperUpdater.fetchImages(8);
                JSONArray out = new JSONArray();
                for (int i = 0; i < raw.length(); i++) {
                    JSONObject o = raw.getJSONObject(i);
                    String urlbase = o.optString("urlbase", "");
                    if (urlbase.isEmpty()) continue;
                    JSONObject e = new JSONObject();
                    String id = o.optString("hsh", "");
                    e.put("id", id.isEmpty() ? urlbase : id);
                    e.put("urlbase", urlbase);
                    e.put("title", o.optString("copyright", ""));
                    // enddate 才是这张图在北京时间对应的日期；startdate 是 UTC 日期，早一天
                    e.put("date", o.optString("enddate", ""));
                    out.put(e);
                }
                sp.edit()
                  .putString(WallpaperUpdater.KEY_RECENT, out.toString())
                  .putLong(WallpaperUpdater.KEY_RECENT_AT, System.currentTimeMillis())
                  .apply();
                ret.put("images", out);
                ret.put("cached", false);
                call.resolve(ret);
            } catch (Exception e) {
                // 拿不到就退回旧缓存，总比什么都没有强
                if (!cached.isEmpty()) {
                    try {
                        ret.put("images", new JSONArray(cached));
                        ret.put("cached", true);
                        call.resolve(ret);
                        return;
                    } catch (Exception ignored) {}
                }
                call.reject(e.getMessage() == null ? "获取失败" : e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void setConfig(PluginCall call) {
        SharedPreferences.Editor e = prefs().edit();
        if (call.hasOption("autoEnabled")) {
            e.putBoolean(WallpaperUpdater.KEY_AUTO,
                         Boolean.TRUE.equals(call.getBoolean("autoEnabled")));
        }
        if (call.hasOption("target")) {
            String t = call.getString("target", "both");
            if (!"home".equals(t) && !"lock".equals(t)) t = "both";
            e.putString(WallpaperUpdater.KEY_TARGET, t);
        }
        e.apply();

        Context app = getContext().getApplicationContext();
        UpdateScheduler.sync(app);
        AlarmScheduler.scheduleNext(app);
        call.resolve();
    }

    @PluginMethod
    public void updateNow(PluginCall call) {
        final boolean force = Boolean.TRUE.equals(call.getBoolean("force", true));
        final Context app = getContext().getApplicationContext();

        // Capacitor 的插件方法在主线程被调用，网络请求必须自己开线程
        new Thread(() -> {
            WallpaperUpdater.Result r = WallpaperUpdater.runOnce(app, force);
            JSObject ret = new JSObject();
            ret.put("ok", r.ok);
            ret.put("changed", r.changed);
            ret.put("title", r.title == null ? "" : r.title);
            ret.put("homeOk", r.homeOk);
            ret.put("lockOk", r.lockOk);
            ret.put("error", r.error == null ? "" : r.error);
            call.resolve(ret);
        }).start();
    }

    @PluginMethod
    public void openPowerSettings(PluginCall call) {
        // 用列表页而不是 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，
        // 后者需要额外权限且在应用商店是敏感权限，这里没必要
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            openAppSettings(call);
        }
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.fromParts("package", getContext().getPackageName(), null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("无法打开系统设置");
        }
    }
}
