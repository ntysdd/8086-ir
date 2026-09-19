package i8086.ssa;

import i8086.ir.IrParser;
import i8086.ir.IrPrinter;
import i8086.ir.IrVerifier;
import i8086.ir.Module;
import i8086.target.Targets;
import i8086.testing.Assert;
import i8086.testing.Suite;

/**
 * Tests for leaving the SSA form.
 *
 * <p>The claim being tested is the one that makes this step small: every version
 * goes back to the variable it is a version of, the φ's are dropped rather than
 * turned into copies, and the module that comes out is a module — one a person
 * could have written, and one the surface's own verifier accepts.
 */
public final class OutOfSsaTest {

    private OutOfSsaTest() {
    }

    public static void register(Suite suite) {
        suite.add("Versions become the variables they are versions of",
                OutOfSsaTest::renamesBack);
        suite.add("The value with no definition becomes the variable itself",
                OutOfSsaTest::renamesTheUndefinedValue);
        suite.add("A φ needs no copy", OutOfSsaTest::dropsPhis);
        suite.add("Labels and data are left alone", OutOfSsaTest::leavesLabelsAlone);
        suite.add("What comes out is a module the surface verifier accepts",
                OutOfSsaTest::isAModule);
        suite.add("What comes out prints and parses back the same",
                OutOfSsaTest::roundTrips);
    }

    private static Module module(String body) {
        Module parsed = IrParser.parse("test.ir",
                "target 8086\norg 0x100\nentry main\n\nmain:\n" + body);
        SsaForm form = SsaBuilder.build(parsed);
        return OutOfSsa.module(form);
    }

    private static void renamesBack() {
        Assert.assertEquals("target 8086\n"
                        + "org 0x100\n"
                        + "entry main\n"
                        + "\n"
                        + "main:\n"
                        + "    var x: u16\n"
                        + "    x = 1\n"
                        + "    x = eval(x + 1)\n"
                        + "    ret\n",
                IrPrinter.print(module("    var x: u16\n    x = 1\n    x = eval(x + 1)\n"
                        + "    ret\n")));
    }

    private static void renamesTheUndefinedValue() {
        Assert.assertEquals("target 8086\n"
                        + "org 0x100\n"
                        + "entry main\n"
                        + "\n"
                        + "main:\n"
                        + "    var x: u16\n"
                        + "    var y: u16\n"
                        + "    y = eval(x + 1)\n"
                        + "    ret\n",
                IrPrinter.print(module("    var x: u16\n    var y: u16\n"
                        + "    y = eval(x + 1)\n    ret\n")));
    }

    private static void dropsPhis() {
        // The φ merges two versions of i, and both of them are i, so the copy it
        // would have become is an identity. This is the whole reason there is no
        // edge splitting and no parallel-copy sequentialisation here.
        String body = "    var i: u16\n    var n: u16\n    i = 0\n    n = 3\n"
                + "    .while i < n\n        i = eval(i + 1)\n    .endw\n    ret\n";
        Module parsed = IrParser.parse("test.ir",
                "target 8086\norg 0x100\nentry main\n\nmain:\n" + body);
        SsaForm form = SsaBuilder.build(parsed);
        Assert.assertEquals(1L, form.phis(form.cfg().blocks().get(2)).size());
        Assert.assertEquals(IrPrinter.print(parsed), IrPrinter.print(OutOfSsa.module(form)));
    }

    private static void leavesLabelsAlone() {
        Assert.assertEquals("target 8086\n"
                        + "org 0x100\n"
                        + "entry main\n"
                        + "\n"
                        + "main:\n"
                        + "    var p: u16\n"
                        + "    p = msg\n"
                        + "    p = [msg]\n"
                        + "    ret\n"
                        + "\n"
                        + "msg: db \"hi\"\n",
                IrPrinter.print(module("    var p: u16\n    p = msg\n    p = [msg]\n"
                        + "    ret\n\nmsg: db \"hi\"\n")));
    }

    private static void isAModule() {
        IrVerifier.verify(module("    var x: u16\n    x = eval(x + 1)\n    ret\n"),
                Targets.byName("8086"));
    }

    private static void roundTrips() {
        Module once = module("    var x: u16\n    var y: u16\n    x = eval(x + 1)\n"
                + "    y = expr(x * 2)\n    ret\n");
        Module twice = IrParser.parse("test.ir", IrPrinter.print(once));
        Assert.assertEquals(IrPrinter.print(once), IrPrinter.print(twice));
    }
}
