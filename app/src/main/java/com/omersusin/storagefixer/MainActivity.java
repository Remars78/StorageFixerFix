package com.omersusin.storagefixer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView statusView, logView;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.statusView);
        logView = findViewById(R.id.logView);
        scrollView = findViewById(R.id.scrollView);
        Button btnFix = findViewById(R.id.btnFixAll);
        Button btnRefresh = findViewById(R.id.btnRefreshLog);
        Button btnClear = findViewById(R.id.btnClearLog);

        // Notification permission (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }

        btnFix.setOnClickListener(v -> {
            statusView.setText("⏳ Running...");
            Intent svc = new Intent(this, FixerService.class);
            svc.setAction("MANUAL_SCAN");
            startForegroundService(svc);
            v.postDelayed(this::refreshLog, 5000);
        });

        btnRefresh.setOnClickListener(v -> refreshLog());

        btnClear.setOnClickListener(v -> {
            FixerLog.clear();
            logView.setText("Log cleared.");
        });

        checkStatus();
        refreshLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLog();
    }

    private void checkStatus() {
        new Thread(() -> {
            boolean root = StorageFixer.isRootAvailable();
            boolean fuse = StorageFixer.isFuseReady();
            String s = "Root: " + (root ? "✓" : "✗")
                    + "   Storage: " + (fuse ? "✓" : "✗");
            runOnUiThread(() -> statusView.setText(s));
        }).start();
    }

    private void refreshLog() {
        List<String> logs = FixerLog.readAll();
        StringBuilder sb = new StringBuilder();
        if (logs.isEmpty()) {
            sb.append("No logs yet.\n\n");
            sb.append("Auto-fix runs on:\n");
            sb.append("• Boot\n");
            sb.append("• App install / update\n\n");
            sb.append("Tap 'Fix All' for manual scan.");
        } else {
            int start = Math.max(0, logs.size() - 200);
            for (int i = start; i < logs.size(); i++)
                sb.append(logs.get(i)).append("\n");
        }
        logView.setText(sb.toString());
        scrollView.post(() ->
                scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}