package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Passive logcat observer, synchronized by a fresh marker before issuing RF_DISCOVER. */
final class LiveNciMonitor implements AutoCloseable {
    private final java.lang.Process child;
    private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>(128);
    private final CountDownLatch barrier = new CountDownLatch(1);
    private final String marker = "EPD_NCI_" + System.nanoTime();
    private volatile String failure;
    private volatile boolean closing;

    LiveNciMonitor() throws Exception {
        child = new ProcessBuilder("/system/bin/logcat", "-T", "1", "-v", "threadtime",
                "TmsNciX:D", "TmsNciR:D", "EpdLiveNci:I", "*:S").redirectErrorStream(true).start();
        Thread reader = new Thread(() -> {
            int budget = 0;
            boolean synchronizedStream = false;
            try (BufferedReader input = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    budget += line.length();
                    // Upstream literal-LZO upload has 476 APDUs, substantially more than the old 53-packet plan.
                    if (budget > 8 * 1024 * 1024 || line.length() > 8192) throw new IllegalStateException("实时日志超出采集上限");
                    if (line.contains(marker)) { synchronizedStream = true; barrier.countDown(); continue; }
                    if (synchronizedStream && PollEvidence.frame(line) != null && !lines.offer(line))
                        throw new IllegalStateException("实时通知队列已满");
                }
                if (!closing) failure = "实时日志进程提前结束";
            } catch (Exception error) { if (!closing) failure = error.toString(); }
        }, "epd-live-nci");
        reader.setDaemon(true);
        reader.start();
    }

    void synchronizeStream() throws Exception {
        // logcat startup can finish after the first marker; repeat only the passive marker.
        long until = SystemClock.elapsedRealtime() + 2000;
        do {
            if (failure != null) throw new IllegalStateException(failure);
            Log.i("EpdLiveNci", marker);
            if (barrier.await(100, TimeUnit.MILLISECONDS)) break;
        } while (SystemClock.elapsedRealtime() < until);
        if (barrier.getCount() != 0) throw new IllegalStateException("无法同步实时 NFC 日志，未开始轮询");
        if (failure != null) throw new IllegalStateException(failure);
    }

    SelectionGate awaitSelection() throws Exception {
        SelectionGate gate = new SelectionGate();
        long until = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < until) {
            if (failure != null) throw new IllegalStateException(failure);
            String line = lines.poll(50, TimeUnit.MILLISECONDS);
            if (line != null) gate.accept(PollEvidence.frame(line));
            if (gate.finished()) {
                // Consume already received commands before deciding whether manual selection is still valid.
                while ((line = lines.poll()) != null) gate.accept(PollEvidence.frame(line));
                if (failure != null) throw new IllegalStateException(failure);
                return gate;
            }
        }
        return gate;
    }

    PollEvidence.Frame next(long waitMs) throws Exception {
        if (failure != null) throw new IllegalStateException(failure);
        String line = lines.poll(Math.max(1, Math.min(waitMs, 100)), TimeUnit.MILLISECONDS);
        if (failure != null) throw new IllegalStateException(failure);
        return line == null ? null : PollEvidence.frame(line);
    }

    @Override public void close() {
        closing = true;
        child.destroy();
    }
}
