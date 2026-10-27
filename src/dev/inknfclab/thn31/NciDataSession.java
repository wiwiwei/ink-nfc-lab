package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;

/** ISO-DEP INF/APDU transport; NFCC owns ISO-DEP blocks, CRC and WTX. */
final class NciDataSession {
    interface Transport {
        PollEvidence.Frame receive(long waitMs) throws Exception;
        void write(byte[] packet) throws Exception;
    }
    interface Time { long now(); }
    final byte[] uid;
    private Transport transport;
    private boolean applicationStarted;
    private final Time clock;
    private final int maxPayload;
    private int credits;
    private final ArrayDeque<String> pendingTx = new ArrayDeque<>();

    NciDataSession(LiveNciMonitor monitor, NfcHalWriter writer, int selectedId) throws Exception {
        this(new Transport() {
            @Override public PollEvidence.Frame receive(long waitMs) throws Exception { return monitor.next(waitMs); }
            @Override public void write(byte[] packet) throws Exception { writer.writeData(packet); }
        }, SystemClock::elapsedRealtime, selectedId);
    }
    NciDataSession(Transport transport, Time clock, int selectedId) throws Exception {
        this.transport = transport; this.clock = clock;
        long until = clock.now() + 2500;
        boolean accepted = false;
        byte[] activation = null;
        while (clock.now() < until) {
            SessionControl.check();
            PollEvidence.Frame f = transport.receive(until - clock.now());
            if (f == null) continue;
            if (f.tx) {
                if (!f.hex.equals(String.format("210403%02X0402", selectedId)))
                    throw new IllegalStateException("Competing command before activation");
            } else if (f.hex.startsWith("4104")) {
                if (!f.hex.equals("41040100")) throw new IllegalStateException("ISO-DEP selection rejected");
                accepted = true;
            } else if (f.hex.startsWith("6105")) {
                byte[] b = PollEvidence.bytes(f.hex);
                if (!accepted || b.length < 23 || (b[3] & 255) != selectedId || b[4] != 2 || b[5] != 4
                        || (b[6] & 255) != 0x71 || (b[9] & 255) < 9 || b[12] != 4 || b[17] != 1
                        || (b[18] & 0x20) == 0 || 14 + (b[9] & 255) > b.length)
                    throw new IllegalStateException("Unverified ISO-DEP activation");
                int actIndex = 13 + (b[9] & 255);
                if (b.length != actIndex + 1 + (b[actIndex] & 255))
                    throw new IllegalStateException("Invalid activation lengths");
                activation = b; break;
            } else checkState(f);
        }
        if (activation == null) throw new IllegalStateException("No matching ISO-DEP activation");
        uid = Arrays.copyOfRange(activation, 13, 17);
        maxPayload = activation[7] & 255;
        credits = activation[8] & 255;
        if (maxPayload == 0 || credits == 0) throw new IllegalStateException("No RF data capacity/credit");
    }

    /** Change only the activated connection's DATA transport, never its Q discovery path. */
    void useDataTransport(Transport receiveChannel) {
        if (receiveChannel == null || applicationStarted || !pendingTx.isEmpty())
            throw new IllegalStateException("Cannot change transport during an APDU session");
        transport = receiveChannel;
    }

    byte[] transceive(byte[] apdu, long timeoutMs) throws Exception {
        applicationStarted = true;
        if (apdu.length == 0 || apdu.length > 4096) throw new IllegalArgumentException("Invalid APDU length");
        SessionControl.check();
        long until = clock.now() + timeoutMs;
        for (int offset = 0; offset < apdu.length;) {
            while (credits == 0) nextControl(until);
            int count = Math.min(maxPayload, apdu.length - offset);
            byte[] packet = new byte[count + 3];
            packet[0] = (byte) (offset + count < apdu.length ? 0x10 : 0);
            packet[2] = (byte) count;
            System.arraycopy(apdu, offset, packet, 3, count);
            pendingTx.add(Protocol.hex(packet));
            SessionControl.check(); transport.write(packet);
            if (credits != 255) credits--;
            offset += count;
        }
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        while (clock.now() < until) {
            SessionControl.check();
            PollEvidence.Frame f = transport.receive(until - clock.now());
            if (f == null) continue;
            byte[] b = PollEvidence.bytes(f.hex);
            if (!f.tx && (b[0] == 0 || b[0] == 0x10) && b[1] == 0) {
                if (!pendingTx.isEmpty()) throw new IllegalStateException("Response without confirmed current TX");
                if (response.size() + b.length - 3 > 8192) throw new IllegalStateException("Oversized APDU response");
                response.write(b, 3, b.length - 3);
                if (b[0] == 0) return response.toByteArray();
            } else control(f);
        }
        throw new IllegalStateException("APDU timeout; do not replay");
    }

    private void nextControl(long until) throws Exception {
        SessionControl.check();
        if (clock.now() >= until) throw new IllegalStateException("NCI credit timeout");
        PollEvidence.Frame f = transport.receive(until - clock.now());
        if (f != null) control(f);
    }
    private void control(PollEvidence.Frame f) {
        if (f.tx) {
            if (pendingTx.isEmpty() || !f.hex.equals(pendingTx.remove()))
                throw new IllegalStateException("Unexpected NCI transmission during APDU");
            return;
        }
        checkState(f);
        if (f.hex.startsWith("0000") || f.hex.startsWith("1000"))
            throw new IllegalStateException("Unexpected response while sending fragments");
        if (f.hex.startsWith("6006")) {
            byte[] b = PollEvidence.bytes(f.hex);
            if (b.length < 4 || b.length != 4 + 2 * (b[3] & 255))
                throw new IllegalStateException("Invalid NCI credits");
            for (int i = 4; i < b.length; i += 2) {
                if (b[i] == 0 && credits != 255) {
                    credits += b[i + 1] & 255;
                    if (credits > 254) throw new IllegalStateException("Invalid credit total");
                }
            }
        }
    }
    private static void checkState(PollEvidence.Frame f) {
        if (!f.tx && (f.hex.startsWith("6106") || f.hex.startsWith("6007") || f.hex.startsWith("6008")
                || f.hex.startsWith("6000"))) throw new IllegalStateException("RF connection ended or controller error: " + f.hex);
    }
}
