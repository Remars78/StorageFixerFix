package com.omersusin.storagefixer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.topjohnwu.superuser.Shell;

import java.util.ArrayList;
import java.util.List;

public class StorageFixer {

    private static final String BASE = "/storage/emulated/0/Android";
    private static final String OWNER = "media_rw:media_rw";
    private static final String DIR_PERM = "777";
    private static final String FILE_PERM = "666";

    private static final String[] SUBDIRS = {
        "cache", "files", "no_backup", "shared_prefs",
        "databases", "code_cache"
    };

    // Different dirs may need different SELinux contexts
    private static final String[][] DIR_CONFIGS = {
        {"data",  "u:object_r:media_rw_data_file:s0"},
        {"obb",   "u:object_r:media_rw_data_file:s0"},
        {"media", "u:object_r:media_rw_data_file:s0"},
    };

    public static boolean isRootAvailable() {
        return Shell.getShell().isRoot();
    }

    public static boolean isFuseReady() {
        return Shell.cmd("ls " + BASE + "/").exec().isSuccess();
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
            // Log parent dir contexts first
            logParentDirs(diagnose);
        }

        // Fix Android/data/<pkg>
        String dataPath = BASE + "/data/" + pkg;
        if (diagnose) logDirState("BEFORE", dataPath);
        r.dataOk = fixDirSmart(dataPath, "data", diagnose);
        fixSubDirs(dataPath, diagnose);
        if (diagnose) logDirState("AFTER", dataPath);

        // Fix Android/obb/<pkg>
        String obbPath = BASE + "/obb/" + pkg;
        if (diagnose) logDirState("BEFORE", obbPath);
        r.obbOk = fixDirSmart(obbPath, "obb", diagnose);
        if (diagnose) logDirState("AFTER", obbPath);

        // Fix Android/media/<pkg>
        String mediaPath = BASE + "/media/" + pkg;
        if (diagnose) logDirState("BEFORE", mediaPath);
        r.mediaOk = fixDirSmart(mediaPath, "media", diagnose);
        fixSubDirs(mediaPath, diagnose);
        if (diagnose) logDirState("AFTER", mediaPath);

        // Write tests
        if (diagnose) {
            r.writeTestOk = testWrite(dataPath);
            FixerLog.i("Write test data: " + (r.writeTestOk ? "PASS" : "FAIL"));
            boolean obbWrite = testWrite(obbPath);
            FixerLog.i("Write test obb: " + (obbWrite ? "PASS" : "FAIL"));
            boolean mediaWrite = testWrite(mediaPath);
            FixerLog.i("Write test media: " + (mediaWrite ? "PASS" : "FAIL"));
        }

        // Success = data AND obb must work. Media is bonus.
        r.success = r.dataOk && r.obbOk;

        String status = (r.success ? "V" : "X") + " " + pkg
                + " [data:" + (r.dataOk ? "ok" : "fail")
                + " obb:" + (r.obbOk ? "ok" : "fail")
                + " media:" + (r.mediaOk ? "ok" : "fail") + "]";
        FixerLog.i(status);

