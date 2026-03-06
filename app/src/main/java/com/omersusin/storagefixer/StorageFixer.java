package com.omersusin.storagefixer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.topjohnwu.superuser.Shell;

import java.util.ArrayList;
import java.util.List;

public class StorageFixer {

    private static final String LOWER = "/data/media/0/Android";
    private static final String FUSE = "/storage/emulated/0/Android";
    private static final String OWNER = "media_rw:media_rw";
    private static final String SECTX = "u:object_r:media_rw_data_file:s0";
    private static final String[] DIR_TYPES = {"data", "obb", "media"};

    public static boolean isRootAvailable() {
        return Shell.getShell().isRoot();
    }

    public static boolean isFuseReady() {
        return Shell.cmd("ls " + FUSE + "/data/").exec().isSuccess();
    }

    public static boolean waitForFuse(int maxSeconds) {
        for (int i = 0; i < maxSeconds; i++) {
            if (isFuseReady()) {
                FixerLog.i("FUSE ready after " + i + "s");
                return true;
            }
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        FixerLog.e("FUSE not ready after " + maxSeconds + "s");
        return false;
    }

    // ========== ONLY CHECK IF DIRECTORY IS MISSING ==========
    // Do NOT check permissions - that could flag working apps

    public static boolean needsFix(String pkg) {
        for (String type : DIR_TYPES) {
            String fusePath = FUSE + "/" + type + "/" + pkg;
            Shell.Result exists = Shell.cmd(
                "[ -d '" + fusePath + "' ] && echo Y || echo N"
            ).exec();
            if (exists.getOut().isEmpty() || "N".equals(exists.getOut().get(0))) {
                return true;
            }
        }
        return false;
    }

    // ========== FIX PACKAGE ==========
    // Matches manual fix EXACTLY: mkdir + chmod 777 + chown media_rw + chcon
    // NO subdirectories - let the app/vold create them

    public static FixResult fixPackage(String pkg) {
        return fixPackage(pkg, false);
    }

    public static FixResult fixPackage(String pkg, boolean diagnose) {
        FixResult r = new FixResult(pkg);

        if (diagnose) {
            FixerLog.divider();
            FixerLog.i("DIAGNOSING: " + pkg);
            logPackageInfo(pkg);
        }

        // Fix on LOWER filesystem (bypasses FUSE)
        boolean lowerData = fixSingleDir(LOWER + "/data/" + pkg, diagnose);
        boolean lowerObb = fixSingleDir(LOWER + "/obb/" + pkg, diagnose);
        boolean lowerMedia = fixSingleDir(LOWER + "/media/" + pkg, diagnose);

        // Also fix on FUSE path (like MT Manager does)
        boolean fuseData = fixSingleDir(FUSE + "/data/" + pkg, diagnose);
        boolean fuseObb = fixSingleDir(FUSE + "/obb/" + pkg, diagnose);
        boolean fuseMedia = fixSingleDir(FUSE + "/media/" + pkg, diagnose);

        r.dataOk = lowerData || fuseData;
        r.obbOk = lowerObb || fuseObb;
        r.mediaOk = lowerMedia || fuseMedia;

        // Try to kick vold/installd to re-prepare
        triggerSystemPrepare(pkg, diagnose);

        if (diagnose) {
            // Write tests
            FixerLog.i("=== WRITE TESTS ===");
            for (String type : DIR_TYPES) {
                boolean lower = testWrite(LOWER + "/" + type + "/" + pkg);
                boolean fuse = testWrite(FUSE + "/" + type + "/" + pkg);
                FixerLog.i("  " + type + ": lower=" + (lower ? "PASS" : "FAIL")
                        + " fuse=" + (fuse ? "PASS" : "FAIL"));
            }
            r.writeTestOk = testWrite(FUSE + "/data/" + pkg);
        }

        r.success = r.dataOk && r.obbOk;
        FixerLog.i((r.success ? "OK" : "FAIL") + " " + pkg
                + " [data:" + (r.dataOk ? "ok" : "fail")
                + " obb:" + (r.obbOk ? "ok" : "fail")
                + " media:" + (r.mediaOk ? "ok" : "fail") + "]");

        if (diagnose) FixerLog.divider();
        return r;
    }

    private static boolean fixSingleDir(String path, boolean diagnose) {
        if (diagnose) logDirState("BEFORE", path);

        // Ensure parent exists
        String parent = path.substring(0, path.lastIndexOf('/'));
        Shell.cmd("mkdir -p '" + parent + "'").exec();

        // Create ONLY the top-level package directory
        // NO subdirectories - matching manual fix exactly
        Shell.Result mk = Shell.cmd("mkdir -p '" + path + "'").exec();
        if (!mk.isSuccess()) {
            if (diagnose) for (String e : mk.getErr()) FixerLog.e("  mkdir ERR: " + e);
            return false;
        }

        // chmod 777 - matching manual fix
        Shell.cmd("chmod 777 '" + path + "'").exec();

        // chown media_rw:media_rw - matching manual fix
        Shell.cmd("chown " + OWNER + " '" + path + "'").exec();

        // chcon - matching manual fix
        Shell.Result chcon = Shell.cmd("chcon " + SECTX + " '" + path + "'").exec();
        if (!chcon.isSuccess()) {
            // Try alternatives
            String[] alts = {"u:object_r:media_data_file:s0", "u:object_r:fuse:s0"};
            for (String alt : alts) {
                if (Shell.cmd("chcon " + alt + " '" + path + "'").exec().isSuccess()) {
                    if (diagnose) FixerLog.i("  chcon OK: " + alt);
                    break;
                }
            }
        }

        if (diagnose) logDirState("AFTER", path);

        Shell.Result verify = Shell.cmd(
            "[ -d '" + path + "' ] && echo OK || echo FAIL"
        ).exec();
        return !verify.getOut().isEmpty() && "OK".equals(verify.getOut().get(0));
    }

    // ========== TRY TO TRIGGER SYSTEM PREPARE ==========

    private static void triggerSystemPrepare(String pkg, boolean diagnose) {
        if (diagnose) FixerLog.i("Attempting system prepare triggers...");

        // Method 1: Force package state update via disable/enable cycle
        // This can trigger PMS to re-call installd prepare_app_data
        Shell.Result disableRes = Shell.cmd(
            "pm disable '" + pkg + "' 2>/dev/null"
        ).exec();
        if (disableRes.isSuccess()) {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            Shell.cmd("pm enable '" + pkg + "'").exec();
            if (diagnose) FixerLog.i("  pm disable/enable cycle done");
        } else {
            if (diagnose) FixerLog.w("  pm disable failed (expected for some apps)");
        }

        // Method 2: Force MediaProvider to rescan
        Shell.cmd(
            "content call --uri content://media/"
            + " --method scan_volume --arg external_primary"
        ).exec();
        if (diagnose) FixerLog.i("  MediaProvider rescan triggered");

        // Method 3: Try sm (StorageManager) command
        Shell.cmd("sm prepare-user-storage '' 0 131076 0 2>/dev/null").exec();
        if (diagnose) FixerLog.i("  sm prepare attempted");

        // Method 4: Invalidate FUSE dentry cache by touching parent
        for (String type : DIR_TYPES) {
            Shell.cmd("ls '" + FUSE + "/" + type + "/' > /dev/null 2>&1").exec();
        }
        if (diagnose) FixerLog.i("  FUSE parent listing done");
    }

    // ========== FORCE STOP ==========

    public static void forceStopPackage(String pkg) {
        Shell.cmd("am force-stop '" + pkg + "'").exec();
        FixerLog.i("Force stopped: " + pkg);
    }

    // ========== MEDIA RESCAN ==========

    public static void triggerMediaRescan() {
        Shell.cmd(
            "content call --uri content://media/"
            + " --method scan_volume --arg external_primary"
        ).exec();
    }

    // ========== FIX ALL (ONLY BROKEN APPS) ==========

    public static List<FixResult> fixAll(Context ctx) {
        List<FixResult> results = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0));

