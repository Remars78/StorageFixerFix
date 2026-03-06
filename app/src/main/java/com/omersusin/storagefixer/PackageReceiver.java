package com.omersusin.storagefixer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class PackageReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null) return;

        String pkg = intent.getData().getSchemeSpecificPart();
        if (pkg == null) return;
        if (pkg.equals(context.getPackageName())) return;

        FixerLog.i("📦 " + intent.getAction() + " → " + pkg);

        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                if (!StorageFixer.isFuseReady()) {
                    StorageFixer.waitForFuse(30);
                }
                StorageFixer.FixResult r = StorageFixer.fixPackage(pkg);
                FixerLog.i("Auto-fix: " + r);
            } finally {
                pending.finish();
            }
        }).start();
    }
}