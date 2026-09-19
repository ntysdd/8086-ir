package i8086;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one compile had to say about a program without refusing it.
 *
 * <p>Per run, and deliberately not static: two compilations in one process are two
 * compilations, and what the first found must not turn up in the second's output
 * ({@code AGENTS.md}, "no mutable global state"). It is handed to the stage that finds
 * something to say and read by whoever reports diagnostics — the command line writes
 * each one to standard error — which is also why it is a parameter of the compilation
 * rather than something the compiler decides to print itself.
 *
 * <p>Warnings are recorded in the order the program is walked, so the same input gives
 * the same warnings in the same order ({@code AGENTS.md}, invariant 6).
 */
public final class Warnings {

    private final List<Warning> warnings = new ArrayList<Warning>();

    /** Records one thing about one place. */
    public void add(SourcePos where, String message) {
        warnings.add(new Warning(where, message));
    }

    /** Everything recorded, in the order it was found. */
    public List<Warning> all() {
        return Collections.unmodifiableList(warnings);
    }
}
