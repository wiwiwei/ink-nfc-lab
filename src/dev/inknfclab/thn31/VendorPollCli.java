package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import java.lang.reflect.Method;

/** Fixed Q_POLL experiment through initialized system NFC; never takes its callback slot. */
public final class VendorPollCli {
    interface ActiveSessionAction {
        void prepare() throws Exception;
        void run(LiveNciMonitor monitor, int selectedId) throws Exception;
        void close() throws Exception;
    }
    static boolean restorationVerified;
    static boolean selectionAttempted;
    static String selectionReason;
    static boolean commandIssued;
    static String lastError;
    private static boolean screenUnlocked() throws Exception {
        java.lang.Process state = new ProcessBuilder("/system/bin/dumpsys", "nfc").start();
        try (java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(
                state.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            int budget = 0; String line;
            while ((line = input.readLine()) != null) {
                if ((budget += line.length()) > 32768) break;
                if (line.trim().startsWith("mScreenState=")) return line.trim().equals("mScreenState=ON_UNLOCKED");
            }
            throw new IllegalStateException("无法取得当前 NFC 锁屏状态，尚未启动 RF 测试");
        } finally { state.destroy(); }
    }
    /** A stalled Root helper must not toggle global NFC power or reinitialize the controller. */
    private static void emergencyResumeWithoutRestart() {
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) manager.getMethod("getService", String.class).invoke(null, "nfc");
            if (binder == null) throw new IllegalStateException("NFC service absent");
            Class<?> api = Class.forName("android.nfc.INfcAdapter");
            Object adapter = Class.forName("android.nfc.INfcAdapter$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            System.out.println("WATCHDOG_DEACTIVATE_STATUS=" + api.getMethod("sendVendorNciMessage",
                    int.class, int.class, int.class, byte[].class)
                    .invoke(adapter, 1, 1, 6, new byte[]{0}));
            System.out.println("WATCHDOG_RESUME_POLLING=" + api.getMethod("resumePolling").invoke(adapter));
            System.out.println("WATCHDOG_NFC_STATE=" + api.getMethod("getState").invoke(adapter));
        } catch (Throwable error) { System.out.println("WATCHDOG_RECOVERY_ERROR=" + error); }
    }
    public static void main(String[] args) {
        System.exit(execute(args));
    }
    public static int execute(String[] args) {
        return execute(args, null);
    }
    static int execute(String[] args, ActiveSessionAction action) {
        restorationVerified = false;
        selectionAttempted = false;
        commandIssued = false; lastError = null;
        selectionReason = "此模式不执行选择";
        int outcome = 1;
        Object adapter = null;
        Class<?> api = null;
        boolean paused = false;
        boolean issued = false;
        LiveNciMonitor monitor = null;
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        if (action != null) {
            Thread watchdog = new Thread(() -> {
                SystemClock.sleep(220000);
                if (finished.get()) return;
                System.out.println("WATCHDOG=cancel active session; no NFC power cycling");
                SessionControl.cancelled = true;
                SystemClock.sleep(12000);
                if (finished.get()) return;
                try { action.close(); } catch (Exception error) {
                    System.out.println("WATCHDOG_ISOLATION_CLOSE_ERROR=" + error);
                }
                emergencyResumeWithoutRestart();
                System.exit(124);
            }, "epd-session-watchdog");
            watchdog.setDaemon(true); watchdog.start();
        }
        try {
            if (Process.myUid() != 0) throw new SecurityException("Root required");
            if (args.length != 1 || !(args[0].equals("inspect") || args[0].equals("inspect-live") || args[0].equals("q-poll-probe")
                    || args[0].equals("a-poll-screen") || args[0].equals("q-poll-screen")
                    || args[0].equals("mixed-poll-screen") || args[0].equals("q-select-screen") || args[0].equals("q-d1-screen")
                    || args[0].equals("q-write-screen") || args[0].equals("q-refresh-screen")))
                throw new IllegalArgumentException("Unknown fixed polling profile");
            Class<?> manager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) manager.getMethod("getService", String.class).invoke(null, "nfc");
            if (binder == null) throw new IllegalStateException("NFC service absent");
            api = Class.forName("android.nfc.INfcAdapter");
            adapter = Class.forName("android.nfc.INfcAdapter$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            System.out.println("NFC_STATE=" + api.getMethod("getState").invoke(adapter));
            for (Method method : api.getMethods()) {
                String name = method.getName();
                if (name.contains("Vendor") || name.equals("pausePolling")
                        || name.equals("resumePolling")) System.out.println("API=" + method);
            }
            Class<?> callback = Class.forName("android.nfc.INfcVendorNciCallback");
            for (Method method : callback.getDeclaredMethods()) System.out.println("CALLBACK=" + method);
            if (args[0].equals("inspect") || args[0].equals("inspect-live")) {
                if (args[0].equals("inspect-live")) {
                    monitor = new LiveNciMonitor();
                    monitor.synchronizeStream();
                    System.out.println("LIVE_NCI_STREAM_READY=true");
                }
                System.out.println("NO_NCI_SENT=true");
            } else {
                Class<?> properties = Class.forName("android.os.SystemProperties");
                Method get = properties.getMethod("get", String.class);
                if (!Thn31Profile.supported((String) get.invoke(null, "persist.sys.nfc.chip_name")))
                    throw new IllegalStateException("THN31 chip property required");
                if ((Integer) api.getMethod("getState").invoke(adapter) != 3)
                    throw new IllegalStateException("System NFC must already be on");
                if (!screenUnlocked())
                    throw new IllegalStateException("手机必须解锁并保持亮屏，尚未启动 RF 测试");
                // Keep the already enabled NFC service, NFCC and field lifecycle intact.
                // The session below only pauses the system's regular discovery.
                long max = ((Number) api.getMethod("getMaxPausePollingTimeoutMs").invoke(adapter)).longValue();
                if (max < 12000) throw new IllegalStateException("Polling pause window is too short");
                if ((args[0].equals("q-select-screen") || action != null) && max < 15000)
                    throw new IllegalStateException("ISO-DEP selection needs a 15-second polling pause budget");
                int pause = (Integer) api.getMethod("pausePolling", long.class).invoke(adapter, action == null ? Math.min(15000, max) : 0L);
                System.out.println("PAUSE_POLLING=" + pause);
                if (pause != 1) throw new IllegalStateException("System did not pause its discovery");
                paused = true;
                SystemClock.sleep(300); // let the normal command queue drain before a raw command
                if (action != null) action.prepare();
                Method send = api.getMethod("sendVendorNciMessage", int.class, int.class, int.class, byte[].class);
                if (args[0].equals("q-select-screen") || action != null) {
                    monitor = new LiveNciMonitor();
                    monitor.synchronizeStream();
                }
                issued = true;
                commandIssued = true;
                byte[] payload = new byte[]{1, 0x71, 1};
                String request = "210303017101";
                if (args[0].equals("a-poll-screen")) {
                    payload = new byte[]{1, 0, 1};
                    request = "210303010001";
                } else if (args[0].equals("mixed-poll-screen")) {
                    // Same append operation observed in the shipped QTag handler:
                    // one NFC-A entry plus Q_POLL, each frequency 1.
                    payload = new byte[]{2, 0, 1, 0x71, 1};
                    request = "2103050200017101";
                }
                System.out.println("PROFILE=" + args[0]);
                System.out.println("SCREEN_STAGE=polling");
                System.out.println("TX_REQUEST=" + request);
                int status = (Integer) send.invoke(adapter, 1, 1, 3, payload);
                System.out.println("FRAMEWORK_API_STATUS=" + status);
                if (status != 0) throw new IllegalStateException("Raw vendor request failed");
                if (monitor != null) {
                    SelectionGate gate = monitor.awaitSelection();
                    selectionReason = gate.reason();
                    if (gate.ready()) {
                        byte[] select = gate.selection();
                        selectionAttempted = true;
                        System.out.println("SCREEN_STAGE=selecting");
                        System.out.println("SELECT_DISCOVERY_ID=" + (select[0] & 255));
                        int selected = (Integer) send.invoke(adapter, 1, 1, 4, select);
                        System.out.println("SELECT_API_STATUS=" + selected);
                        if (selected != 0) throw new IllegalStateException("Selection API failed");
                        selectionReason = "已发送本轮 ISO-DEP 选择，需核对控制器响应与激活通知";
                        if (action == null) SystemClock.sleep(2000);
                        else action.run(monitor, select[0] & 255);
                    } else if (action != null) {
                        throw new IllegalStateException(selectionReason);
                    }
                    System.out.println("SELECTION_NOTE=" + selectionReason);
                } else SystemClock.sleep(args[0].endsWith("-screen") ? 5000 : 150);
                System.out.println("RF_7BIT_READY=false; validate the actual matching NCI response in HAL log");
            }
            outcome = 0;
        } catch (Throwable error) {
            lastError = error.toString();
            if (args.length == 1 && (args[0].equals("q-select-screen") || action != null)) selectionReason = "选择检测中止：" + error;
            System.out.println("ERROR=" + error);
        }
        finally {
            if (monitor != null) monitor.close();
            boolean cleanupOk = true;
            if (action != null) {
                try { action.close(); }
                catch (Throwable error) { cleanupOk = false; outcome = 3; System.out.println("ISOLATION_RESTORE_ERROR=" + error); }
            }
            if (issued) System.out.println("SCREEN_STAGE=restoring");
            if (issued) {
                try {
                    Method send = api.getMethod("sendVendorNciMessage", int.class, int.class, int.class, byte[].class);
                    System.out.println("TX_REQUEST=21060100");
                    int status = (Integer) send.invoke(adapter, 1, 1, 6, new byte[]{0});
                    System.out.println("DEACTIVATE_API_STATUS=" + status);
                    cleanupOk = cleanupOk && status == 0;
                } catch (Throwable error) { cleanupOk = false; System.out.println("DEACTIVATE_ERROR=" + error); }
            }
            if (paused) {
                try {
                    Object resume = api.getMethod("resumePolling").invoke(adapter);
                    System.out.println("RESUME_POLLING=" + resume);
                    if (!(resume instanceof Number) || ((Number) resume).intValue() != 1)
                        cleanupOk = false;
                } catch (Throwable error) { cleanupOk = false; System.out.println("RESUME_ERROR=" + error); }
            }
            // Never call svc nfc disable/enable here. Restore Q-only RF state and
            // system polling in place; NFC power state must remain unchanged.
            boolean nfcStillOn = false;
            if (api != null && adapter != null) {
                try {
                    int after = (Integer) api.getMethod("getState").invoke(adapter);
                    System.out.println("NFC_STATE_AFTER=" + after);
                    nfcStillOn = after == 3;
                } catch (Throwable error) { System.out.println("STATE_ERROR=" + error); }
            }
            restorationVerified = cleanupOk && nfcStillOn;
            System.out.println("SYSTEM_NFC_REINITIALIZED=false");
            System.out.println("RF_POLLING_RESTORED_WITHOUT_RESTART=" + restorationVerified);
            if (!restorationVerified) outcome = 3;
            finished.set(true);
        }
        return outcome;
    }
}
