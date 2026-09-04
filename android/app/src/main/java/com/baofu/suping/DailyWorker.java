package com.baofu.suping;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** 周期检查。doWork 本身就在后台线程，可以直接做网络和设壁纸。 */
public class DailyWorker extends Worker {

    public DailyWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        boolean auto = ctx.getSharedPreferences(WallpaperUpdater.PREFS, Context.MODE_PRIVATE)
                          .getBoolean(WallpaperUpdater.KEY_AUTO, true);
        if (!auto) return Result.success();

        WallpaperUpdater.Result r = WallpaperUpdater.runOnce(ctx, false);
        // 失败就 retry，交给 WorkManager 的指数退避；不要在这里自己 sleep 重试
        return r.ok ? Result.success() : Result.retry();
    }
}
