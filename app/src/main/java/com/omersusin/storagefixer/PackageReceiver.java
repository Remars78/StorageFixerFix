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
        boolean isReplace = Intent.ACTION_PACKAGE_REPLACED.equals(action);

        FixerLog.i("Package event: " + action + " -> " + pkg);

        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                // Wait 2s for system MediaProvider to settle
                FixerLog.i("Waiting 2s for MediaProvider...");
                Thread.sleep(2000);

                // Pass 1
                FixerLog.i("Pass 1 for " + pkg);
                StorageFixer.fixPackage(pkg);

                // Wait 3s for app to create subdirs
                Thread.sleep(3000);

                // Pass 2
                FixerLog.i("Pass 2 for " + pkg);
                StorageFixer.FixResult r = StorageFixer.fixPackage(pkg);

                // Force stop (especially important for reinstalls)
                if (isReplace) {
                    StorageFixer.forceStopPackage(pkg);
                }

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
