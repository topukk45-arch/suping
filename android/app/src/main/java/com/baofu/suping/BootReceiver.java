package com.baofu.suping;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机 / 升级后重新排定所有后台任务。
 *
 * 两件事都必须做，漏掉任何一件都会导致"重启手机之后就不自动换了"这种极难排查的问题：
 *  1. WorkManager 周期任务 —— 理论上它自己会恢复，但国产 ROM 上很不可靠
 *  2. AlarmManager 闹钟 —— 这个是硬性的，系统关机时会把所有闹钟全部丢弃
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        UpdateScheduler.sync(app);
        AlarmScheduler.scheduleNext(app);
    }
}
