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

        FixerLog.i("Package event: " + intent.getAction() + " -> " + pkg);

        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                // Wait 2s for system's MediaProvider to finish (or fail)
                // This prevents race condition with FUSE dentry caching
                FixerLog.i("Waiting 2s for MediaProvider to settle...");
                Thread.sleep(2000);

                // Pass 1: Initial fix on lower filesystem
                FixerLog.i("Pass 1 for " + pkg);
                StorageFixer.fixPackage(pkg);

                // Wait 3s for app to create its own subdirectories
                Thread.sleep(3000);

                // Pass 2: Fix any subdirs the app created with wrong perms
                FixerLog.i("Pass 2 for " + pkg);
                StorageFixer.FixResult r = StorageFixer.fixPackage(pkg);

                // Trigger media rescan so FUSE picks up changes
                StorageFixer.triggerMediaRescan(pkg);

                FixerLog.i("Auto-fix complete: " + r);

            } catch (InterruptedException ignored) {
            } finally {
                pending.finish();
            }
        }).start();
    }
}
