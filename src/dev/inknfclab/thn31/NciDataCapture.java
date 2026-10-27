package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import android.os.SystemClock;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Root-only receive channel from the already verified NFC-owned session hook.
 * The hook supplies real NCI inbound packets, not HAL logcat lines.
 * HAL write completion is local acknowledgement only; final APDU response
 * is still mandatory to establish that a command succeeded.
 */
final class NciDataCapture implements NciDataSession.Transport, AutoCloseable {
    interface Writer {
        void write(byte[] packet) throws Exception;
    }
    interface Clock {
        long now();
        void sleep(long milliseconds) throws InterruptedException;
    }
    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long now() { return SystemClock.elapsedRealtime(); }
        @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
    };

    private static final long MAX_FILE_BYTES = 3L * 1024 * 1024;
    private static final int MAX_FRAME_HEX_LENGTH = 516;
    private final RandomAccessFile file;
    private final Writer writer;
    private final Clock clock;
    private final ArrayDeque<PollEvidence.Frame> received = new ArrayDeque<>();
    private final StringBuilder partial = new StringBuilder();
    private PollEvidence.Frame acceptedTx;
    private long offset;
    private int creditsReceived;
    private int dataReceived;
    private int txAccepted;

    NciDataCapture(File path, NfcHalWriter writer) throws Exception {
        this(path, writer::writeData);
    }

    NciDataCapture(File path, Writer writer) throws Exception {
        this(path, writer, SYSTEM_CLOCK);
    }

    NciDataCapture(File path, Writer writer, Clock clock) throws Exception {
        if (!path.isFile() || !path.canRead())
            throw new IllegalStateException("Owned NFC receive file unavailable");
        this.file = new RandomAccessFile(path, "r");
        this.writer = writer;
        this.clock = clock;
        // Activation occurred on the existing monitor. Never replay earlier
        // discovery/selection notifications as current APDU responses.
        offset = file.length();
        if (offset > MAX_FILE_BYTES) throw new IllegalStateException("Unexpected NFC capture file length");
    }

    @Override public void write(byte[] packet) throws Exception {
        if (acceptedTx != null) throw new IllegalStateException("Previous HAL DATA acceptance unconsumed");
        SessionControl.check();
        writer.write(packet);
        txAccepted++;
        acceptedTx = new PollEvidence.Frame(true, Protocol.hex(packet));
    }

    @Override public PollEvidence.Frame receive(long waitMs) throws Exception {
        long deadline = clock.now() + Math.max(waitMs, 1);
        while (true) {
            SessionControl.check();
            if (acceptedTx != null) {
                PollEvidence.Frame result = acceptedTx;
                acceptedTx = null;
                return result;
            }
            if (!received.isEmpty()) return received.removeFirst();
            pollFile();
            if (!received.isEmpty()) return received.removeFirst();
            if (clock.now() >= deadline) return null;
            clock.sleep(Math.min(5, Math.max(1, deadline - clock.now())));
        }
    }

    private void pollFile() throws Exception {
        long length = file.length();
        if (length < offset || length > MAX_FILE_BYTES)
            throw new IllegalStateException("Owned NFC receive channel rotated or exceeded budget");
        if (length == offset) return;
        byte[] bytes = new byte[(int) Math.min(4096, length - offset)];
        file.seek(offset);
        file.readFully(bytes);
        offset += bytes.length;
        for (byte value : bytes) {
            if (value == '\n') {
                String hex = partial.toString();
                partial.setLength(0);
                PollEvidence.Frame frame = parseCaptured(hex);
                if (frame.hex.startsWith("6006")) creditsReceived++;
                else if (frame.hex.startsWith("0000") || frame.hex.startsWith("1000")) dataReceived++;
                received.addLast(frame);
                if (received.size() > 128)
                    throw new IllegalStateException("Owned NFC receive queue overflow");
            } else if (value != '\r') {
                if (partial.length() >= MAX_FRAME_HEX_LENGTH)
                    throw new IllegalStateException("Oversized owned NFC packet");
                partial.append((char) (value & 255));
            }
        }
    }

    static PollEvidence.Frame parseCaptured(String raw) {
        if (raw.isEmpty() || (raw.length() & 1) != 0 || raw.length() > MAX_FRAME_HEX_LENGTH
                || !raw.matches("[0-9a-f]+"))
            throw new IllegalStateException("Invalid owned NFC frame encoding");
        byte[] data = PollEvidence.bytes(raw.toUpperCase(java.util.Locale.ROOT));
        if (data.length < 3 || data.length != 3 + (data[2] & 255))
            throw new IllegalStateException("Invalid owned NFC frame envelope");
        boolean dataFrame = (data[0] == 0 || data[0] == 0x10) && data[1] == 0;
        boolean credit = data[0] == 0x60 && data[1] == 6;
        boolean deactivated = data[0] == 0x61 && data[1] == 6;
        if (!dataFrame && !credit && !deactivated)
            throw new IllegalStateException("Non-owned NFC frame in capture channel");
        return new PollEvidence.Frame(false, raw.toUpperCase(java.util.Locale.ROOT));
    }

    String summary() {
        return "source=owned-nfc-capture, hal_writes=" + txAccepted
                + ", credits=" + creditsReceived + ", data_replies=" + dataReceived
                + ", queued=" + received.size() + ", partial_hex=" + partial.length();
    }

    @Override public void close() throws Exception { file.close(); }
}
