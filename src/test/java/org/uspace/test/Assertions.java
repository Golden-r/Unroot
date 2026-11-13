package org.uspace.test;

import java.util.Objects;

/** The handful of assertions these tests need, with messages that name the expected value. */
public final class Assertions {

    private Assertions() {
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            fail(message);
        }
    }

    public static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    public static void assertNull(Object actual, String message) {
        if (actual != null) {
            fail(message + " — expected null but was <" + actual + ">");
        }
    }

    public static void assertNotNull(Object actual, String message) {
        if (actual == null) {
            fail(message + " — expected a value but was null");
        }
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            fail(message + "\n          expected: <" + expected + ">\n          actual:   <" + actual + ">");
        }
    }

    public static void assertNotEquals(Object unexpected, Object actual, String message) {
        if (Objects.equals(unexpected, actual)) {
            fail(message + " — both were <" + actual + ">");
        }
    }

    public static void assertContains(String haystack, String needle, String message) {
        if (haystack == null || !haystack.contains(needle)) {
            fail(message + "\n          looked for: <" + needle + ">\n          in:         <" + haystack + ">");
        }
    }

    public static <T extends Throwable> T assertThrows(Class<T> expected, TestRunner.Body body, String message) {
        try {
            body.run();
        } catch (Throwable t) {
            if (expected.isInstance(t)) {
                return expected.cast(t);
            }
            fail(message + " — expected " + expected.getSimpleName() + " but got " + t.getClass().getName() + ": " + t.getMessage());
        }
        fail(message + " — expected " + expected.getSimpleName() + " but nothing was thrown");
        throw new AssertionError("unreachable");
    }
}
