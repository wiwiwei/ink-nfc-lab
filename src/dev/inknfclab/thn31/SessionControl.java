package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

final class SessionControl {
    static volatile boolean cancelled;
    static volatile java.io.File stopFile;
    static volatile long deadline = Long.MAX_VALUE;
    static void check() {
        if (cancelled || (stopFile != null && stopFile.exists())
                || (deadline != Long.MAX_VALUE && android.os.SystemClock.elapsedRealtime() >= deadline))
            throw new IllegalStateException("会话已停止或超时；设备最终画面需确认");
    }
}
