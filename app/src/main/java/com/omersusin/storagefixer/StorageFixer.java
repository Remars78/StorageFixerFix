package com.omersusin.storagefixer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.topjohnwu.superuser.Shell;

import java.util.ArrayList;
import java.util.List;

public class StorageFixer {

    // Lower filesystem path - bypasses FUSE entirely
    private static final String LOWER = "/data/media/0/Android";
    // FUSE path - only used for verification
    private static final String FUSE = "/storage/emulated/0/Android";

    private static final String OWNER = "media_rw:media_rw";
    private static final String SECTX = "u:object_r:media_rw_data_file:s0";
    private static final String DIR_PERM = "2771";
    private static final String FILE_PERM = "660";

    private static final String[] SUBDIRS = {
        "cache", "files", "no_backup", "shared_prefs",
        "databases", "code_cache"
    };

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

        // Fix on LOWER filesystem (bypasses FUSE)
        String dataPath = LOWER + "/data/" + pkg;
        if (diagnose) logDirState("BEFORE", dataPath);
        r.dataOk = fixDir(dataPath, diagnose);
        fixSubDirs(dataPath, diagnose);
        if (diagnose) logDirState("AFTER", dataPath);

        String obbPath = LOWER + "/obb/" + pkg;
        if (diagnose) logDirState("BEFORE", obbPath);
        r.obbOk = fixDir(obbPath, diagnose);
        if (diagnose) logDirState("AFTER", obbPath);

        String mediaPath = LOWER + "/media/" + pkg;
        if (diagnose) logDirState("BEFORE", mediaPath);
        r.mediaOk = fixDir(mediaPath, diagnose);
        fixSubDirs(mediaPath, diagnose);
        if (diagnose) logDirState("AFTER", mediaPath);

        // Verify from FUSE side too
        if (diagnose) {
            FixerLog.i("=== FUSE VERIFICATION ===");
            logDirState("FUSE", FUSE + "/data/" + pkg);
            logDirState("FUSE", FUSE + "/obb/" + pkg);
            logDirState("FUSE", FUSE + "/media/" + pkg);

            r.writeTestOk = testWrite(dataPath);
            FixerLog.i("Write test lower: " + (r.writeTestOk ? "PASS" : "FAIL"));
            boolean fuseWrite = testWrite(FUSE + "/data/" + pkg);
            FixerLog.i("Write test FUSE: " + (fuseWrite ? "PASS" : "FAIL"));
        }

        r.success = r.dataOk && r.obbOk;

        String status = (r.success ? "OK" : "FAIL") + " " + pkg
                + " [data:" + (r.dataOk ? "ok" : "fail")
                + " obb:" + (r.obbOk ? "ok" : "fail")
                + " media:" + (r.mediaOk ? "ok" : "fail") + "]";
        FixerLog.i(status);

