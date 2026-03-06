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

        String action = intent.getAction();
        FixerLog.i("Package event: " + action + " -> " + pkg);

        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                // CRITICAL: Wait 5s for vold to fail and settle.
                // If we fix too early, vold will overwrite our fix.
                FixerLog.i("Waiting 5s for vold to settle...");
                Thread.sleep(5000);

                // Pass 1: fix with app UID
                FixerLog.i("Pass 1 for " + pkg);
                StorageFixer.fixPackage(context, pkg);

                // Wait 3s for app to create its own subdirs
                Thread.sleep(3000);

                // Pass 2: fix any new subdirs
                FixerLog.i("Pass 2 for " + pkg);
                StorageFixer.FixResult r = StorageFixer.fixPackage(context, pkg);

                // Force stop so app picks up new permissions
                StorageFixer.forceStopPackage(pkg);

                // Trigger media rescan
                StorageFixer.triggerMediaRescan(pkg);

                FixerLog.i("Auto-fix complete: " + r);

            } catch (InterruptedException ignored) {
            } finally {
                pending.finish();
            }
        }).start();
    }
}
