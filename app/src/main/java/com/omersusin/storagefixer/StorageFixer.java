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

    // ========== GET APP UID ==========

    private static int getAppUid(Context ctx, String pkg) {
        try {
            ApplicationInfo info = ctx.getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0));
            return info.uid;
        } catch (PackageManager.NameNotFoundException e) {
            FixerLog.e("Package not found: " + pkg);
            return -1;
        }
    }

    // ========== CHECK IF NEEDS FIX ==========

    public static boolean needsFix(String pkg) {
        for (String type : DIR_TYPES) {
            String lowerPath = LOWER + "/" + type + "/" + pkg;

            // Check if directory exists on lower FS
            Shell.Result exists = Shell.cmd(
                "[ -d '" + lowerPath + "' ] && echo Y || echo N"
            ).exec();
            if (exists.getOut().isEmpty() || "N".equals(exists.getOut().get(0))) {
                FixerLog.d("  Needs fix: " + lowerPath + " MISSING");
                return true;
            }

            // Check if FUSE can see it
            String fusePath = FUSE + "/" + type + "/" + pkg;
            Shell.Result fuseExists = Shell.cmd(
                "[ -d '" + fusePath + "' ] && echo Y || echo N"
            ).exec();
            if (fuseExists.getOut().isEmpty() || "N".equals(fuseExists.getOut().get(0))) {
                FixerLog.d("  Needs fix: " + fusePath + " not on FUSE");
                return true;
            }
        }
        return false;
    }

    // ========== FIX PACKAGE ==========

    public static FixResult fixPackage(Context ctx, String pkg) {
        return fixPackage(ctx, pkg, false);
    }

    public static FixResult fixPackage(Context ctx, String pkg, boolean diagnose) {
        FixResult r = new FixResult(pkg);

        int uid = getAppUid(ctx, pkg);
        if (uid < 0) {
            FixerLog.e("Cannot fix " + pkg + ": UID not found");
            return r;
        }

        String owner = uid + ":" + uid;

        if (diagnose) {
            FixerLog.divider();
            FixerLog.i("DIAGNOSING: " + pkg);
            FixerLog.i("App UID: " + uid + " -> owner=" + owner);
            logPackageInfo(pkg);
        }

        // Fix each directory type
        r.dataOk = fixDir(LOWER + "/data/" + pkg, owner, diagnose);
        r.obbOk = fixDir(LOWER + "/obb/" + pkg, owner, diagnose);
        r.mediaOk = fixDir(LOWER + "/media/" + pkg, owner, diagnose);

        if (diagnose) {
            // Verify from FUSE side
            FixerLog.i("=== FUSE VERIFICATION ===");
            for (String type : DIR_TYPES) {
                logDirState("FUSE", FUSE + "/" + type + "/" + pkg);
            }

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
                + " media:" + (r.mediaOk ? "ok" : "fail")
                + " uid:" + uid + "]");

        if (diagnose) FixerLog.divider();
        return r;
    }

    private static boolean fixDir(String path, String owner, boolean diagnose) {
        if (diagnose) logDirState("BEFORE", path);

        // Ensure parent exists
        String parent = path.substring(0, path.lastIndexOf('/'));
        Shell.cmd("mkdir -p '" + parent + "'").exec();

        // Create directory
        Shell.Result mk = Shell.cmd("mkdir -p '" + path + "'").exec();
        if (!mk.isSuccess()) {
            if (diagnose) for (String e : mk.getErr()) FixerLog.e("  mkdir ERR: " + e);
            return false;
        }

        // Set ownership to APP UID (NOT media_rw!)
        Shell.cmd("chown " + owner + " '" + path + "'").exec();

        // Set permissions to 777
        Shell.cmd("chmod 777 '" + path + "'").exec();

        // Use restorecon instead of chcon!
        // This lets Android's sepolicy assign the correct context
        // with proper MLS categories for the app's UID
        Shell.Result restorecon = Shell.cmd("restorecon -R '" + path + "'").exec();
        if (!restorecon.isSuccess() && diagnose) {
            FixerLog.w("  restorecon failed, falling back to chcon");
            Shell.cmd("chcon u:object_r:media_rw_data_file:s0 '" + path + "'").exec();
        }

        // Recursive ownership fix
        Shell.cmd("chown -R " + owner + " '" + path + "' 2>/dev/null").exec();

        if (diagnose) logDirState("AFTER", path);

        Shell.Result verify = Shell.cmd(
            "[ -d '" + path + "' ] && echo OK || echo FAIL"
        ).exec();
        return !verify.getOut().isEmpty() && "OK".equals(verify.getOut().get(0));
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

    // ========== FIX ALL ==========

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

            if (!needsFix(pkg)) {
                skipped++;
                continue;
            }

            FixerLog.i("BROKEN: " + pkg + " -> fixing...");
            FixResult r = fixPackage(ctx, pkg);
            results.add(r);
            if (r.success) {
                fixed++;
                fixedPkgs.add(pkg);
            }
        }

        for (String pkg : fixedPkgs) {
            forceStopPackage(pkg);
        }

        if (!fixedPkgs.isEmpty()) {
            triggerMediaRescan();
        }

        FixerLog.i("Done: " + fixed + " fixed, " + skipped + " already OK");
        return results;
    }

    // ========== DIAGNOSE ==========

    public static void diagnosePackage(Context ctx, String pkg) {
        FixerLog.divider();
        FixerLog.i("=== FULL DIAGNOSIS: " + pkg + " ===");

        Shell.Result sdkRes = Shell.cmd("getprop ro.build.version.sdk").exec();
        Shell.Result romRes = Shell.cmd("getprop ro.build.display.id").exec();
        FixerLog.i("SDK: " + join(sdkRes.getOut()));
        FixerLog.i("ROM: " + join(romRes.getOut()));

        Shell.Result seRes = Shell.cmd("getenforce").exec();
        FixerLog.i("SELinux: " + join(seRes.getOut()));

        Shell.Result ksuRes = Shell.cmd(
            "ksud --version 2>/dev/null; magisk -v 2>/dev/null"
        ).exec();
        FixerLog.i("Root: " + join(ksuRes.getOut()));

        // Mount info
        Shell.Result fsRes = Shell.cmd("mount | grep emulated | head -5").exec();
        FixerLog.i("Mounts:");
        for (String line : fsRes.getOut())
            FixerLog.i("  " + line.trim());

        // AVC denials
        Shell.Result avcRes = Shell.cmd(
            "dmesg 2>/dev/null | grep 'avc.*denied' | grep -iE 'vold|media|fuse|"
            + pkg.replace(".", "\\.") + "' | tail -15"
            + " || echo 'Cannot read dmesg'"
        ).exec();
        FixerLog.i("AVC denials:");
        for (String line : avcRes.getOut())
            FixerLog.i("  " + line.trim());

        // App info
        int uid = getAppUid(ctx, pkg);
        FixerLog.i("App UID: " + uid);

        // Needs fix?
        boolean needs = needsFix(pkg);
        FixerLog.i("Needs fix: " + (needs ? "YES" : "NO"));

        // Current state
        FixerLog.i("=== CURRENT STATE ===");
        for (String type : DIR_TYPES) {
            logDirState("LOWER", LOWER + "/" + type + "/" + pkg);
            logDirState("FUSE", FUSE + "/" + type + "/" + pkg);
        }

        // Apply fix
        FixerLog.i("=== APPLYING FIX ===");
        fixPackage(ctx, pkg, true);

        // Force stop
        forceStopPackage(pkg);

        // Wait
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

        // Final state
        FixerLog.i("=== POST-FIX (3s after force-stop) ===");
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

        FixerLog.divider();
    }

    // ========== HELPERS ==========

    private static boolean testWrite(String path) {
        String testFile = path + "/.sf_test_" + System.currentTimeMillis();
        Shell.Result w = Shell.cmd(
            "echo t > '" + testFile + "' && rm '" + testFile + "' && echo Y || echo N"
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
