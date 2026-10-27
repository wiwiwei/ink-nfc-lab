package dev.inknfclab.thn31;

import java.util.Arrays;
import java.util.Objects;

final class TestSupport {
    interface Throwing { void run() throws Exception; }
    static void fail(String message) { throw new AssertionError(message); }
    static void assertTrue(boolean value) { if (!value) fail("Expected true"); }
    static void assertFalse(boolean value) { assertTrue(!value); }
    static void assertEquals(long expected, long actual) { if (expected != actual) fail(expected + " != " + actual); }
    static void assertEquals(String expected, String actual) { if (!Objects.equals(expected, actual)) fail(expected + " != " + actual); }
    static void assertSame(Object expected, Object actual) { if (expected != actual) fail("Identity mismatch"); }
    static void assertArrayEquals(byte[] expected, byte[] actual) { if (!Arrays.equals(expected, actual)) fail("Array mismatch"); }
    static <T extends Throwable> T assertThrows(Class<T> type, Throwing action) {
        try { action.run(); } catch (Throwable error) {
            if (type.isInstance(error)) return type.cast(error);
            throw new AssertionError("Wrong exception", error);
        }
        throw new AssertionError("Expected " + type.getName());
    }
}
