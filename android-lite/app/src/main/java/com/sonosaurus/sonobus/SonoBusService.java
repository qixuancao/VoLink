// SPDX-License-Identifier: GPL-3.0-or-later
package com.sonosaurus.sonobus;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

public class SonoBusService extends Service {
    static final String ACTION_TOGGLE_MIC = "com.sharkhome.codexvoice.TOGGLE_MIC";
    private PowerManager.WakeLock wake;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TOGGLE_MIC.equals(intent.getAction()))
            SonoBusActivity.toggleMicrophoneFromNotification();
        updateNotification();
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "codexvoice:audio");
            wake.acquire();
        }
        return START_NOT_STICKY;
    }
    void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("voice", "VoLink 语音连接", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, SonoBusActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent toggle = PendingIntent.getService(this, 1,
            new Intent(this, SonoBusService.class).setAction(ACTION_TOGGLE_MIC),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean mic = SonoBusActivity.isMicrophoneRequested();
        Notification n = new Notification.Builder(this, "voice").setContentTitle("VoLink")
            .setContentText(SonoBusActivity.microphoneStatus()).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, mic ? "关闭麦克风" : "开启麦克风", toggle).build())
            .setSmallIcon(R.drawable.ic_notify).setOngoing(true).build();
        startForeground(1555, n);
    }
    @Override public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }
}
