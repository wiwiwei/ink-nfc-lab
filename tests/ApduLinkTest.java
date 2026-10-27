package dev.inknfclab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exercises the same image pipeline used by IsoDep over a non-Tag transport. */
public final class ApduLinkTest {
    static final class Link implements ImageTransfer.ApduLink {
        final List<byte[]> commands = new ArrayList<>();
        int timeout = 3000;
        boolean rejectAuthentication;
        @Override public boolean isConnected() { return true; }
        @Override public int getMaxTransceiveLength() { return 257; }
        @Override public int getTimeout() { return timeout; }
        @Override public void setTimeout(int value) { timeout = value; }
        @Override public byte[] transceive(byte[] command) {
            commands.add(command.clone());
            if (rejectAuthentication) return new byte[]{0x69, (byte) 0x82};
            if (Arrays.equals(command, Protocol.READ_STATUS)) return new byte[]{0, (byte) 0x90, 0};
            return new byte[]{(byte) 0x90, 0};
        }
    }
    static final ImageTransfer.RefreshClock CLOCK = new ImageTransfer.RefreshClock() {
        @Override public long now() { return 0; }
        @Override public void sleep(long milliseconds) {}
    };
    static final ImageTransfer.Progress PROGRESS = new ImageTransfer.Progress() {
        @Override public boolean cancelled() { return false; }
        @Override public void update(String message, int done, int total) {}
        @Override public void trace(String message) {}
    };
    public static void main(String[] args) throws Exception {
        byte[] packed = new byte[ImageCodec.PACKED_LENGTH];
        List<ImageCodec.Packet> packets = ImageCodec.packets(packed, 250);
        Link link = new Link();
        ImageTransfer.send(link, packed, PROGRESS, CLOCK);
        require(link.commands.size() == packets.size() + 4);
        require(Arrays.equals(link.commands.get(0), link.commands.get(1)));
        for (int i = 0; i < packets.size(); i++)
            require(Arrays.equals(packets.get(i).apdu, link.commands.get(i + 2)));
        require(Protocol.hex(link.commands.get(packets.size() + 2)).equals("F0D4948000"));
        require(Arrays.equals(link.commands.get(packets.size() + 3), Protocol.READ_STATUS));
        require(link.timeout == 3000);
        Link rejected = new Link(); rejected.rejectAuthentication = true;
        try { ImageTransfer.send(rejected, packed, PROGRESS, CLOCK); throw new AssertionError("Expected rejection"); }
        catch (IllegalStateException expected) { require(rejected.commands.size() == 1); }
        Link badImage = new Link();
        try { ImageTransfer.send(badImage, new byte[1], PROGRESS, CLOCK); throw new AssertionError("Expected bad image"); }
        catch (IllegalArgumentException expected) { require(badImage.commands.isEmpty()); }
        System.out.println("APDU link: authentication, 476 D3 packets, D4/DE and failure gates passed");
    }
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
}
