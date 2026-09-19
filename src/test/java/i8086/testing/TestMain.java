package i8086.testing;

/**
 * The one test entry point. {@code build.bat} runs it, and its exit code is the
 * build's: any failing test makes the build red.
 */
public final class TestMain {

    private TestMain() {
    }

    public static void main(String[] args) {
        Suite suite = new Suite();
        AllTests.register(suite);
        boolean passed = suite.run(System.out);
        System.exit(passed ? 0 : 1);
    }
}
