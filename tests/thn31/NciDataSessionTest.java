package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import static dev.inknfclab.thn31.TestSupport.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class NciDataSessionTest {
    private static final String ACT = "61052002020471FF010A040004010203040120007100000B0A7833A002900102030403";
    private static final class Device implements NciDataSession.Transport, NciDataSession.Time {
        final ArrayDeque<PollEvidence.Frame> input = new ArrayDeque<>();
        final List<byte[]> sent = new ArrayList<>();
        boolean reply = true, fragmentedReply, badCredits;
        long time;
        Device() { input.add(new PollEvidence.Frame(true, "210403020402")); receive("41040100"); receive(ACT); }
        void receive(String hex) { input.add(new PollEvidence.Frame(false, hex)); }
        @Override public long now() { return time; }
        @Override public PollEvidence.Frame receive(long waitMs) { time += 10; return input.poll(); }
        @Override public void write(byte[] packet) {
            sent.add(packet.clone()); input.add(new PollEvidence.Frame(true, Protocol.hex(packet)));
            receive(badCredits ? "600603020001" : "600603010001");
            if (packet[0] == 0 && reply) {
                if (fragmentedReply) { receive("10000112"); receive("0000029000"); }
                else receive("0000029000");
            }
        }
    }
    public void fragmentsRespectControllerPayloadAndCredits() throws Exception {
        Device d = new Device(); NciDataSession s = new NciDataSession(d, d, 2);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, s.uid);
        assertArrayEquals(new byte[]{(byte) 0x90, 0}, s.transceive(new byte[257], 500));
        assertEquals(2, d.sent.size()); assertEquals(0x10, d.sent.get(0)[0]);
        assertEquals(258, d.sent.get(0).length); assertEquals(5, d.sent.get(1).length); assertEquals(0, d.sent.get(1)[0]);
    }
    public void incomingDataFragmentsAreReassembled() throws Exception {
        Device d = new Device(); d.fragmentedReply = true;
        assertArrayEquals(new byte[]{0x12, (byte) 0x90, 0}, new NciDataSession(d, d, 2).transceive(new byte[5], 500));
    }
    public void timeoutNeverReplaysApdu() throws Exception {
        Device d = new Device(); d.reply = false; NciDataSession s = new NciDataSession(d, d, 2);
        assertThrows(IllegalStateException.class, () -> s.transceive(new byte[5], 100)); assertEquals(1, d.sent.size());
    }
    public void hostDeactivationAbortsAndNoSecondFragmentIsSent() throws Exception {
        Device d = new Device(); NciDataSession s = new NciDataSession(d, d, 2);
        d.input.add(new PollEvidence.Frame(true, "21060100"));
        assertThrows(IllegalStateException.class, () -> s.transceive(new byte[257], 500)); assertEquals(1, d.sent.size());
    }
    public void invalidCreditNotificationAborts() throws Exception {
        Device d = new Device(); d.badCredits = true; NciDataSession s = new NciDataSession(d, d, 2);
        assertThrows(IllegalStateException.class, () -> s.transceive(new byte[257], 500)); assertEquals(1, d.sent.size());
    }
    public void activationMustMatchTheSelectedIdAndInterface() {
        Device d = new Device(); assertThrows(IllegalStateException.class, () -> new NciDataSession(d, d, 3));
    }
    public void manySequentialLongApdusUseSingleActivatedSession() throws Exception {
        Device d = new Device(); NciDataSession s = new NciDataSession(d, d, 2);
        for (int i = 0; i < 476; i++)
            assertArrayEquals(new byte[]{(byte) 0x90, 0}, s.transceive(new byte[257], 1000));
        assertEquals(952, d.sent.size());
    }
    public void cancellationDoesNotWriteACommand() throws Exception {
        Device d = new Device(); NciDataSession s = new NciDataSession(d, d, 2);
        SessionControl.cancelled = true;
        try { assertThrows(IllegalStateException.class, () -> s.transceive(new byte[5], 500)); assertTrue(d.sent.isEmpty()); }
        finally { SessionControl.cancelled = false; }
    }
    public static void main(String[] args) throws Exception {
        new NciDataSessionTest().fragmentsRespectControllerPayloadAndCredits();
        new NciDataSessionTest().incomingDataFragmentsAreReassembled();
        new NciDataSessionTest().timeoutNeverReplaysApdu();
        new NciDataSessionTest().hostDeactivationAbortsAndNoSecondFragmentIsSent();
        new NciDataSessionTest().invalidCreditNotificationAborts();
        new NciDataSessionTest().activationMustMatchTheSelectedIdAndInterface();
        new NciDataSessionTest().manySequentialLongApdusUseSingleActivatedSession();
        new NciDataSessionTest().cancellationDoesNotWriteACommand();
        System.out.println("NciDataSessionTest: 8 passed");
    }
}
