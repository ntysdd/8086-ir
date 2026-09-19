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
    }
}