        int fixed = 0;
        int skipped = 0;
        List<String> fixedPkgs = new ArrayList<>();

        for (ApplicationInfo app : apps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            String pkg = app.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;

            // ONLY fix if directory is MISSING
            if (!needsFix(pkg)) {
                skipped++;
                continue;
            }

            FixerLog.i("BROKEN: " + pkg + " -> fixing...");
            FixResult r = fixPackage(pkg);
            results.add(r);
            if (r.success) {
                fixed++;
                fixedPkgs.add(pkg);
            }
        }

        // Force stop all fixed apps
        for (String pkg : fixedPkgs) {
            forceStopPackage(pkg);
        }

        if (!fixedPkgs.isEmpty()) {
            triggerMediaRescan();
        }

        FixerLog.i("Done: " + fixed + " fixed, " + skipped + " already OK (skipped)");
        return results;
    }

    // ========== DIAGNOSE ==========

    public static void diagnosePackage(Context ctx, String pkg) {
        FixerLog.divider();
        FixerLog.i("=== FULL DIAGNOSIS: " + pkg + " ===");

        // System info
        Shell.Result sdkRes = Shell.cmd("getprop ro.build.version.sdk").exec();
        Shell.Result romRes = Shell.cmd("getprop ro.build.display.id").exec();
        FixerLog.i("SDK: " + join(sdkRes.getOut()));
        FixerLog.i("ROM: " + join(romRes.getOut()));

        // SELinux
        Shell.Result seRes = Shell.cmd("getenforce").exec();
        FixerLog.i("SELinux: " + join(seRes.getOut()));

        // Root method
        Shell.Result magiskRes = Shell.cmd("magisk -v 2>/dev/null").exec();
        Shell.Result ksuRes = Shell.cmd(
            "ksud --version 2>/dev/null; ksu --version 2>/dev/null"
        ).exec();
        FixerLog.i("Magisk: " + join(magiskRes.getOut()));
        FixerLog.i("KernelSU: " + join(ksuRes.getOut()));

        // Mounts
        Shell.Result fsRes = Shell.cmd("mount | grep emulated | head -5").exec();
        FixerLog.i("Mounts:");
        for (String line : fsRes.getOut())
            FixerLog.i("  " + line.trim());

        // AVC denials
        Shell.Result avcRes = Shell.cmd(
            "dmesg 2>/dev/null | grep 'avc.*denied' | grep -iE 'vold|media|fuse|sdcard|" 
            + pkg.replace(".", "\\.") + "' | tail -20"
            + " || logcat -d -b events -t 100 2>/dev/null | grep -i storage | tail -10"
            + " || echo 'Cannot read kernel/event logs'"
        ).exec();
        FixerLog.i("AVC/Storage events:");
        for (String line : avcRes.getOut())
            FixerLog.i("  " + line.trim());

        // Check app UID
        try {
            ApplicationInfo info = ctx.getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0));
            FixerLog.i("App UID: " + info.uid);
            FixerLog.i("App data dir: " + info.dataDir);
        } catch (Exception e) {
            FixerLog.e("Cannot get app info: " + e.getMessage());
        }

        // Check needs fix
        boolean needs = needsFix(pkg);
        FixerLog.i("Needs fix (missing dirs): " + (needs ? "YES" : "NO"));

        // Show current state of all paths
        FixerLog.i("=== CURRENT STATE ===");
        for (String type : DIR_TYPES) {
            logDirState("LOWER", LOWER + "/" + type + "/" + pkg);
            logDirState("FUSE", FUSE + "/" + type + "/" + pkg);
        }

        // Fix with diagnostics
        FixerLog.i("=== APPLYING FIX ===");
        fixPackage(pkg, true);

        // Force stop
        forceStopPackage(pkg);

        // Wait 3s
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

        // Final verification
        FixerLog.i("=== POST-FIX STATE (3s after force-stop) ===");
        for (String type : DIR_TYPES) {
            logDirState("LOWER-FINAL", LOWER + "/" + type + "/" + pkg);
            logDirState("FUSE-FINAL", FUSE + "/" + type + "/" + pkg);
        }

        // Final write tests
        FixerLog.i("=== FINAL WRITE TESTS ===");
        for (String type : DIR_TYPES) {
            boolean lower = testWrite(LOWER + "/" + type + "/" + pkg);
            boolean fuse = testWrite(FUSE + "/" + type + "/" + pkg);
            FixerLog.i("  " + type + ": lower=" + (lower ? "PASS" : "FAIL")
                    + " fuse=" + (fuse ? "PASS" : "FAIL"));
        }

        // Check if vold has any pending operations
        Shell.Result voldRes = Shell.cmd(
            "dumpsys vold 2>/dev/null | head -30"
        ).exec();
        FixerLog.i("Vold status:");
        for (String line : voldRes.getOut())
            FixerLog.i("  " + line.trim());

        FixerLog.divider();
    }

    // ========== HELPERS ==========

    private static boolean testWrite(String path) {
        String testFile = path + "/.sf_test_" + System.currentTimeMillis();
        Shell.Result w = Shell.cmd(
            "echo test > '" + testFile + "' && rm '" + testFile + "' && echo Y || echo N"
        ).exec();
        return !w.getOut().isEmpty() && w.getOut().get(w.getOut().size() - 1).equals("Y");
    }

    private static void logPackageInfo(String pkg) {
        Shell.Result permRes = Shell.cmd(
            "dumpsys package " + pkg
            + " | grep -E 'storage|STORAGE|READ_MEDIA|MANAGE|EXTERNAL|granted=true'"
            + " | head -20"
        ).exec();
        if (!permRes.getOut().isEmpty()) {
            FixerLog.d("  Permissions:");
            for (String line : permRes.getOut())
                FixerLog.d("    " + line.trim());
        }
    }

    private static void logDirState(String label, String path) {
        Shell.Result exists = Shell.cmd(
            "[ -d '" + path + "' ] && echo EXISTS || echo MISSING"
        ).exec();
        String status = exists.getOut().isEmpty() ? "UNKNOWN"
                : exists.getOut().get(0);

        if ("MISSING".equals(status)) {
            FixerLog.d("  [" + label + "] " + path + " -> MISSING");
            return;
        }

        Shell.Result ls = Shell.cmd("ls -laZd '" + path + "'").exec();
        String info = ls.getOut().isEmpty() ? "?" : ls.getOut().get(0).trim();
        FixerLog.d("  [" + label + "] " + info);
    }

    private static String join(List<String> list) {
        if (list == null || list.isEmpty()) return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (String s : list) sb.append(s).append(" ");
        return sb.toString().trim();
    }

    public static class FixResult {
        public String packageName;
        public boolean dataOk, obbOk, mediaOk, writeTestOk, success;

        FixResult(String pkg) { this.packageName = pkg; }

        @Override
        public String toString() {
            return (success ? "OK " : "FAIL ") + packageName
                    + " [data:" + (dataOk ? "ok" : "fail")
                    + " obb:" + (obbOk ? "ok" : "fail")
                    + " media:" + (mediaOk ? "ok" : "fail") + "]";
        }
    }
}
