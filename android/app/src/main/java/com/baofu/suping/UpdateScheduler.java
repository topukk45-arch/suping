package com.baofu.suping;

import android.content.Context;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

public class UpdateScheduler {
    private static final String PERIODIC_WORK_NAME = "DailyWallpaperUpdate";
    private static final String ONE_TIME_WORK_NAME = "CheckNowWallpaperUpdate";

    /**
     * 供 App、BootReceiver 和 WallpaperPlugin 调用的同步入口
     */
    public static void sync(Context context) {
        // 注册周期性定时任务（KEEP 策略保证不会重复创建）
        schedulePeriodic(context);
        // 如需在启动/开机时立即触发一次检查，可按需取消下行注释
        // checkNow(context);
    }

    public static void schedulePeriodic(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                DailyWorker.class, 1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
        );
    }

    public static void checkNow(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DailyWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
        );
    }

    public static void cancelAll(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME);
        WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK_NAME);
    }
}