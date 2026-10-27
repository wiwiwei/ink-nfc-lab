package dev.inknfclab.thn31;

import android.os.SystemClock;
import dev.inknfclab.ImageCodec;
import dev.inknfclab.ImageTransfer;
import dev.inknfclab.Protocol;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Root Q discovery + owned NCI DATA; never fabricates Tag or uses Android IsoDep. */
public final class Thn31Worker {
    private static void event(String kind, String message) {
        System.out.println("THN31|" + kind + "|" + message.replace('\n', ' '));
        System.out.flush();
    }

    public static void main(String[] args) {
        int exit = 1;
        try {
            if (android.os.Process.myUid() != 0) throw new SecurityException("Root required");
            if (args.length != 2) throw new IllegalArgumentException("Thn31Worker image-file|--read stop-file");
            byte[] image = null;
            if (!args[0].equals("--read")) {
                File file = new File(args[0]);
                if (file.length() != ImageCodec.PACKED_LENGTH) throw new IllegalArgumentException("Invalid image length");
                image = Files.readAllBytes(file.toPath());
                if (image.length != ImageCodec.PACKED_LENGTH) throw new IllegalArgumentException("Image changed while reading");
            }
            SessionControl.stopFile = new File(args[1]);
            SessionControl.deadline = SystemClock.elapsedRealtime() + 180000;
            SessionControl.check();
            Thread input = new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null)
                        if (line.equalsIgnoreCase("stop")) SessionControl.cancelled = true;
                } catch (Exception ignored) {}
            }, "thn31-stop");
            input.setDaemon(true); input.start();
            // Keep the original app's lock/lease names to exclude simultaneous sessions
            // when both applications are installed. These names are not package lookups.
            try (ScreenSessionLock lock = ScreenSessionLock.acquire(2500)) {
                Flow flow = new Flow(image);
                exit = VendorPollCli.execute(new String[]{image == null ? "q-d1-screen" : "q-write-screen"}, flow);
                if (exit == 0 && VendorPollCli.restorationVerified && flow.complete)
                    event("COMPLETE", image == null ? "D1 身份校验通过；未写入图像" : "协议确认刷新完成；请目视确认画面");
                else {
                    if (exit == 0) exit = 1;
                    event("ERROR", "会话未完成：" + VendorPollCli.lastError);
                }
                event("RESTORED", Boolean.toString(VendorPollCli.restorationVerified));
            }
        } catch (Throwable error) { exit = 1; event("ERROR", error.toString()); }
        event("END", "exit=" + exit);
        System.exit(exit);
    }

    private static final class Flow implements VendorPollCli.ActiveSessionAction {
        private final byte[] image;
        private final NfcSessionIsolation isolation = new NfcSessionIsolation();
        private final NfcHalWriter writer;
        private boolean complete;
        Flow(byte[] image) throws Exception { this.image = image; writer = new NfcHalWriter(); }
        @Override public void prepare() throws Exception {
            isolation.prepare();
            event("READY", "Root Q 轮询即将开始；请保持屏幕贴合和手机解锁");
        }
        @Override public void close() throws Exception { isolation.close(); }
        @Override public void run(LiveNciMonitor monitor, int selectedId) throws Exception {
            NciDataSession session = new NciDataSession(monitor, writer, selectedId);
            monitor.close();
            try (NciDataCapture capture = isolation.openDataCapture(writer)) {
                session.useDataTransport(capture);
                byte[] response = session.transceive(Protocol.READ_INFO, 3000);
                Protocol.DeviceInfo info = Protocol.parseDeviceInfo(response, session.uid);
                if (!info.supportsImageTransfer()) throw new IllegalStateException("D1 identity/geometry mismatch; no write");
                event("D1", info.width + "x" + info.height + "; colors=" + info.colors + "; UID matches activation");
                if (image != null) {
                    ImageTransfer.send(new ImageTransfer.ApduLink() {
                        private int timeout = 3000;
                        @Override public boolean isConnected() { return !SessionControl.cancelled; }
                        @Override public int getMaxTransceiveLength() { return 257; }
                        @Override public int getTimeout() { return timeout; }
                        @Override public void setTimeout(int value) { timeout = value; }
                        @Override public byte[] transceive(byte[] command) throws Exception {
                            SessionControl.check();
                            long remaining = Math.min(timeout, SessionControl.deadline - SystemClock.elapsedRealtime());
                            if (remaining <= 0) throw new IllegalStateException("THN31 session expired");
                            return session.transceive(command, remaining);
                        }
                    }, image, new ImageTransfer.Progress() {
                        @Override public boolean cancelled() {
                            return SessionControl.cancelled || SessionControl.stopFile.exists()
                                    || SystemClock.elapsedRealtime() >= SessionControl.deadline;
                        }
                        @Override public void update(String message, int done, int total) {
                            event("PROGRESS", done + "/" + total + " " + message);
                        }
                        @Override public void trace(String message) { event("TRACE", message); }
                    });
                }
                complete = true;
                event("TRACE", capture.summary());
            }
        }
    }
    private Thn31Worker() {}
}
