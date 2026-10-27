package dev.inknfclab;

import android.nfc.tech.IsoDep;
import java.util.List;

public final class ImageTransfer {
    private static final byte[] DEFAULT_PIN = {0x52, 0x65, 0x69, (byte) 0x99, 0x19};

    public interface Progress {
        void update(String message, int completed, int total);
        boolean cancelled();
        void trace(String message);
    }

    /** APDU transport for controllers which cannot expose a system Tag. */
    public interface ApduLink {
        boolean isConnected();
        int getMaxTransceiveLength();
        byte[] transceive(byte[] command) throws Exception;
        int getTimeout();
        void setTimeout(int milliseconds);
    }

    interface RefreshLink {
        byte[] transceive(byte[] command) throws Exception;
        int getTimeout();
        void setTimeout(int milliseconds);
    }

    interface RefreshClock {
        long now();
        void sleep(long milliseconds) throws Exception;
    }

    public static void send(IsoDep link, byte[] packed, Progress progress) throws Exception {
        if (link == null) throw new IllegalStateException("ISO-DEP连接已断开");
        send(new ApduLink() {
            @Override public boolean isConnected() { return link.isConnected(); }
            @Override public int getMaxTransceiveLength() { return link.getMaxTransceiveLength(); }
            @Override public byte[] transceive(byte[] command) throws Exception { return link.transceive(command); }
            @Override public int getTimeout() { return link.getTimeout(); }
            @Override public void setTimeout(int milliseconds) { link.setTimeout(milliseconds); }
        }, packed, progress);
    }

    public static void send(ApduLink link, byte[] packed, Progress progress) throws Exception {
        send(link, packed, progress, new RefreshClock() {
            @Override public long now() { return android.os.SystemClock.elapsedRealtime(); }
            @Override public void sleep(long milliseconds) throws Exception { Thread.sleep(milliseconds); }
        });
    }

    static void send(ApduLink link, byte[] packed, Progress progress, RefreshClock clock) throws Exception {
        if (link == null || !link.isConnected()) throw new IllegalStateException("ISO-DEP连接已断开");
        if (packed == null || packed.length != ImageCodec.PACKED_LENGTH)
            throw new IllegalArgumentException("图像数据长度错误，拒绝写入");
        int chunkSize = Math.min(ImageCodec.CHUNK_SIZE, link.getMaxTransceiveLength() - 7);
        if (chunkSize < 1) throw new IllegalStateException("ISO-DEP最大传输长度不足，拒绝开始写屏");
        List<ImageCodec.Packet> packets = ImageCodec.packets(packed, chunkSize);
        progress.update("正在认证", 0, packets.size());
        byte[] authentication = Protocol.authentication(DEFAULT_PIN);
        for (int index = 0; index < 2; index++) {
            requireActive(progress);
            Protocol.requireSuccess(link.transceive(authentication), "本地验证 " + (index + 1));
        }
        for (int index = 0; index < packets.size(); index++) {
            requireActive(progress);
            ImageCodec.Packet packet = packets.get(index);
            Protocol.requireSuccess(link.transceive(packet.apdu), "图像块 " + packet.block + " / 包 " + packet.subpacket);
            progress.update("上传中 · 块 " + (packet.block + 1) + " · " + (index + 1) + "/" + packets.size(), index + 1, packets.size());
        }
        progress.trace("D3_CONFIRMED · " + packets.size() + "/" + packets.size() + " 个图像包均收到 9000");
        refresh(new RefreshLink() {
            @Override public byte[] transceive(byte[] command) throws Exception { return link.transceive(command); }
            @Override public int getTimeout() { return link.getTimeout(); }
            @Override public void setTimeout(int milliseconds) { link.setTimeout(milliseconds); }
        }, clock, progress, packets.size());
    }

    static void refresh(RefreshLink link, RefreshClock clock, Progress progress, int packetCount) throws Exception {
        int originalTimeout = link.getTimeout();
        long started = clock.now();
        long deadline = started + 60000;
        String stage = "D4";
        try {
            requireActive(progress);
            progress.update("图片已发送，正在触发刷新", packetCount, packetCount);
            byte[] refreshCommand = {(byte) 0xf0, (byte) 0xd4, (byte) 0x94, (byte) 0x80, 0};
            link.setTimeout(remainingTimeout(clock, deadline));
            progress.trace("D4 TX " + Protocol.hex(refreshCommand) + " · timeout=" + link.getTimeout() + "ms");
            byte[] response = link.transceive(refreshCommand);
            progress.trace("D4 RX " + Protocol.hex(response) + " · elapsed=" + (clock.now() - started) + "ms");
            Protocol.requireSuccess(response, "触发屏幕刷新");
            stage = "DE";
            progress.update("刷新指令已确认，等待屏幕完成", packetCount, packetCount);
            int polls = 0;
            while (true) {
                requireActive(progress);
                link.setTimeout(remainingTimeout(clock, deadline));
                progress.trace("DE TX " + Protocol.hex(Protocol.READ_STATUS) + " · poll=" + (++polls)
                        + " · timeout=" + link.getTimeout() + "ms");
                response = link.transceive(Protocol.READ_STATUS);
                progress.trace("DE RX " + Protocol.hex(response) + " · elapsed=" + (clock.now() - started) + "ms");
                boolean complete = Protocol.parseRefreshStatus(response);
                if (clock.now() > deadline) throw refreshTimeout();
                if (complete) {
                    progress.update("屏幕刷新完成", packetCount, packetCount);
                    return;
                }
                clock.sleep(Math.min(500, remainingTimeout(clock, deadline)));
            }
        } catch (Exception error) {
            progress.trace("REFRESH_FAILED · stage=" + stage + " · elapsed=" + (clock.now() - started)
                    + "ms · " + error);
            throw error;
        } finally {
            try { link.setTimeout(originalTimeout); }
            catch (Exception error) { progress.trace("TIMEOUT_RESTORE_FAILED · " + error); }
        }
    }

    private static int remainingTimeout(RefreshClock clock, long deadline) throws java.util.concurrent.TimeoutException {
        long remaining = deadline - clock.now();
        if (remaining <= 0) throw refreshTimeout();
        return (int) remaining;
    }

    private static java.util.concurrent.TimeoutException refreshTimeout() {
        return new java.util.concurrent.TimeoutException("D4 与 DE 刷新确认超过60秒；图像已上传，最终画面未确认");
    }

    private static void requireActive(Progress progress) {
        if (progress.cancelled()) throw new java.util.concurrent.CancellationException("已取消；屏幕可能处于部分写入状态");
    }

    private ImageTransfer() {}
}
