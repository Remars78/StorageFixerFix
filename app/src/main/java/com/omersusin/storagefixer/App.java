package com.omersusin.storagefixer;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import com.topjohnwu.superuser.Shell;

public class App extends Application {

    public static final String CHANNEL_ID = "storage_fixer_channel";

    static {
        Shell.enableVerboseLogging = BuildConfig.DEBUG;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setTimeout(10));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        FixerLog.init(this);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "StorageFixer Service",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Storage permission fix notifications");
        getSystemService(NotificationManager.class)
                .createNotificationChannel(channel);
    }
}