        if (diagnose) FixerLog.divider();
        return r;
    }

    private static boolean fixDir(String path, boolean diagnose) {
        // Detect correct SELinux context from parent
        String parentPath = path.substring(0, path.lastIndexOf('/'));
        String detectedCtx = detectContext(parentPath);
        String ctx = (detectedCtx != null) ? detectedCtx : SECTX;

        if (diagnose) FixerLog.d("  Parent context: " + ctx);

        // Ensure parent exists
        Shell.cmd("mkdir -p '" + parentPath + "'").exec();

        // Create and set permissions on lower filesystem
        Shell.Result mkRes = Shell.cmd("mkdir -p '" + path + "'").exec();
        if (!mkRes.isSuccess()) {
            if (diagnose) for (String e : mkRes.getErr()) FixerLog.e("  mkdir ERR: " + e);
            return false;
        }

        // Set ownership first (important for new dirs)
        Shell.Result chownRes = Shell.cmd("chown " + OWNER + " '" + path + "'").exec();
        if (!chownRes.isSuccess() && diagnose) {
            for (String e : chownRes.getErr()) FixerLog.e("  chown ERR: " + e);
        }

        // Set permissions
        Shell.Result chmodRes = Shell.cmd("chmod " + DIR_PERM + " '" + path + "'").exec();
        if (!chmodRes.isSuccess() && diagnose) {
            for (String e : chmodRes.getErr()) FixerLog.e("  chmod ERR: " + e);
        }

        // Set SELinux context
        Shell.Result chconRes = Shell.cmd("chcon '" + ctx + "' '" + path + "'").exec();
        if (!chconRes.isSuccess()) {
            if (diagnose) FixerLog.w("  chcon failed with " + ctx + ", trying alternatives...");
            String[] alts = {
                "u:object_r:media_rw_data_file:s0",
                "u:object_r:media_data_file:s0",
                "u:object_r:sdcardfs:s0",
                "u:object_r:fuse:s0",
            };
            for (String alt : alts) {
                Shell.Result tryRes = Shell.cmd("chcon '" + alt + "' '" + path + "'").exec();
                if (tryRes.isSuccess()) {
                    if (diagnose) FixerLog.i("  chcon OK with: " + alt);
                    ctx = alt;
                    break;
                }
            }
        }

        // Recursive fix for existing contents
        Shell.cmd(String.join("; ",
            "find '" + path + "' -type d -exec chmod " + DIR_PERM + " {} + 2>/dev/null",
            "find '" + path + "' -type f -exec chmod " + FILE_PERM + " {} + 2>/dev/null",
            "chown -R " + OWNER + " '" + path + "' 2>/dev/null",
            "chcon -R '" + ctx + "' '" + path + "' 2>/dev/null"
        )).exec();

        // Verify
        Shell.Result verify = Shell.cmd(
            "[ -d '" + path + "' ] && echo OK || echo FAIL"
        ).exec();
        return !verify.getOut().isEmpty() && "OK".equals(verify.getOut().get(0));
    }

    private static boolean fixDir(String path) {
        return fixDir(path, false);
    }

    private static void fixSubDirs(String parentPath, boolean diagnose) {
        for (String sub : SUBDIRS) {
            String subPath = parentPath + "/" + sub;
            Shell.cmd(String.join(" && ",
                "mkdir -p '" + subPath + "'",
                "chown " + OWNER + " '" + subPath + "'",
                "chmod " + DIR_PERM + " '" + subPath + "'",
                "chcon " + SECTX + " '" + subPath + "'")
            ).exec();
            if (diagnose) FixerLog.d("  Subdir: " + sub + " created");
        }
    }

    private static String detectContext(String path) {
        Shell.Result res = Shell.cmd(
            "ls -Zd '" + path + "' 2>/dev/null | awk '{print $1}'"
        ).exec();
        if (!res.getOut().isEmpty()) {
            String ctx = res.getOut().get(0).trim();
            if (ctx.startsWith("u:")) return ctx;
        }
        return null;
    }

    public static void triggerMediaRescan(String pkg) {
        FixerLog.i("Triggering media rescan for " + pkg);
        // Method 1: scan specific paths
        Shell.cmd(
            "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE"
            + " -d 'file:///storage/emulated/0/Android/data/" + pkg + "'"
        ).exec();
        // Method 2: force MediaProvider to rescan volume
        Shell.cmd(
            "content call --uri content://media/"
            + " --method scan_volume --arg external_primary"
        ).exec();
        FixerLog.i("Media rescan triggered");
    }

    private static boolean testWrite(String path) {
        String testFile = path + "/.storagefixer_test";
        Shell.Result w = Shell.cmd(
            "echo test > '" + testFile + "' && cat '" + testFile
            + "' && rm '" + testFile + "'"
        ).exec();
        return w.isSuccess();
    }

    public static void forceStopPackage(String pkg) {
        Shell.cmd("am force-stop '" + pkg + "'").exec();
        FixerLog.i("Force stopped: " + pkg);
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
            "ls -laZ '" + path + "/' 2>/dev/null | head -15").exec();
        if (!subRes.getOut().isEmpty()) {
            FixerLog.d("    Contents:");
            for (String line : subRes.getOut())
                FixerLog.d("      " + line.trim());
        }
    }

    public static void diagnosePackage(Context ctx, String pkg) {
        FixerLog.divider();
        FixerLog.i("=== FULL DIAGNOSIS: " + pkg + " ===");

        // System info
        Shell.Result sdkRes = Shell.cmd("getprop ro.build.version.sdk").exec();
        Shell.Result romRes = Shell.cmd("getprop ro.build.display.id").exec();
        FixerLog.i("SDK: " + join(sdkRes.getOut()));
        FixerLog.i("ROM: " + join(romRes.getOut()));

        // FUSE info
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

        // SELinux status
        Shell.Result seRes = Shell.cmd("getenforce").exec();
        FixerLog.i("SELinux: " + join(seRes.getOut()));

        // Check for avc denials related to this package
        Shell.Result avcRes = Shell.cmd(
            "dmesg | grep -i 'avc.*denied' | grep -i '"
            + pkg.replace(".", "\\.") + "' | tail -10 2>/dev/null"
            + " || echo 'No AVC denials found (or no access to dmesg)'"
        ).exec();
        FixerLog.i("SELinux denials:");
        for (String line : avcRes.getOut())
            FixerLog.i("  " + line.trim());

        // Check for general storage AVC denials
        Shell.Result avcStorageRes = Shell.cmd(
            "dmesg | grep -i 'avc.*denied.*media' | tail -10 2>/dev/null"
            + " || echo 'No media AVC denials'"
        ).exec();
        FixerLog.i("Media AVC denials:");
        for (String line : avcStorageRes.getOut())
            FixerLog.i("  " + line.trim());

        // Now do the fix with full diagnostics
        fixPackage(pkg, true);

        // Trigger rescan
        triggerMediaRescan(pkg);

        // Wait and verify FUSE picked it up
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        FixerLog.i("=== POST-RESCAN FUSE VERIFICATION ===");
        logDirState("FUSE-FINAL", FUSE + "/data/" + pkg);
        logDirState("FUSE-FINAL", FUSE + "/obb/" + pkg);
        logDirState("FUSE-FINAL", FUSE + "/media/" + pkg);

        FixerLog.divider();
    }

    public static List<FixResult> fixAll(Context ctx) {
        List<FixResult> results = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0));

        int ok = 0;
        for (ApplicationInfo app : apps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            FixResult r = fixPackage(app.packageName);
            results.add(r);
            if (r.success) ok++;
        }

        // Trigger single volume rescan after fixing all
        triggerMediaRescan("*");
        FixerLog.i("Scan done: " + ok + "/" + results.size() + " fixed");
        return results;
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
                    + " media:" + (mediaOk ? "ok" : "fail")
                    + " write:" + (writeTestOk ? "ok" : "fail") + "]";
        }
    }
}
