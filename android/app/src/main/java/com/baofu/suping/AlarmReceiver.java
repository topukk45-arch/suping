package com.baofu.suping;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 00:10 闹钟响了。
 *
 * 跟 UnlockReceiver 一样，这里不直接下载图片 —— 广播的执行窗口只有约 10 秒，
 * 网络慢一点就会被系统杀掉。只丢一个一次性任务给 WorkManager，然后立刻把
 * 明天的闹钟排上。
 */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        UpdateScheduler.checkNow(app);
        AlarmScheduler.scheduleNext(app);   // 一次性闹钟，响完必须自己排下一次
    }
}
