package com.dsh.eye;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机 / 应用更新 / 闹钟到点 —— 三条路都把服务拉起来。
 *
 * 华为会把后台进程一次性全清（电池白名单 + 前台服务照样杀），所以只靠
 * START_STICKY 不够。这里的 alarm 是最后一层网，间隔 10 分钟。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            Intent i = new Intent(ctx, EyeService.class);
            ctx.startService(i);
            EyeService.scheduleWake(ctx, 10 * 60 * 1000);
            Log.i("DshEye", "wake by " + (intent == null ? "?" : intent.getAction()));
        } catch (Throwable e) {
            Log.w("DshEye", "wake failed: " + e);
        }
    }
}
