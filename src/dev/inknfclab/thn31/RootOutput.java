package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

/** Read only available pipe bytes: never waits for EOF held by an inherited descriptor. */
final class RootOutput {
    interface Sink {
        boolean line(String line) throws Exception;
        void waiting(long elapsedMs, long silentMs) throws Exception;
    }
    interface Clock { long now(); void sleep(long millis) throws InterruptedException; }
    static boolean collect(java.lang.Process process, long timeoutMs, Sink sink, Clock clock) throws Exception {
        InputStream input = process.getInputStream();
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long started = clock.now(), lastOutput = started, nextPulse = started + 5000;
        while (true) {
            if (clock.now() - started >= timeoutMs)
                throw new TimeoutException("Root 会话未在时限内返回；本轮结果与 NFC 恢复尚未确认");
            int available = input.available();
            if (available > 0) {
                int count = input.read(buffer, 0, Math.min(buffer.length, available));
                if (count < 0) break;
                lastOutput = clock.now();
                for (int i = 0; i < count; i++) {
                    if (buffer[i] == '\n') {
                        String line = new String(pending.toByteArray(), StandardCharsets.UTF_8);
                        pending.reset();
                        if (sink.line(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line)) return true;
                    } else {
                        if (pending.size() >= 256 * 1024) throw new IOException("Root output line exceeds limit");
                        pending.write(buffer[i]);
                    }
                }
                continue;
            }
            if (!process.isAlive()) {
                if (pending.size() > 0) return sink.line(new String(pending.toByteArray(), StandardCharsets.UTF_8));
                return false;
            }
            long now = clock.now();
            if (now >= nextPulse) { sink.waiting(now - started, now - lastOutput); nextPulse = now + 5000; }
            clock.sleep(50);
        }
        return false;
    }
}
