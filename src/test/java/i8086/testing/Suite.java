package i8086.testing;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The test suite: plain methods, registered explicitly, run in the order they
 * were added so the report is deterministic. There is no JUnit here and no
 * reflection — a test that is not registered does not run, which is why the
 * list in {@link AllTests} is the one place a new test has to be named.
 */
public final class Suite {

    /** One registered test: a name, and the body to run. */
    private static final class Case {
        private final String name;
        private final Runnable body;

        Case(String name, Runnable body) {
            this.name = name;
            this.body = body;
        }
    }

    private static final int REPORTED_FRAMES = 4;

    private final List<Case> cases = new ArrayList<Case>();

    public void add(String name, Runnable body) {
        if (name == null || body == null) {
            throw new NullPointerException("test name and body are both required");
        }
        cases.add(new Case(name, body));
    }

    public int size() {
        return cases.size();
    }

    /**
     * Runs every test, prints one line per test plus a summary, and returns
     * whether all of them passed. The caller turns the result into the exit
     * code, so that a failing test is never a quiet failure.
     */
    public boolean run(PrintStream out) {
        int failed = 0;
        for (Case test : cases) {
            try {
                test.body.run();
                out.println("ok   " + test.name);
            } catch (Throwable failure) {
                failed++;
                out.println("FAIL " + test.name);
                report(out, failure);
            }
        }
        out.println();
        out.println(cases.size() + " tests, " + failed + " failed");
        return failed == 0;
    }

    private static void report(PrintStream out, Throwable failure) {
        String message = failure.getMessage();
        out.println("       " + failure.getClass().getSimpleName()
                + (message == null ? "" : ": " + message));
        StackTraceElement[] trace = failure.getStackTrace();
        int shown = Math.min(trace.length, REPORTED_FRAMES);
        for (int i = 0; i < shown; i++) {
            out.println("         at " + trace[i]);
        }
    }
}
