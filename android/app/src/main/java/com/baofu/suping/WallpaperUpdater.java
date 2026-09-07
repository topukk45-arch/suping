package com.baofu.suping;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 整个 App 唯一的业务逻辑入口。
 *
 * 定时任务、解锁广播、开机广播、网页层的「立即更换」按钮，全都调用 runOnce()。
 * 这里面不能出现任何依赖 WebView / Capacitor Bridge 存活的代码 —— 绝大多数情况下
 * 这个方法执行时，用户根本没打开过 App。
 */
public class WallpaperUpdater {

    public static final String PREFS = "wallpaper";

    // 配置项（网页层可写）
    public static final String KEY_AUTO       = "auto_enabled";   // boolean，是否自动更新
    public static final String KEY_TARGET     = "target";         // "home" | "lock" | "both"

    // 状态项（只有这里写，网页层只读）
    public static final String KEY_LAST_ID    = "last_id";        // 上次成功设置的图片标识（hsh）
    public static final String KEY_LAST_TIME  = "last_time";      // 上次成功设置的时间戳
    public static final String KEY_LAST_TITLE = "last_title";     // 图片说明（copyright 字段）
    public static final String KEY_LAST_ERROR = "last_error";     // 上次失败原因，空表示上次是成功的
    public static final String KEY_RECENT     = "recent_json";    // 最近 8 天列表的缓存
    public static final String KEY_RECENT_AT  = "recent_at";      // 缓存时间戳

    private static final String UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    // 竖版直链后缀。手机屏幕比 1080x1920 更长时系统会自己居中裁剪，效果可以接受；
    // 用 _UHD.jpg 横图的话两侧会被裁掉一大半，不要改。
    private static final String SUFFIX = "_1080x1920.jpg";

    private static final String[] HOSTS = { "https://www.bing.com", "https://cn.bing.com" };
    private static final String API = "/HPImageArchive.aspx?format=js&idx=0&n=%d&mkt=zh-CN";

    /** 供网页层拼图片地址用，两边必须一致 */
    public static final String IMG_HOST = HOSTS[0];
    public static final String IMG_SUFFIX = SUFFIX;

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT    = 30000;

    /** runOnce 的返回值。网页层拿它来显示结果，后台任务拿它决定要不要重试。 */
    public static class Result {
        public boolean ok;          // 整体是否成功（跳过也算成功）
        public boolean changed;     // 是否真的换了新壁纸
        public String  imageId;     // 当前图片标识
        public String  title;       // 图片说明
        public String  error;       // 失败原因，成功时为 null
        public boolean homeOk;      // 桌面是否设置成功
        public boolean lockOk;      // 锁屏是否设置成功
    }

