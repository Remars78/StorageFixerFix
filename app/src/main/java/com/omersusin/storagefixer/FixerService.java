package com.omersusin.storagefixer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class FixerService extends Service {

    private static final int NOTIF_ID = 1;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotif("Starting..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        if (!running.compareAndSet(false, true)) {
            FixerLog.w("Already running, skipping");
            return START_NOT_STICKY;
        }

        new Thread(() -> {
            try {
                if (!StorageFixer.isRootAvailable()) {
                    FixerLog.e("❌ Root not available");
                    updateNotif("❌ No root");
                    return;
                }

                updateNotif("⏳ Waiting for storage...");
                if (!StorageFixer.waitForFuse(60)) {
                    updateNotif("❌ Storage timeout");
                    return;
                }

                updateNotif("🔍 Scanning...");
                List<StorageFixer.FixResult> results =
                        StorageFixer.fixAll(this);

                long ok = results.stream()
                        .filter(r -> r.success).count();
                String msg = "✅ " + ok + "/" + results.size() + " fixed";
                updateNotif(msg);

                Thread.sleep(3000);

            } catch (Exception e) {
                FixerLog.e("Service error: " + e.getMessage());
            } finally {
                running.set(false);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }).start();

        return START_NOT_STICKY;
    }

    private Notification buildNotif(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, App.CHANNEL_ID)
                .setContentTitle("StorageFixer")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotif(String text) {
        getSystemService(NotificationManager.class)
                .notify(NOTIF_ID, buildNotif(text));
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}