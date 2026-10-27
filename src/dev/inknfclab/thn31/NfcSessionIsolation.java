package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/** Bounded runtime ownership; normal NFC traffic resumes when the owner lease is removed. */
final class NfcSessionIsolation implements AutoCloseable {
    private static final File LEASE = new File("/data/user_de/0/com.android.nfc/files/epdprobe-owned-session.json");
    private static final File STATUS = new File("/data/user_de/0/com.android.nfc/files/epdprobe-owned-status.json");
    private static final File FRAMES = new File("/data/user_de/0/com.android.nfc/files/epdprobe-owned-data-frames.txt");
    private final String token = UUID.randomUUID().toString();
    private boolean installed;
    private String nfcPid;
    private boolean captureOwned;

    private static String runtimeApk() throws Exception {
        String classpath = System.getenv("CLASSPATH");
        if (classpath != null) for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!entry.endsWith(".apk")) continue;
            try (ZipFile zip = new ZipFile(entry)) {
                if (zip.getEntry("assets/thn31-session-isolation.js") != null && zip.getEntry("assets/frida-inject") != null) return entry;
            }
        }
        throw new IllegalStateException("Verified probe APK assets are missing from CLASSPATH");
    }

    private boolean bridgeState(String state) {
        try {
            JSONObject status = new JSONObject(new String(Files.readAllBytes(STATUS.toPath()), StandardCharsets.UTF_8));
            return status.optInt("protocol_version") == 2 && nfcPid.equals(status.optString("nfc_pid"))
                    && (state == null || state.equals(status.optString("state")));
        } catch (Exception error) { return false; }
    }

    private void awaitReady() throws Exception {
        long until = SystemClock.elapsedRealtime() + 3000;
        while (SystemClock.elapsedRealtime() < until) {
            SessionControl.check();
            if (bridgeState("ready")) {
                JSONObject state = new JSONObject(new String(Files.readAllBytes(STATUS.toPath()), StandardCharsets.UTF_8));
                if (token.equals(state.optString("token"))) { installed = true; return; }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Resident NFC bridge did not acknowledge this lease");
    }

    void prepare() throws Exception {
        String apk = runtimeApk();
        File injector = new File("/data/local/tmp/epd-frida-inject-17.24.0");
        if (!injector.isFile()) {
            File temporary = new File(injector.getPath() + "." + Process.myPid());
            try (ZipFile zip = new ZipFile(apk);
                    java.io.InputStream input = zip.getInputStream(zip.getEntry("assets/frida-inject"));
                    FileOutputStream output = new FileOutputStream(temporary)) { ProbeIo.copy(input, output, 90 * 1024 * 1024); }
            Os.chmod(temporary.getPath(), 0700);
            if (!temporary.renameTo(injector)) throw new IllegalStateException("Cannot prepare runtime helper");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(injector)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        if (!Protocol.hex(digest.digest()).equalsIgnoreCase("af77c1d6e291cccb74c6aae3214eddf369166a873456ce8db60d9f7a82c530c3"))
            throw new IllegalStateException("Unverified runtime helper");
        File script = new File("/data/local/tmp/epd-nfc-owned-session.js");
        try (ZipFile zip = new ZipFile(apk); java.io.InputStream input = zip.getInputStream(zip.getEntry("assets/thn31-session-isolation.js"));
                FileOutputStream output = new FileOutputStream(script)) { ProbeIo.copy(input, output, 65536); }
        Os.chmod(script.getPath(), 0600);
        java.lang.Process find = new ProcessBuilder("/system/bin/pidof", "com.android.nfc").start();
        String pid = new String(ProbeIo.read(find.getInputStream(), 256), StandardCharsets.UTF_8).trim();
        if (!find.waitFor(3, TimeUnit.SECONDS) || find.exitValue() != 0 || !pid.matches("[0-9]+"))
            throw new IllegalStateException("Cannot identify NFC process");
        nfcPid = pid;
        String processStatus = new String(Files.readAllBytes(new File("/proc/" + pid + "/status").toPath()), StandardCharsets.UTF_8);
        if (!processStatus.matches("(?s).*\nUid:\\s+1027\\s+.*")) throw new IllegalStateException("Unverified NFC process UID");
        boolean reuse = bridgeState(null);
        if (reuse && !bridgeState("idle"))
            throw new IllegalStateException("上一桥接租约尚未撤销，请等待其恢复后重试；本次未发出 RF 命令。");
        String maps = new String(Files.readAllBytes(new File("/proc/" + pid + "/maps").toPath()), StandardCharsets.UTF_8);
        if (!reuse && maps.contains("frida-agent"))
            throw new IllegalStateException("NFC 进程已有旧版或其他注入，已禁止重复注入以免系统崩溃；需先在空闲时重启 NFC 服务进程。");
        if (!reuse) {
            Files.write(STATUS.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
            Os.chown(STATUS.getPath(), 1027, 1027); Os.chmod(STATUS.getPath(), 0600);
        }
        // The injected NFC process appends only owned inbound DATA, credits and deactivation.
        // A new file is created before injection so no previous session can be replayed.
        Files.write(FRAMES.toPath(), new byte[0]);
        captureOwned = true;
        Os.chown(FRAMES.getPath(), 1027, 1027); Os.chmod(FRAMES.getPath(), 0600);
        JSONObject lease = new JSONObject().put("token", token).put("pid", Process.myPid())
                .put("deadline", System.currentTimeMillis() + 240000);
        Files.write(LEASE.toPath(), lease.toString().getBytes(StandardCharsets.UTF_8));
        Os.chown(LEASE.getPath(), 0, 1027); Os.chmod(LEASE.getPath(), 0640);
        if (reuse) {
            awaitReady();
            System.out.println("NFC_SESSION_ISOLATION=ready; resident_bridge=reused; nfc_pid=" + nfcPid);
            return;
        }
        java.lang.Process child = new ProcessBuilder(injector.getPath(), "--pid", pid, "--script", script.getPath(), "--eternalize")
                .redirectErrorStream(true).start();
        StringBuilder trace = new StringBuilder();
        try {
            RootOutput.collect(child, 20000, new RootOutput.Sink() {
                @Override public boolean line(String line) {
                    if (trace.length() < 8192) trace.append(line).append('\n');
                    return false;
                }
                @Override public void waiting(long elapsed, long silent) {
                    System.out.println("SCREEN_TRACE=等待桥接加载 · " + elapsed / 1000 + " 秒");
                }
            }, new RootOutput.Clock() {
                @Override public long now() { return SystemClock.elapsedRealtime(); }
                @Override public void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
            });
        } catch (Exception error) { child.destroy(); throw error; }
        String output = trace.toString();
        if (child.exitValue() != 0 || !output.contains("EPD_NFC_ISOLATION_READY"))
            throw new IllegalStateException("Runtime bridge did not start: " + output.substring(0, Math.min(1000, output.length())));
        awaitReady();
        System.out.println("NFC_SESSION_ISOLATION=ready; resident_bridge=new; nfc_pid=" + nfcPid);
    }

    NciDataCapture openDataCapture(NfcHalWriter writer) throws Exception {
        if (!installed || !FRAMES.isFile()) throw new IllegalStateException("Owned NCI receive channel unavailable");
        return new NciDataCapture(FRAMES, writer);
    }

    @Override public void close() throws Exception {
        if (LEASE.isFile()) {
            JSONObject lease = new JSONObject(new String(Files.readAllBytes(LEASE.toPath()), StandardCharsets.UTF_8));
            if (token.equals(lease.optString("token"))) Files.delete(LEASE.toPath());
        }
        if (!installed && !bridgeState(null)) {
            if (captureOwned && FRAMES.isFile()) Files.delete(FRAMES.toPath());
            return;
        }
        long until = SystemClock.elapsedRealtime() + 2000;
        while (SystemClock.elapsedRealtime() < until) {
            if (STATUS.isFile()) {
                JSONObject state = new JSONObject(new String(Files.readAllBytes(STATUS.toPath()), StandardCharsets.UTF_8));
                if (token.equals(state.optString("token")) && "idle".equals(state.optString("state"))
                        && state.optInt("protocol_version") == 2 && nfcPid.equals(state.optString("nfc_pid"))) {
                    System.out.println("NFC_SESSION_ISOLATION=inactive; resident_passthrough=true; consumed=" + state.optInt("consumed")
                            + "; captured=" + state.optInt("captured"));
                    installed = false;
                    if (FRAMES.isFile()) Files.delete(FRAMES.toPath());
                    return;
                }
            }
            SystemClock.sleep(50);
        }
        throw new IllegalStateException("Runtime bridge removal not verified");
    }
}
