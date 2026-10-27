package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import static dev.inknfclab.thn31.TestSupport.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;


/** Offline verification of Frida-owned inbound frames, without a real NFC device. */
public final class NciDataCaptureTest {
    private static final String ACT =
            "61052002020471FF010A040004010203040120007100000B0A7833A002900102030403";

    private static final class Clock implements NciDataSession.Time, NciDataCapture.Clock {
        long time;
        @Override public long now() { return time; }
        @Override public void sleep(long millis) { time += millis; }
    }

    private static final class Activation implements NciDataSession.Transport {
        final ArrayDeque<PollEvidence.Frame> queue = new ArrayDeque<>();
        Activation() {
            queue.add(new PollEvidence.Frame(true, "210403020402"));
            queue.add(new PollEvidence.Frame(false, "41040100"));
            queue.add(new PollEvidence.Frame(false, ACT));
        }
        @Override public PollEvidence.Frame receive(long millis) { return queue.pollFirst(); }
        @Override public void write(byte[] packet) { throw new AssertionError("Unexpected activation write"); }
    }

    public void capturedCreditsAndRepliesSupport476LongApdus() throws Exception {
        File path = File.createTempFile("epd-owned-capture-", ".txt");
        try {
            Files.write(path.toPath(), "600603010001\n".getBytes(StandardCharsets.UTF_8));
            Clock clock = new Clock();
            NciDataSession session = new NciDataSession(new Activation(), clock, 2);
            List<byte[]> sent = new ArrayList<>();
            try (NciDataCapture capture = new NciDataCapture(path, packet -> {
                sent.add(packet.clone());
                append(path, "600603010001\n");
                if (packet[0] == 0) append(path, "0000029000\n");
            }, clock)) {
                session.useDataTransport(capture);
                for (int i = 0; i < 476; i++) {
                    assertArrayEquals(new byte[]{(byte) 0x90, 0},
                            session.transceive(new byte[257], 3000));
                }
            }
            assertEquals(952, sent.size());
            assertEquals(0x10, sent.get(0)[0]);
            assertEquals(0, sent.get(1)[0]);
        } finally { Files.deleteIfExists(path.toPath()); }
    }

    public void missingCreditFailsClosedWithoutResending() throws Exception {
        File path = File.createTempFile("epd-owned-capture-", ".txt");
        try {
            Clock clock = new Clock();
            NciDataSession session = new NciDataSession(new Activation(), clock, 2);
            List<byte[]> sent = new ArrayList<>();
            try (NciDataCapture capture = new NciDataCapture(path, packet -> {
                sent.add(packet.clone());
            }, clock)) {
                session.useDataTransport(capture);
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> session.transceive(new byte[257], 150));
                assertTrue(failure.getMessage().contains("credit timeout"));
            }
            assertEquals(1, sent.size());
        } finally { Files.deleteIfExists(path.toPath()); }
    }

    public void rejectMalformedOrUnownedFrames() {
        assertEquals("600603010001", NciDataCapture.parseCaptured("600603010001").hex);
        assertEquals("0000029000", NciDataCapture.parseCaptured("0000029000").hex);
        assertThrows(IllegalStateException.class, () -> NciDataCapture.parseCaptured("6f350928"));
        assertThrows(IllegalStateException.class, () -> NciDataCapture.parseCaptured("000002900"));
        assertThrows(IllegalStateException.class, () -> NciDataCapture.parseCaptured("000002900000"));
    }

    private static void append(File file, String value) throws Exception {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }
    public static void main(String[] args) throws Exception {
        new NciDataCaptureTest().capturedCreditsAndRepliesSupport476LongApdus();
        new NciDataCaptureTest().missingCreditFailsClosedWithoutResending();
        new NciDataCaptureTest().rejectMalformedOrUnownedFrames();
        System.out.println("NciDataCaptureTest: 3 passed");
    }
}
