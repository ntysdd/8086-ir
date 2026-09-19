package i8086.testing;

import i8086.CompileError;
import i8086.SourcePos;

/**
 * Tests for the test harness itself. The harness decides whether every other
 * test passed, so it is the one piece of the build that has to be checked by
 * testing that it fails when it should.
 */
public final class HarnessTest {

    private HarnessTest() {
    }

    public static void register(Suite suite) {
        suite.add("Assert.assertEquals accepts equal values", HarnessTest::acceptsEqualValues);
        suite.add("Assert.assertEquals rejects different values", HarnessTest::rejectsDifferentValues);
        suite.add("Assert.assertThrows rejects nothing being thrown", HarnessTest::rejectsNoThrow);
        suite.add("Assert.assertThrows rejects the wrong type", HarnessTest::rejectsWrongType);
        suite.add("Assert.assertNull and assertNotNull check both ways", HarnessTest::checksNullness);
        suite.add("Assert.assertRefused accepts a positioned error", HarnessTest::acceptsPositionedError);
        suite.add("Assert.assertRefused rejects a silent body", HarnessTest::rejectsSilentBody);
        suite.add("Assert.assertRefused rejects the wrong position", HarnessTest::rejectsWrongPosition);
    }

    private static void acceptsEqualValues() {
        Assert.assertEquals("abc", "abc");
        Assert.assertEquals(7L, 7L);
        Assert.assertEquals(new SourcePos("a.ir", 1, 2), new SourcePos("a.ir", 1, 2));
        Assert.assertTrue(true, "true is true");
        Assert.assertFalse(false, "false is false");
    }

    private static void rejectsDifferentValues() {
        Assert.assertThrows(AssertionError.class, () -> Assert.assertEquals("expected", "actual"));
    }

    private static void rejectsNoThrow() {
        Assert.assertThrows(AssertionError.class, () -> {
            Assert.assertThrows(IllegalStateException.class, () -> {
                // Throws nothing at all, which assertThrows must refuse.
            });
        });
    }

    private static void rejectsWrongType() {
        Assert.assertThrows(AssertionError.class, () -> {
            Assert.assertThrows(IllegalArgumentException.class, () -> {
                throw new IllegalStateException("not the type that was expected");
            });
        });
    }

    private static void checksNullness() {
        Assert.assertNull(null, "nothing");
        Assert.assertNotNull("something", "something");
        Assert.assertThrows(AssertionError.class, () -> Assert.assertNull("x", "expected nothing"));
        Assert.assertThrows(AssertionError.class, () -> Assert.assertNotNull(null, "expected something"));
    }

    private static void acceptsPositionedError() {
        CompileError error = Assert.assertRefused("a.ir:3:7", () -> {
            throw new CompileError(new SourcePos("a.ir", 3, 7), "unsupported operand shape");
        });
        Assert.assertEquals("unsupported operand shape", error.getMessage());
        Assert.assertEquals("a.ir:3:7: unsupported operand shape", error.format());
    }

    private static void rejectsSilentBody() {
        Assert.assertThrows(AssertionError.class, () -> {
            Assert.assertRefused("a.ir:1:1", () -> {
                // Refuses nothing.
            });
        });
    }

    private static void rejectsWrongPosition() {
        Assert.assertThrows(AssertionError.class, () -> {
            Assert.assertRefused("a.ir:1:1", () -> {
                throw new CompileError(new SourcePos("b.ir", 2, 3), "elsewhere");
            });
        });
    }
}
