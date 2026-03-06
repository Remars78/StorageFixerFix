package com.omersusin.storagefixer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        FixerLog.i("🔄 Boot completed — starting service");
        Intent svc = new Intent(context, FixerService.class);
        svc.setAction("BOOT_SCAN");
        context.startForegroundService(svc);
    }
}