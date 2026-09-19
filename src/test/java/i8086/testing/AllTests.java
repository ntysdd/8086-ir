package i8086.testing;

/**
 * Every test, in the order they are run. There is no discovery and no
 * reflection: a new test class is invisible until it is named here.
 */
public final class AllTests {

    private AllTests() {
    }

    public static void register(Suite suite) {
        HarnessTest.register(suite);
        i8086.SourcePosTest.register(suite);
        i8086.asm.TokenizerTest.register(suite);
        i8086.ir.IrParserTest.register(suite);
        i8086.ir.IrSugarTest.register(suite);
        i8086.ir.InstructionStatementTest.register(suite);
        i8086.ir.IrVerifierTest.register(suite);
        i8086.ssa.CfgTest.register(suite);
        i8086.ssa.DominatorsTest.register(suite);
        i8086.ssa.SsaBuilderTest.register(suite);
        i8086.ssa.SsaVerifierTest.register(suite);
        i8086.ssa.OutOfSsaTest.register(suite);
        i8086.pass.ConstantPropagationTest.register(suite);
        i8086.pass.DeadValueEliminationTest.register(suite);
        i8086.pass.UnreadFlagsTest.register(suite);
        i8086.pass.PipelineTest.register(suite);
        i8086.emit.AsmEmitterTest.register(suite);
        i8086.target.I8086Test.register(suite);
        i8086.CompilerTest.register(suite);
    }
}
