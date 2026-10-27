package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import static dev.inknfclab.thn31.TestSupport.*;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.BindException;
import java.util.concurrent.atomic.AtomicInteger;


public final class ScreenSessionLockTest {
    private static final class Clock implements ScreenSessionLock.Clock {
        long now;
        @Override public long now() { return now; }
        @Override public void sleep(long millis) { now += millis; }
    }
    public void waitsForPreviousOwnerAndReleasesItsOwnLock() throws Exception {
        Clock clock = new Clock(); AtomicInteger attempts = new AtomicInteger(), closed = new AtomicInteger();
        try (ScreenSessionLock lock = ScreenSessionLock.acquire(() -> {
            if (attempts.incrementAndGet() < 4) throw new IOException("Address already in use");
            return () -> { closed.incrementAndGet(); };
        }, clock, () -> false, 2500)) {
            assertEquals(300, clock.now); assertEquals(0, closed.get());
        }
        assertEquals(1, closed.get());
    }
    public void persistentOwnerIsBusyAfterBoundedWait() throws Exception {
        Clock clock = new Clock();
        try {
            ScreenSessionLock.acquire(() -> { throw new IOException("bind failed: EADDRINUSE"); }, clock, () -> false, 2500);
            fail("Must not acquire another owner's socket");
        } catch (ScreenSessionLock.BusyException expected) {
            assertEquals(2500, clock.now);
            assertTrue(expected.getMessage().contains("本次未开始"));
        }
    }
    public void otherBindErrorsAreNotMisreportedAsBusyOrRetried() throws Exception {
        Clock clock = new Clock(); IOException denied = new BindException("Permission denied");
        try {
            ScreenSessionLock.acquire(() -> { throw denied; }, clock, () -> false, 2500);
            fail("Expected original bind error");
        } catch (IOException expected) { assertSame(denied, expected); assertEquals(0, clock.now); }
    }
    public void cancellationStopsWaitingWithoutOpeningAnyNewSocket() throws Exception {
        Clock clock = new Clock(); AtomicInteger attempts = new AtomicInteger();
        try {
            ScreenSessionLock.acquire(() -> { attempts.incrementAndGet(); throw new IOException("Address already in use"); },
                    clock, () -> clock.now >= 200, 2500);
            fail("Must stop waiting");
        } catch (InterruptedIOException expected) { assertEquals(2, attempts.get()); assertEquals(200, clock.now); }
    }
    public void passiveCheckDoesNotWaitOrTakeAnOccupiedLock() throws Exception {
        Clock clock = new Clock();
        try {
            ScreenSessionLock.acquire(() -> { throw new IOException("Address already in use"); }, clock, () -> false, 0);
            fail("Expected busy");
        } catch (ScreenSessionLock.BusyException expected) { assertEquals(0, clock.now); }
    }
    public static void main(String[] args) throws Exception {
        new ScreenSessionLockTest().waitsForPreviousOwnerAndReleasesItsOwnLock();
        new ScreenSessionLockTest().persistentOwnerIsBusyAfterBoundedWait();
        new ScreenSessionLockTest().otherBindErrorsAreNotMisreportedAsBusyOrRetried();
        new ScreenSessionLockTest().cancellationStopsWaitingWithoutOpeningAnyNewSocket();
        new ScreenSessionLockTest().passiveCheckDoesNotWaitOrTakeAnOccupiedLock();
        System.out.println("ScreenSessionLockTest: 5 passed");
    }
}
