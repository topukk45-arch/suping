package com.baofu.suping;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 解锁屏幕时检查一次 —— 早上第一次解锁必然触发，是比定时任务更贴近实际的入口。
 *
 * 两个必须知道的限制：
 * 1. USER_PRESENT 从 Android 8 起不允许静态注册，只能由 App.java 在进程启动时动态
 *    注册，因此只在进程还活着时有效，属于尽力而为的兜底，主力仍然是 WorkManager。
 * 2. onReceive 在主线程，且广播的执行窗口只有约 10 秒，下载图片可能超时被杀。
 *    所以这里不直接干活，只丢一个一次性任务给 WorkManager 去跑。
 */
public class UnlockReceiver extends BroadcastReceiver {

    private static final String KEY_LAST_CHECK = "last_check";
    private static final long MIN_GAP_MS = 30 * 60 * 1000L;   // 半小时内不重复检查

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(WallpaperUpdater.PREFS, Context.MODE_PRIVATE);

        if (!sp.getBoolean(WallpaperUpdater.KEY_AUTO, true)) return;

        long now = System.currentTimeMillis();
        if (now - sp.getLong(KEY_LAST_CHECK, 0) < MIN_GAP_MS) return;
        sp.edit().putLong(KEY_LAST_CHECK, now).apply();

        UpdateScheduler.checkNow(app);
    }
}
