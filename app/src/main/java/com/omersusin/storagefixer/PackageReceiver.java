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
                // Wait 10s for vold to try AND FAIL completely
                // This is critical - we must fix AFTER vold gives up
                FixerLog.i("Waiting 10s for vold to settle...");
                Thread.sleep(10000);

                // Check if fix is needed
                if (!StorageFixer.needsFix(pkg)) {
                    FixerLog.i("  " + pkg + " dirs OK, skipping");
                    return;
                }

                // Fix (matching manual fix exactly)
                FixerLog.i("Fixing " + pkg + "...");
                StorageFixer.FixResult r = StorageFixer.fixPackage(pkg);

                // Force stop so app gets fresh FUSE mount on next launch
                StorageFixer.forceStopPackage(pkg);

                // Rescan
                StorageFixer.triggerMediaRescan();

                FixerLog.i("Auto-fix done: " + r);

            } catch (InterruptedException ignored) {
            } finally {
                pending.finish();
            }
        }).start();
    }
}
