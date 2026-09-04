package com.baofu.suping;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * 进程一起来就注册解锁广播并对齐周期任务。
 *
 * 任何组件被拉起（定时任务执行、开机广播、用户打开 App）都会先创建 Application，
 * 所以这里是唯一保证会执行到的地方。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        IntentFilter f = new IntentFilter(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13 起动态注册必须显式声明导出状态
                registerReceiver(new UnlockReceiver(), f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(new UnlockReceiver(), f);
            }
        } catch (Exception ignored) {}

        UpdateScheduler.sync(this);
        AlarmScheduler.scheduleNext(this);

        // 从没成功设置过（刚装上）就立刻取一张。
        // 不然首次打开是空的，要等周期任务第一次执行，最长可能等一小时。
        boolean never = getSharedPreferences(WallpaperUpdater.PREFS, MODE_PRIVATE)
            .getString(WallpaperUpdater.KEY_LAST_ID, "").isEmpty();
        if (never) UpdateScheduler.checkNow(this);
    }
}
