package i8086.ir;

import i8086.CompileError;
import i8086.testing.Assert;
import i8086.testing.Suite;
import i8086.target.Targets;

/**
 * Tests for {@code pad}, the bytes that exist in the image and mean nothing.
 *
 * <p>Two forms, one idea ({@code docs/ir.md} §10.2): {@code pad 32} says how many
 * bytes when the text can say so, and {@code pad to 510} says how long the image is
 * when only the layout can. The second one is why this is not a byte list — the
 * length of the code before it is not known until it has been placed, and a pass
 * that selects a smaller instruction can change it.
 */
public final class PadTest {

    private static final String HEAD = "target 8086\norg 0x7c00\nentry main\n\nmain:\n";

    private PadTest() {
    }

    public static void register(Suite suite) {
        suite.add("Pad writes a count of bytes", PadTest::writesACount);
        suite.add("Pad writes a fill byte", PadTest::writesAFill);
        suite.add("Pad to reaches a length, not an address", PadTest::reachesALength);
        suite.add("Pad is a named place like any other", PadTest::isANamedPlace);
        suite.add("Pad round-trips through the printer", PadTest::roundTrips);
        suite.add("A variable may be called pad", PadTest::padIsStillAName);
        suite.add("Pad refuses a count that is not a count", PadTest::refusesBadCounts);
        suite.add("Pad refuses a fill that is not a byte", PadTest::refusesBadFill);
        suite.add("Pad goes into the assembly text and back out of it", PadTest::reachesAssembly);
    }

    private static Module parse(String body) {
        return IrParser.parse("test.ir", HEAD + body);
    }

    private static String printed(String body) {
        return IrPrinter.print(parse(body));
    }

    private static String became(String body) {
        String text = printed(body);
        return text.substring(text.indexOf("\nmain:\n") + "\nmain:\n".length());
    }

    private static void writesACount() {
        Assert.assertEquals("pad 0x20\npad 0x20, 0x90\n",
                became("    pad 32\n"
                        + "    pad 32, 0x90\n"));
    }

    private static void writesAFill() {
        Assert.assertEquals("pad 0x190, 0x90\n",
                became("    pad 400, 0x90\n"));
    }

    private static void reachesALength() {
        // The number is how long the image is at that point, not an address: the same
        // line is a 512-byte sector at any load address, which is what makes a boot
        // sector written here still a boot sector when `org` changes.
        Assert.assertEquals("pad to 0x1fe\ndw 0xaa55\n",
                became("    pad to 510\n"
                        + "    dw 0xAA55\n"));
        // And with a fill, and with a label on it, which gets a blank line the way any
        // named item does.
        Assert.assertEquals("\nbuf: pad to 0x1fe, 0x90\n",
                became("buf: pad to 510, 0x90\n"));
    }

    private static void isANamedPlace() {
        // A label on a pad names an address, like a label on data: the padding is part
        // of the image, so what follows it is somewhere.
        Assert.assertEquals("    var p: i16\n"
                        + "    p = buf\n"
                        + "    word [buf + 2] = 1\n"
                        + "\n"
                        + "buf: pad 0x20\n",
                became("    var p: i16\n"
                        + "    p = buf\n"
                        + "    word [buf + 2] = 1\n"
                        + "buf: pad 32\n"));
    }

    private static void roundTrips() {
        String body = "    pad 32\n"
                + "    pad 64, 0xFF\n"
                + "buf: pad to 510\n"
                + "    dw 0xAA55\n";
        String once = printed(body);
        Assert.assertEquals(once, IrPrinter.print(IrParser.parse("test.ir", once)));
    }

    private static void padIsStillAName() {
        // Nothing is reserved (docs/ir.md §3.1): 'pad' begins a piece of padding where a
        // piece of padding can begin, and is a name everywhere else — which is what the
        // '=' or ':' in front of it, or the lack of one, decides.
        Assert.assertEquals("    var $pad: i16\n"
                        + "    $pad = 1\n"
                        + "pad 0x20\n",
                became("    var pad: i16\n"
                        + "    pad = 1\n"
                        + "    pad 32\n"));
        Assert.assertEquals("\n$to: pad 0x10\n"
                        + "    var $to: i16\n"
                        + "    $to = 1\n",
                became("to: pad 16\n"
                        + "    var to: i16\n"
                        + "    to = 1\n"));
    }

    private static void refusesBadCounts() {
        // A negative count cannot be written at all: the surface has no negative
        // literals (docs/ir.md §12), so the verifier's own check on the number is
        // there for a pass and not for a writer.
        Assert.assertTrue(refusal("    pad -1\n").contains("number of bytes"),
                refusal("    pad -1\n"));
        Assert.assertTrue(refusal("    pad 0x10000\n").contains("0xFFFF"),
                refusal("    pad 0x10000\n"));
    }

    private static void refusesBadFill() {
        Assert.assertTrue(refusal("    pad 32, 0x100\n").contains("one byte"),
                refusal("    pad 32, 0x100\n"));
    }

    private static void reachesAssembly() {
        // A pad is not code and not a value, so nothing a pass does may remove it: the
        // image is what it is (docs/ssa.md §7). The dead variable here is removed and
        // the padding is not.
        String assembly = i8086.Compiler.compile("t.ir", HEAD
                + "    var unused: i16\n"
                + "    unused = 1\n"
                + "    pad 32, 0x90\n"
                + "    pad to 510\n"
                + "    dw 0xAA55\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("times 0x20 db 0x90\n"), assembly);
        Assert.assertTrue(assembly.contains("times 0x1fe-($-$$) db 0\n"), assembly);
        Assert.assertFalse(assembly.contains("unused"), assembly);
    }

    /** Parsing and verifying, because what a writer gets is both of them. */
    private static String refusal(String body) {
        return Assert.assertThrows(CompileError.class,
                () -> IrVerifier.verify(parse(body), Targets.byName("8086"))).getMessage();
    }
}
