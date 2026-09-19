package i8086.pass;

import i8086.ir.IrParser;
import i8086.ir.IrPrinter;
import i8086.ir.IrVerifier;
import i8086.ir.Module;
import i8086.ssa.OutOfSsa;
import i8086.ssa.SsaForm;
import i8086.ssa.SsaPrinter;
import i8086.target.Targets;
import i8086.testing.Assert;
import i8086.testing.Suite;

import java.util.Arrays;

/**
 * Tests for the pipeline itself: the list of passes, the verification around them,
 * and leaving SSA again.
 *
 * <p>The list is compared against the same names written out by hand, so that
 * adding a pass means editing this test and the README in the same change
 * ({@code AGENTS.md}: "New passes are registered explicitly in the pipeline, and the
 * pass list in README.md is updated in the same change").
 */
public final class PipelineTest {

    private PipelineTest() {
    }

    public static void register(Suite suite) {
        suite.add("The pipeline is the list the README lists", PipelineTest::listsItsPasses);
        suite.add("Running the pipeline twice changes nothing the second time",
                PipelineTest::reachesAFixedPoint);
        suite.add("Leaving SSA gives a module the surface verifier accepts",
                PipelineTest::leavingSsaIsVerified);
        suite.add("The pipeline is deterministic", PipelineTest::deterministic);
    }

    private static void listsItsPasses() {
        Assert.assertEquals(Arrays.asList("constant propagation", "dead value elimination",
                        "unread flags").toString(),
                Pipeline.names().toString());
    }

    private static void reachesAFixedPoint() {
        SsaForm once = Pipeline.run(build());
        Assert.assertEquals(SsaPrinter.print(once),
                SsaPrinter.print(Pipeline.run(once)));
    }

    /**
     * Leaving SSA is a transformation too, so its output is checked like any input:
     * the surface's own rules, on the module the passes left. If this ever throws,
     * some pass has produced something that is not a program.
     */
    private static void leavingSsaIsVerified() {
        Module module = OutOfSsa.module(Pipeline.run(build()));
        IrVerifier.verify(module, Targets.byName("8086"));
        Assert.assertTrue(IrPrinter.print(module).contains("    jmp $lbl1\n"),
                "the loop is still a loop: " + IrPrinter.print(module));
    }

    private static void deterministic() {
        Assert.assertEquals(SsaPrinter.print(Pipeline.run(build())),
                SsaPrinter.print(Pipeline.run(build())));
    }

    private static SsaForm build() {
        return ConstantPropagationTest.build("    var i: u16\n    var n: u16\n"
                + "    i = 0\n    n = 3\n"
                + "    .while i < n\n        i = eval(i + 1)\n    .endw\n    ret\n");
    }
}
