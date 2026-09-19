package i8086.testing;

import i8086.CompileError;

/**
 * Hand-written assertions. They are deliberately few and dependency-free: a
 * failure is an {@link AssertionError} carrying a message that says what was
 * expected and what turned up, and the suite reports it.
 */
public final class Assert {

    private Assert() {
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            fail(message);
        }
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) {
            fail(message);
        }
    }

    public static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            fail("expected <" + expected + ">, but was <" + actual + ">");
        }
    }

    public static void assertEquals(long expected, long actual) {
        if (expected != actual) {
            fail("expected <" + expected + ">, but was <" + actual + ">");
        }
    }

    public static void assertNull(Object value, String message) {
        if (value != null) {
            fail(message + ": expected nothing, but was <" + value + ">");
        }
    }

    public static void assertNotNull(Object value, String message) {
        if (value == null) {
            fail(message + ": expected something, but was nothing");
        }
    }

    /**
     * Asserts that the body is refused with a {@link CompileError} at exactly
     * the given position, and returns the error for further checks. This is the
     * shape every "unsupported input" test takes, and it insists on the position
     * because an error without one is not a finished error.
     */
    public static CompileError assertRefused(String expectedPosition, Runnable body) {
        CompileError error = assertThrows(CompileError.class, body);
        assertEquals(expectedPosition, error.position().toString());
        return error;
    }

    public static <T extends Throwable> T assertThrows(Class<T> type, Runnable body) {
        try {
            body.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return type.cast(thrown);
            }
            fail("expected " + type.getSimpleName() + ", but "
                    + thrown.getClass().getSimpleName() + " was thrown: " + thrown.getMessage());
        }
        fail("expected " + type.getSimpleName() + ", but nothing was thrown");
        return null; // unreachable: fail always throws
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }
}
