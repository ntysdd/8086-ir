package i8086;

import i8086.testing.Assert;
import i8086.testing.Suite;

/**
 * Tests for the position type and the error that carries it. Everything the
 * compiler refuses travels through these two, so they are checked first.
 */
public final class SourcePosTest {

    private SourcePosTest() {
    }

    public static void register(Suite suite) {
        suite.add("SourcePos prints as file:line:column", SourcePosTest::printsAsFileLineColumn);
        suite.add("SourcePos treats equal places as equal", SourcePosTest::treatsEqualPlacesAsEqual);
        suite.add("SourcePos refuses a line below 1", SourcePosTest::refusesLineBelowOne);
        suite.add("SourcePos refuses a column below 1", SourcePosTest::refusesColumnBelowOne);
        suite.add("SourcePos refuses a missing file name", SourcePosTest::refusesMissingFile);
        suite.add("CompileError keeps its position and message", SourcePosTest::errorKeepsPosition);
        suite.add("CompileError formats the position first", SourcePosTest::errorFormatsPositionFirst);
    }

    private static void printsAsFileLineColumn() {
        Assert.assertEquals("hello.ir:3:7", new SourcePos("hello.ir", 3, 7).toString());
    }

    private static void treatsEqualPlacesAsEqual() {
        SourcePos here = new SourcePos("hello.ir", 3, 7);
        Assert.assertEquals(here, new SourcePos("hello.ir", 3, 7));
        Assert.assertEquals(here.hashCode(), new SourcePos("hello.ir", 3, 7).hashCode());
        Assert.assertFalse(here.equals(new SourcePos("hello.ir", 3, 8)), "column differs");
        Assert.assertFalse(here.equals(new SourcePos("other.ir", 3, 7)), "file differs");
        Assert.assertFalse(here.equals(null), "nothing is not a position");
    }

    private static void refusesLineBelowOne() {
        Assert.assertThrows(IllegalArgumentException.class, () -> new SourcePos("hello.ir", 0, 1));
    }

    private static void refusesColumnBelowOne() {
        Assert.assertThrows(IllegalArgumentException.class, () -> new SourcePos("hello.ir", 1, 0));
    }

    private static void refusesMissingFile() {
        Assert.assertThrows(NullPointerException.class, () -> new SourcePos(null, 1, 1));
    }

    private static void errorKeepsPosition() {
        SourcePos where = new SourcePos("hello.ir", 12, 4);
        CompileError error = new CompileError(where, "unknown mnemonic");
        Assert.assertEquals(where, error.position());
        Assert.assertEquals("unknown mnemonic", error.getMessage());
    }

    private static void errorFormatsPositionFirst() {
        CompileError error = new CompileError(new SourcePos("hello.ir", 12, 4), "unknown mnemonic");
        Assert.assertEquals("hello.ir:12:4: unknown mnemonic", error.format());
    }
}