    /**
     * @param force true 表示无视 hsh 比对强制重设一次（网页层的「立即更换」用，
     *              某些 ROM 会把壁纸重置回默认，这时需要强制重设）
     */
    public static Result runOnce(Context ctx, boolean force) {
        Result r = new Result();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // 1.0 版留下过一个只写不读的 previous.jpg，升级上来时清掉
        File stale = new File(ctx.getFilesDir(), "previous.jpg");
        if (stale.exists()) stale.delete();

        try {
            JSONObject img = fetchTodayImage();
            String urlbase = img.optString("urlbase", "");
            if (urlbase.isEmpty()) throw new Exception("接口没有返回 urlbase");

            // 优先用 hsh 当标识；老接口偶尔不返回 hsh，退回用 urlbase。
            // 绝对不要用本地日期判断「今天换没换」—— 手机时钟、时区、必应 CDN 推送
            // 三者都可能对不上，hsh 是唯一可靠的依据。
            String id = img.optString("hsh", "");
            if (id.isEmpty()) id = urlbase;

            r.imageId = id;
            r.title   = img.optString("copyright", "");

            String lastId = sp.getString(KEY_LAST_ID, "");
            File current  = new File(ctx.getFilesDir(), "current.jpg");

            if (!force && id.equals(lastId) && current.exists()) {
                // 没换图，什么都不做。定时任务绝大多数次数走的是这个分支，
                // 代价只有一次几 KB 的 JSON 请求。
                r.ok = true;
                r.changed = false;
                sp.edit().putString(KEY_LAST_ERROR, "").apply();
                return r;
            }

            // 下载到 .tmp 再改名，避免下到一半的图被当成完整壁纸用掉。
            // 中途失败必须把 .tmp 删掉，否则半张图会一直躺在私有目录里。
            File tmp = new File(ctx.getFilesDir(), "current.jpg.tmp");
            try {
                download(HOSTS[0] + urlbase + SUFFIX, tmp);
                if (tmp.length() < 10240) throw new Exception("下载到的文件过小，可能不是图片");
            } catch (Exception e) {
                tmp.delete();
                throw e;
            }
            if (current.exists() && !current.delete()) throw new Exception("旧图片删不掉");
            if (!tmp.renameTo(current)) throw new Exception("保存图片失败");

            String target = sp.getString(KEY_TARGET, "both");
            boolean wantHome = !"lock".equals(target);
            boolean wantLock = !"home".equals(target);

            // 桌面和锁屏分两次设置，各自开一个新的 InputStream。
            // 不合并成一次 FLAG_SYSTEM|FLAG_LOCK 调用，是因为部分国产 ROM 对锁屏
            // 壁纸有自己的一套实现，合并调用时锁屏失败会连带桌面一起失败。
            if (wantHome) r.homeOk = apply(ctx, current, WallpaperManager.FLAG_SYSTEM);
            if (wantLock) r.lockOk = apply(ctx, current, WallpaperManager.FLAG_LOCK);

            if ((wantHome && !r.homeOk) && (wantLock && !r.lockOk)) {
                throw new Exception("系统拒绝了设置壁纸的请求");
            }

            sp.edit()
                .putString(KEY_LAST_ID, id)
                .putString(KEY_LAST_TITLE, r.title)
                .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                .putString(KEY_LAST_ERROR, "")
                .apply();

            r.ok = true;
            r.changed = true;
            return r;

        } catch (Exception e) {
            r.ok = false;
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
            sp.edit().putString(KEY_LAST_ERROR, r.error).apply();
            return r;
        }
    }

    private static boolean apply(Context ctx, File file, int which) {
        InputStream is = null;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            is = new FileInputStream(file);
            // setStream 的 which 参数和 FLAG_LOCK 都要求 API 24+，minSdk 已提到 24
            wm.setStream(is, null, true, which);
            return true;
        } catch (Throwable t) {
            // 锁屏在 MIUI / HyperOS / EMUI 上可能被忽略甚至抛异常，
            // 这里吞掉让桌面那一路继续走完
            return false;
        } finally {
            close(is);
        }
    }

    private static JSONObject fetchTodayImage() throws Exception {
        return fetchImages(1).getJSONObject(0);
    }

    /** 拉最近 n 天（接口上限 8）。设壁纸只要 n=1，浏览往期用 n=8。 */
    public static org.json.JSONArray fetchImages(int n) throws Exception {
        Exception last = null;
        for (String host : HOSTS) {
            try {
                String body = getText(host + String.format(java.util.Locale.US, API, n));
                return new JSONObject(body).getJSONArray("images");
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new Exception("无法连接必应") : last;
    }

    private static String getText(String url) throws Exception {
        HttpURLConnection conn = open(url);
        InputStreamReader reader = null;
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("接口返回 HTTP " + code);
            reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            close(reader);
            conn.disconnect();
        }
    }

    private static void download(String url, File dest) throws Exception {
        HttpURLConnection conn = open(url);
        InputStream in = null;
        OutputStream out = null;
        boolean done = false;
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("下载图片返回 HTTP " + code);
            in = conn.getInputStream();
            out = new FileOutputStream(dest);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            done = true;
        } finally {
            close(in);
            close(out);
            conn.disconnect();
            // 断网、超时、写盘失败都会走到这里。下到一半的残片不能留在磁盘上，
            // 否则下次进来 tmp.length() 的体积检查可能被它蒙混过去。
            if (!done) dest.delete();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        return conn;
    }

    private static void close(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }
}
