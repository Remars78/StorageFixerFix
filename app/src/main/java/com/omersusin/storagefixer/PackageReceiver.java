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
                // Wait 10s for vold to try and fail
                FixerLog.i("Waiting 10s for vold...");
                Thread.sleep(10000);

                if (!StorageFixer.needsFix(pkg)) {
                    FixerLog.i(pkg + " dirs OK, skipping");
                    return;
                }

                FixerLog.i("Fixing " + pkg + "...");
                StorageFixer.FixResult r = StorageFixer.fixPackage(context, pkg);
                StorageFixer.forceStopPackage(pkg);
                StorageFixer.triggerMediaRescan();
                FixerLog.i("Auto-fix done: " + r);

            } catch (InterruptedException ignored) {
            } finally {
                pending.finish();
            }
        }).start();
    }
}
