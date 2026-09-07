package com.baofu.suping;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** 任务调度。开机广播、解锁广播、网页层改设置、App 启动时都会调到。 */
public class UpdateScheduler {

    private static final String WORK_PERIODIC = "bing_wallpaper_check";
    private static final String WORK_ONESHOT  = "bing_wallpaper_now";

    private static Constraints constraints() {
        return new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();
    }

    /**
     * 间隔定 1 小时而不是允许的最小值 15 分钟：没换图时这次检查只发一个几 KB 的
     * JSON 请求就返回，成本极低，但每次唤醒本身是要耗电的，1 小时足够了。
     * 必应 00:00 换图，最迟 1 小时内能换上，实际上早上解锁那次广播通常更早触发。
     */
    public static void enable(Context ctx) {
        PeriodicWorkRequest req =
            new PeriodicWorkRequest.Builder(DailyWorker.class, 1, TimeUnit.HOURS)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build();

        // KEEP：已经排好的任务不要因为一次开机或一次打开 App 就重置计时
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    public static void disable(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_PERIODIC);
    }

    /** 按当前设置启停周期任务 */
    public static void sync(Context ctx) {
        boolean auto = ctx.getSharedPreferences(WallpaperUpdater.PREFS, Context.MODE_PRIVATE)
                          .getBoolean(WallpaperUpdater.KEY_AUTO, true);
        if (auto) enable(ctx); else disable(ctx);
    }

    /**
     * 立刻检查一次（解锁广播、闹钟、首次安装用）。
     * 走 WorkManager 是为了不占广播那 10 秒的执行窗口。
     *
     * 这里刻意不用 setExpedited()：API 31 以下 WorkManager 会去调
     * getForegroundInfoAsync()，而 DailyWorker 继承的 Worker 没有重写它，
     * 默认实现直接抛 IllegalStateException —— Android 7~11 上会让解锁触发、
     * 闹钟触发、首次取图三条路全部失败。换壁纸也没有加急的必要。
     */
    public static void checkNow(Context ctx) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(DailyWorker.class)
            .setConstraints(constraints())
            .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK_ONESHOT, ExistingWorkPolicy.KEEP, req);
    }
}