        if (diagnose) FixerLog.divider();
        return r;
    }

    private static void logParentDirs(boolean diagnose) {
        if (!diagnose) return;
        String[] parents = {
            BASE + "/data", BASE + "/obb", BASE + "/media"
        };
        for (String p : parents) {
            Shell.Result res = Shell.cmd(
                "ls -laZd '" + p + "' 2>/dev/null || echo 'MISSING: " + p + "'"
            ).exec();
            for (String line : res.getOut()) {
                FixerLog.d("  Parent: " + line.trim());
            }
        }
    }

    private static boolean fixDirSmart(String path, String type, boolean diagnose) {
        // First detect the correct SELinux context from parent
        String parentPath = path.substring(0, path.lastIndexOf('/'));
        String detectedCtx = detectContext(parentPath);

        if (diagnose) {
            FixerLog.d("  Detected parent context: " + detectedCtx);
        }

        // Ensure parent exists first
        Shell.Result parentCheck = Shell.cmd(
            "[ -d '" + parentPath + "' ] && echo EXISTS || echo MISSING"
        ).exec();
        String parentStatus = parentCheck.getOut().isEmpty() ? "UNKNOWN"
                : parentCheck.getOut().get(0);

        if ("MISSING".equals(parentStatus)) {
            if (diagnose) FixerLog.w("  Parent missing: " + parentPath + ", creating...");
            Shell.cmd("mkdir -p '" + parentPath + "'").exec();
        }

        // Create directory
        Shell.Result mkResult = Shell.cmd("mkdir -p '" + path + "'").exec();
        if (!mkResult.isSuccess()) {
            if (diagnose) {
                for (String e : mkResult.getErr()) FixerLog.e("  mkdir ERR: " + e);
            }
            return false;
        }

        // chmod
        Shell.Result chmodResult = Shell.cmd("chmod " + DIR_PERM + " '" + path + "'").exec();
        if (!chmodResult.isSuccess() && diagnose) {
            for (String e : chmodResult.getErr()) FixerLog.e("  chmod ERR: " + e);
        }

        // chown
        Shell.Result chownResult = Shell.cmd("chown " + OWNER + " '" + path + "'").exec();
        if (!chownResult.isSuccess() && diagnose) {
            for (String e : chownResult.getErr()) FixerLog.e("  chown ERR: " + e);
        }

        // chcon - use detected context, fallback to default
        String ctx = (detectedCtx != null && !detectedCtx.isEmpty())
                ? detectedCtx : "u:object_r:media_rw_data_file:s0";
        Shell.Result chconResult = Shell.cmd("chcon '" + ctx + "' '" + path + "'").exec();
        if (!chconResult.isSuccess()) {
            if (diagnose) {
                for (String e : chconResult.getErr()) FixerLog.e("  chcon ERR: " + e);
                FixerLog.w("  Trying alternative contexts...");
            }
            // Try alternative contexts
            String[] altContexts = {
                "u:object_r:media_rw_data_file:s0",
                "u:object_r:sdcardfs:s0",
                "u:object_r:fuse:s0",
                "u:object_r:vfat:s0",
                "u:object_r:media_data_file:s0",
            };
            for (String alt : altContexts) {
                Shell.Result tryRes = Shell.cmd("chcon '" + alt + "' '" + path + "'").exec();
                if (tryRes.isSuccess()) {
                    if (diagnose) FixerLog.i("  chcon OK with: " + alt);
                    break;
                } else if (diagnose) {
                    FixerLog.d("  chcon failed with: " + alt);
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
        boolean ok = !verify.getOut().isEmpty() && "OK".equals(verify.getOut().get(0));

        if (diagnose) FixerLog.d("  fixDirSmart result: " + (ok ? "OK" : "FAIL"));
        return ok;
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

    private static void fixSubDirs(String parentPath, boolean diagnose) {
        for (String sub : SUBDIRS) {
            String subPath = parentPath + "/" + sub;
            Shell.cmd(String.join(" && ",
                "mkdir -p '" + subPath + "'",
                "chmod " + DIR_PERM + " '" + subPath + "'",
                "chown " + OWNER + " '" + subPath + "'")
            ).exec();
            if (diagnose) FixerLog.d("  Subdir: " + sub + " created");
        }
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

    private static void logPackageInfo(String pkg) {
        Shell.Result uidRes = Shell.cmd(
            "dumpsys package " + pkg + " | grep userId= | head -1").exec();
        for (String line : uidRes.getOut())
            FixerLog.d("  UID: " + line.trim());

        Shell.Result pathRes = Shell.cmd("pm path " + pkg).exec();
        for (String line : pathRes.getOut())
            FixerLog.d("  Path: " + line.trim());

        Shell.Result permRes = Shell.cmd(
            "dumpsys package " + pkg
            + " | grep -E 'storage|STORAGE|READ_MEDIA|MANAGE|READ_EXTERNAL|WRITE_EXTERNAL'"
        ).exec();
        if (!permRes.getOut().isEmpty()) {
            FixerLog.d("  Storage permissions:");
            for (String line : permRes.getOut())
                FixerLog.d("    " + line.trim());
        }

        Shell.Result mountRes = Shell.cmd(
            "cat /proc/mounts | grep 'emulated' | head -5").exec();
        FixerLog.d("  Mounts:");
        for (String line : mountRes.getOut())
            FixerLog.d("    " + line.trim());
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
        Shell.Result kernRes = Shell.cmd("uname -r").exec();
        FixerLog.i("SDK: " + join(sdkRes.getOut()));
        FixerLog.i("ROM: " + join(romRes.getOut()));
        FixerLog.i("Kernel: " + join(kernRes.getOut()));

        // FUSE vs sdcardfs
        Shell.Result fsRes = Shell.cmd("mount | grep emulated | head -5").exec();
        FixerLog.i("Storage filesystem:");
        for (String line : fsRes.getOut())
            FixerLog.i("  " + line.trim());

        // Root method
        Shell.Result magiskRes = Shell.cmd("magisk -v 2>/dev/null").exec();
        Shell.Result ksuRes = Shell.cmd(
            "ksud --version 2>/dev/null; ksu_susfs --version 2>/dev/null"
        ).exec();
        FixerLog.i("Magisk: " + join(magiskRes.getOut()));
        FixerLog.i("KernelSU: " + join(ksuRes.getOut()));

        // Check if Android/media parent exists
        Shell.Result mediaParent = Shell.cmd(
            "ls -laZd /storage/emulated/0/Android/media/ 2>/dev/null"
            + " || echo 'Android/media DOES NOT EXIST'"
        ).exec();
        FixerLog.i("Media parent:");
        for (String line : mediaParent.getOut())
            FixerLog.i("  " + line.trim());

        // Check mount namespace
        Shell.Result nsRes = Shell.cmd(
            "ls -la /proc/self/ns/mnt 2>/dev/null"
        ).exec();
        FixerLog.i("Mount namespace: " + join(nsRes.getOut()));

        // Check FUSE implementation
        Shell.Result fuseRes = Shell.cmd(
            "ps -ef | grep -i fuse | grep -v grep | head -5"
        ).exec();
        FixerLog.i("FUSE processes:");
        for (String line : fuseRes.getOut())
            FixerLog.i("  " + line.trim());

        // Now do the fix with full diagnostics
        fixPackage(pkg, true);

        // Post-fix: check from different paths
        FixerLog.i("=== PATH VERIFICATION ===");
        String[] paths = {
            "/storage/emulated/0/Android/data/" + pkg,
            "/data/media/0/Android/data/" + pkg,
            "/mnt/user/0/emulated/0/Android/data/" + pkg,
            "/mnt/pass_through/0/emulated/0/Android/data/" + pkg,
        };
        for (String p : paths) {
            Shell.Result check = Shell.cmd(
                "ls -laZd '" + p + "' 2>/dev/null || echo 'NOT FOUND: " + p + "'"
            ).exec();
            for (String line : check.getOut())
                FixerLog.i("  " + line.trim());
        }

        // Check the underlying data path (bypass FUSE)
        FixerLog.i("=== UNDERLYING STORAGE (bypass FUSE) ===");
        Shell.Result underRes = Shell.cmd(
            "ls -laZd /data/media/0/Android/data/" + pkg + " 2>/dev/null"
            + " || echo 'Not found in /data/media/0/'"
        ).exec();
        for (String line : underRes.getOut())
            FixerLog.i("  " + line.trim());

        Shell.Result underMediaRes = Shell.cmd(
            "ls -laZd /data/media/0/Android/media/" + pkg + " 2>/dev/null"
            + " || echo 'Not found in /data/media/0/Android/media/'"
        ).exec();
        for (String line : underMediaRes.getOut())
            FixerLog.i("  " + line.trim());

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
            return (success ? "V " : "X ") + packageName
                    + " [data:" + (dataOk ? "ok" : "fail")
                    + " obb:" + (obbOk ? "ok" : "fail")
                    + " media:" + (mediaOk ? "ok" : "fail")
                    + " write:" + (writeTestOk ? "ok" : "fail") + "]";
        }
    }
}
