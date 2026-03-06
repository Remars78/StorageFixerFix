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
    private static final String DIR_PERM = "777";
    private static final String FILE_PERM = "666";

    private static final String[] SUBDIRS = {
        "cache", "files", "no_backup", "shared_prefs",
        "databases", "code_cache"
    };

    private static final String[] DIR_TYPES = {"data", "obb", "media"};

    public static boolean isRootAvailable() {
        return Shell.getShell().isRoot();
    }

    public static boolean isFuseReady() {
        return Shell.cmd("ls " + FUSE + "/").exec().isSuccess();
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

    // ========== CHECK IF APP NEEDS FIXING ==========

    private static boolean needsFix(String pkg) {
        for (String type : DIR_TYPES) {
            String lowerPath = LOWER + "/" + type + "/" + pkg;
            String fusePath = FUSE + "/" + type + "/" + pkg;

            // Check if directory exists on lower FS
            Shell.Result exists = Shell.cmd(
                "[ -d '" + lowerPath + "' ] && echo Y || echo N"
            ).exec();
            if (exists.getOut().isEmpty() || "N".equals(exists.getOut().get(0))) {
                FixerLog.d("  Needs fix: " + lowerPath + " missing");
                return true;
            }

            // Check ownership
            Shell.Result owner = Shell.cmd(
                "stat -c '%U:%G' '" + lowerPath + "' 2>/dev/null"
            ).exec();
            if (!owner.getOut().isEmpty()) {
                String o = owner.getOut().get(0).trim();
                if (!o.equals("media_rw:media_rw")) {
                    FixerLog.d("  Needs fix: " + lowerPath + " owner=" + o);
                    return true;
                }
            }

            // Check permissions
            Shell.Result perm = Shell.cmd(
                "stat -c '%a' '" + lowerPath + "' 2>/dev/null"
            ).exec();
            if (!perm.getOut().isEmpty()) {
                String p = perm.getOut().get(0).trim();
                if (!p.equals("777")) {
                    FixerLog.d("  Needs fix: " + lowerPath + " perm=" + p);
                    return true;
                }
            }

            // Check FUSE visibility
            Shell.Result fuseExists = Shell.cmd(
                "[ -d '" + fusePath + "' ] && echo Y || echo N"
            ).exec();
            if (fuseExists.getOut().isEmpty() || "N".equals(fuseExists.getOut().get(0))) {
                FixerLog.d("  Needs fix: " + fusePath + " not visible on FUSE");
                return true;
            }
        }
        return false;
    }

    // ========== FIX PACKAGE ==========

    public static FixResult fixPackage(String pkg) {
        return fixPackage(pkg, false);
    }

    public static FixResult fixPackage(String pkg, boolean diagnose) {
        FixResult r = new FixResult(pkg);

        if (diagnose) {
            FixerLog.divider();
            FixerLog.i("DIAGNOSING: " + pkg);
            logPackageInfo(pkg);
            logParentDirs();
        }

        // Fix each type on BOTH lower FS and FUSE
        r.dataOk = fixBothPaths("data", pkg, diagnose);
        r.obbOk = fixBothPaths("obb", pkg, diagnose);
        r.mediaOk = fixBothPaths("media", pkg, diagnose);

        // Create common subdirs for data and media
        fixSubDirs(LOWER + "/data/" + pkg, diagnose);
        fixSubDirs(LOWER + "/media/" + pkg, diagnose);
        fixSubDirs(FUSE + "/data/" + pkg, diagnose);
        fixSubDirs(FUSE + "/media/" + pkg, diagnose);

        // Verify write tests
        if (diagnose) {
            FixerLog.i("=== WRITE TESTS ===");
            r.writeTestOk = testWrite(LOWER + "/data/" + pkg);
            FixerLog.i("  Lower data write: " + (r.writeTestOk ? "PASS" : "FAIL"));
            boolean fuseWrite = testWrite(FUSE + "/data/" + pkg);
            FixerLog.i("  FUSE data write: " + (fuseWrite ? "PASS" : "FAIL"));
            boolean lowerMedia = testWrite(LOWER + "/media/" + pkg);
            FixerLog.i("  Lower media write: " + (lowerMedia ? "PASS" : "FAIL"));
            boolean fuseMedia = testWrite(FUSE + "/media/" + pkg);
            FixerLog.i("  FUSE media write: " + (fuseMedia ? "PASS" : "FAIL"));
        }

        r.success = r.dataOk && r.obbOk;

        FixerLog.i((r.success ? "OK" : "FAIL") + " " + pkg
                + " [data:" + (r.dataOk ? "ok" : "fail")
                + " obb:" + (r.obbOk ? "ok" : "fail")
                + " media:" + (r.mediaOk ? "ok" : "fail") + "]");

        if (diagnose) FixerLog.divider();
        return r;
    }

    private static boolean fixBothPaths(String type, String pkg, boolean diagnose) {
        String lowerPath = LOWER + "/" + type + "/" + pkg;
        String fusePath = FUSE + "/" + type + "/" + pkg;

        if (diagnose) {
            logDirState("LOWER-BEFORE", lowerPath);
            logDirState("FUSE-BEFORE", fusePath);
        }

        // Fix on lower filesystem first
        boolean lowerOk = fixSingleDir(lowerPath, diagnose);

        // Also fix on FUSE path (some ROMs need both)
        boolean fuseOk = fixSingleDir(fusePath, diagnose);

        if (diagnose) {
            logDirState("LOWER-AFTER", lowerPath);
            logDirState("FUSE-AFTER", fusePath);
        }

        return lowerOk || fuseOk;
    }

    private static boolean fixSingleDir(String path, boolean diagnose) {
        String parentPath = path.substring(0, path.lastIndexOf('/'));

        // Ensure parent exists
        Shell.cmd("mkdir -p '" + parentPath + "'").exec();

        // Create directory
        Shell.Result mkRes = Shell.cmd("mkdir -p '" + path + "'").exec();
        if (!mkRes.isSuccess()) {
            if (diagnose) for (String e : mkRes.getErr()) FixerLog.e("  mkdir ERR: " + e);
            return false;
        }

        // Set ownership FIRST
        Shell.cmd("chown " + OWNER + " '" + path + "'").exec();

        // Set permissions to 777 (matching manual fix)
        Shell.cmd("chmod " + DIR_PERM + " '" + path + "'").exec();

        // Set SELinux context
        Shell.Result chconRes = Shell.cmd("chcon '" + SECTX + "' '" + path + "'").exec();
        if (!chconRes.isSuccess()) {
            // Try alternatives
            String[] alts = {
                "u:object_r:media_rw_data_file:s0",
                "u:object_r:media_data_file:s0",
                "u:object_r:sdcardfs:s0",
                "u:object_r:fuse:s0",
            };
            for (String alt : alts) {
                if (Shell.cmd("chcon '" + alt + "' '" + path + "'").exec().isSuccess()) {
                    if (diagnose) FixerLog.i("  chcon OK with: " + alt);
                    break;
                }
            }
        }

        // Recursive fix for existing contents
        Shell.cmd(String.join("; ",
            "find '" + path + "' -type d -exec chmod " + DIR_PERM + " {} + 2>/dev/null",
            "find '" + path + "' -type f -exec chmod " + FILE_PERM + " {} + 2>/dev/null",
            "chown -R " + OWNER + " '" + path + "' 2>/dev/null",
            "chcon -R '" + SECTX + "' '" + path + "' 2>/dev/null"
        )).exec();

        Shell.Result verify = Shell.cmd(
            "[ -d '" + path + "' ] && echo OK || echo FAIL"
        ).exec();
        return !verify.getOut().isEmpty() && "OK".equals(verify.getOut().get(0));
    }

    private static void fixSubDirs(String parentPath, boolean diagnose) {
        for (String sub : SUBDIRS) {
            String subPath = parentPath + "/" + sub;
            Shell.cmd(String.join(" && ",
                "mkdir -p '" + subPath + "'",
                "chown " + OWNER + " '" + subPath + "'",
                "chmod " + DIR_PERM + " '" + subPath + "'",
                "chcon " + SECTX + " '" + subPath + "' 2>/dev/null")
            ).exec();
        }
        if (diagnose) FixerLog.d("  Subdirs created in " + parentPath);
    }

    // ========== FORCE STOP ==========

    public static void forceStopPackage(String pkg) {
        Shell.cmd("am force-stop '" + pkg + "'").exec();
        FixerLog.i("Force stopped: " + pkg);
    }

    // ========== MEDIA RESCAN ==========

    public static void triggerMediaRescan(String pkg) {
        Shell.cmd(
            "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE"
            + " -d 'file:///storage/emulated/0/Android/data/" + pkg + "'"
        ).exec();
        Shell.cmd(
            "content call --uri content://media/"
            + " --method scan_volume --arg external_primary"
        ).exec();
        FixerLog.i("Media rescan triggered for " + pkg);
    }

    // ========== FIX ALL (only broken) ==========

    public static List<FixResult> fixAll(Context ctx) {
        List<FixResult> results = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0));

        int fixed = 0;
        int skipped = 0;
        List<String> fixedPackages = new ArrayList<>();

        for (ApplicationInfo app : apps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            String pkg = app.packageName;

            // Skip our own app
            if (pkg.equals(ctx.getPackageName())) continue;

            // Check if fix is needed
            if (!needsFix(pkg)) {
                skipped++;
                continue;
            }

            FixerLog.i("Fixing: " + pkg);
            FixResult r = fixPackage(pkg);
            results.add(r);
            if (r.success) {
                fixed++;
                fixedPackages.add(pkg);
            }
        }

        // Force stop all fixed apps
        for (String pkg : fixedPackages) {
            forceStopPackage(pkg);
        }

        // Trigger media rescan once
        if (!fixedPackages.isEmpty()) {
            triggerMediaRescan("all");
        }

        FixerLog.i("Done: " + fixed + " fixed, " + skipped + " skipped (already OK)");
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

        // Mount info
        Shell.Result fsRes = Shell.cmd("mount | grep emulated | head -5").exec();
        FixerLog.i("Storage mounts:");
        for (String line : fsRes.getOut())
            FixerLog.i("  " + line.trim());

        // Root method
        Shell.Result magiskRes = Shell.cmd("magisk -v 2>/dev/null").exec();
        Shell.Result ksuRes = Shell.cmd(
            "ksud --version 2>/dev/null; ksu --version 2>/dev/null").exec();
        FixerLog.i("Magisk: " + join(magiskRes.getOut()));
        FixerLog.i("KernelSU: " + join(ksuRes.getOut()));

        // AVC denials
        Shell.Result avcRes = Shell.cmd(
            "dmesg | grep 'avc.*denied' | tail -20 2>/dev/null"
            + " || echo 'Cannot read dmesg'"
        ).exec();
        FixerLog.i("Recent AVC denials:");
        for (String line : avcRes.getOut())
            FixerLog.i("  " + line.trim());

        // Check needs fix
        boolean needs = needsFix(pkg);
        FixerLog.i("Needs fix: " + (needs ? "YES" : "NO"));

        // Fix with diagnostics
        fixPackage(pkg, true);

        // Force stop
        forceStopPackage(pkg);

        // Rescan
        triggerMediaRescan(pkg);

        // Wait for FUSE to update
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

        // Final verification
        FixerLog.i("=== FINAL VERIFICATION (3s after fix + force-stop) ===");
        for (String type : DIR_TYPES) {
            logDirState("LOWER-FINAL", LOWER + "/" + type + "/" + pkg);
            logDirState("FUSE-FINAL", FUSE + "/" + type + "/" + pkg);
        }

        // Write test from FUSE
        boolean fuseWrite = testWrite(FUSE + "/data/" + pkg);
        FixerLog.i("FUSE write test after fix: " + (fuseWrite ? "PASS" : "FAIL"));

        FixerLog.divider();
    }

    // ========== HELPERS ==========

    private static boolean testWrite(String path) {
        String testFile = path + "/.storagefixer_test";
        Shell.Result w = Shell.cmd(
            "echo test > '" + testFile + "' && cat '" + testFile
            + "' && rm '" + testFile + "'"
        ).exec();
        return w.isSuccess();
    }

    private static void logPackageInfo(String pkg) {
        Shell.Result uidRes = Shell.cmd(
            "dumpsys package " + pkg + " | grep userId= | head -1").exec();
        for (String line : uidRes.getOut())
            FixerLog.d("  UID: " + line.trim());

        Shell.Result permRes = Shell.cmd(
            "dumpsys package " + pkg
            + " | grep -E 'storage|STORAGE|READ_MEDIA|MANAGE|EXTERNAL'"
        ).exec();
        if (!permRes.getOut().isEmpty()) {
            FixerLog.d("  Storage permissions:");
            for (String line : permRes.getOut())
                FixerLog.d("    " + line.trim());
        }
    }

    private static void logParentDirs() {
        String[] parents = {
            LOWER + "/data", LOWER + "/obb", LOWER + "/media",
            FUSE + "/data", FUSE + "/obb", FUSE + "/media"
        };
        for (String p : parents) {
            Shell.Result res = Shell.cmd(
                "ls -laZd '" + p + "' 2>/dev/null || echo 'MISSING: " + p + "'"
            ).exec();
            for (String line : res.getOut())
                FixerLog.d("  Parent: " + line.trim());
        }
    }

    private static void logDirState(String label, String path) {
        FixerLog.d("  [" + label + "] " + path);

        Shell.Result exists = Shell.cmd(
            "[ -d '" + path + "' ] && echo EXISTS || echo MISSING").exec();
        String status = exists.getOut().isEmpty() ? "UNKNOWN"
                : exists.getOut().get(0);
        FixerLog.d("    Status: " + status);
        if ("MISSING".equals(status)) return;

        Shell.Result lsRes = Shell.cmd("ls -laZd '" + path + "'").exec();
        for (String line : lsRes.getOut())
            FixerLog.d("    " + line.trim());

        Shell.Result subRes = Shell.cmd(
            "ls -laZ '" + path + "/' 2>/dev/null | head -10").exec();
        if (!subRes.getOut().isEmpty()) {
            FixerLog.d("    Contents:");
            for (String line : subRes.getOut())
                FixerLog.d("      " + line.trim());
        }
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
