package i8086.pass;

import i8086.ssa.SsaForm;
import i8086.ssa.SsaVerifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The passes, in the order they run, and the verification around them.
 *
 * <p>This list is the description of record for what the optimizer does, next to
 * the one in {@code README.md}: adding, removing or reordering a pass means
 * changing both, and a test that compares {@link #names()} against the same list
 * written out by hand is what makes forgetting the README a failing build rather
 * than a stale document.
 *
 * <p><b>Every pass runs on verified input and has its output verified.</b> The
 * verification before the first pass is the SSA verifier's, and after each pass it
 * is that same verifier again — which is the first invariant, and it is stated here
 * rather than inside each pass because a pass that verified itself would be
 * checking its own homework.
 *
 * <p>The order is the one the passes need rather than a total order they could be
 * run in. Constants first, because a value that is known is worth writing down
 * before anything counts readers. Then load folding, which takes a load away and
 * leaves the address it was computing with nobody reading it. Then dead value
 * elimination, which sees the uses both of them left behind. Then the flags, last,
 * because "nobody reads this" is a question about the program that the passes
 * before it have finished shaping.
 */
public final class Pipeline {

    private Pipeline() {
    }

    /**
     * A fresh set of passes, in the order they run.
     *
     * <p>Fresh because a pass may hold state while it works, and two runs of the
     * pipeline are two runs: nothing here is shared between them, so the same input
     * gives the same output every time (`AGENTS.md`, invariant 6).
     */
    public static List<Pass> passes() {
        List<Pass> passes = new ArrayList<Pass>();
        passes.add(new ConstantPropagation());
        passes.add(new LoadFolding());
        passes.add(new DeadValueElimination());
        passes.add(new UnreadFlags());
        return Collections.unmodifiableList(passes);
    }

    /** What the passes are called, in the order they run. */
    public static List<String> names() {
        List<String> names = new ArrayList<String>();
        for (Pass pass : passes()) {
            names.add(pass.name());
        }
        return Collections.unmodifiableList(names);
    }

    /** Runs the passes, verifying the form before and after each of them. */
    public static SsaForm run(SsaForm form) {
        SsaVerifier.verify(form);
        SsaForm current = form;
        for (Pass pass : passes()) {
            current = pass.run(current);
            SsaVerifier.verify(current);
        }
        return current;
    }
}
