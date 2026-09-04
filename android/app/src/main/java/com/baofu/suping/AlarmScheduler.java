package com.baofu.suping;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/**
 * 每天 00:10 的闹钟 —— 整套触发机制里的主力。
 *
 * 为什么必须有它：必应 00:00 换图，用户早上七点看。这中间手机在 Doze 深度休眠，
 * WorkManager 的周期任务基本不会执行。只靠周期任务和解锁广播的话，用户每天早上
 * 第一眼看到的都还是昨天那张，要等解锁之后才开始更新。
 *
 * 为什么用 setAndAllowWhileIdle 而不是 setExactAndAllowWhileIdle：
 * 两者都能穿透 Doze，但后者在 Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限，
 * 得引导用户去系统设置里手动开。前者不需要任何权限，代价只是不精确
 * （定 00:10 可能 00:25 才响）—— 对换壁纸这件事完全无所谓。
 *
 * 定 00:10 而不是 00:00，是留出必应 CDN 的推送延迟。
 */
public class AlarmScheduler {

    private static final int HOUR = 0, MINUTE = 10;
    private static final int REQ = 1001;

    private static PendingIntent intent(Context ctx) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQ, i, flags);
    }

    /** 排下一次。幂等，重复调用只是把时间往后推到下一个 00:10。 */
    public static void scheduleNext(Context ctx) {
        boolean auto = ctx.getSharedPreferences(WallpaperUpdater.PREFS, Context.MODE_PRIVATE)
                          .getBoolean(WallpaperUpdater.KEY_AUTO, true);
        if (!auto) { cancel(ctx); return; }

        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, HOUR);
        c.set(Calendar.MINUTE, MINUTE);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), intent(ctx));
        } catch (Exception ignored) {}
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) try { am.cancel(intent(ctx)); } catch (Exception ignored) {}
    }
}
