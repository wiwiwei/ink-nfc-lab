package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import android.net.LocalServerSocket;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InterruptedIOException;

/** Cross-process guard. Never takes over another helper or changes NFC state. */
final class ScreenSessionLock implements AutoCloseable {
    static final String NAME = "com.kuroneko.epdprobe.screen-test";
    static final class BusyException extends IOException {
        BusyException(IOException cause) {
            super("另一个 Root NFC 会话仍在运行或恢复中；本次未开始。请等待上一会话结束后重试。", cause);
        }
    }
    interface Factory { AutoCloseable open() throws IOException; }
    interface Clock { long now(); void sleep(long millis) throws InterruptedException; }
    interface Cancelled { boolean get(); }
    private final AutoCloseable socket;

    private ScreenSessionLock(AutoCloseable socket) { this.socket = socket; }

    static ScreenSessionLock acquire(long waitMs) throws IOException, InterruptedException {
        return acquire(() -> new LocalServerSocket(NAME), new Clock() {
            @Override public long now() { return SystemClock.elapsedRealtime(); }
            @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
        }, () -> SessionControl.cancelled, waitMs);
    }

    static ScreenSessionLock acquire(Factory factory, Clock clock, Cancelled cancelled, long waitMs)
            throws IOException, InterruptedException {
        if (waitMs < 0 || waitMs > 3000) throw new IllegalArgumentException("Invalid session lock wait");
        long deadline = clock.now() + waitMs;
        while (true) {
            if (cancelled.get()) throw new InterruptedIOException("已停止等待上一会话；本次未开始，也未改变 NFC。");
            try { return new ScreenSessionLock(factory.open()); }
            catch (IOException error) {
                if (!addressInUse(error)) throw error;
                long remaining = deadline - clock.now();
                if (remaining <= 0) throw new BusyException(error);
                clock.sleep(Math.min(100, remaining));
            }
        }
    }

    private static boolean addressInUse(IOException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            if (message.contains("Address already in use") || message.contains("EADDRINUSE")) return true;
        }
        return false;
    }

    @Override public void close() throws Exception { socket.close(); }
}
