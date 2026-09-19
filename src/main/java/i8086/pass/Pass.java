package i8086.pass;

import i8086.ssa.SsaForm;

/**
 * One transformation of a module in SSA form.
 *
 * <p>A pass takes a form and answers with a form. It does not verify: the
 * pipeline verifies before and after it, which is what makes "every
 * transformation runs on verified input and has its output verified"
 * ({@code AGENTS.md}, invariant 1) one statement in one place instead of a claim
 * each pass makes about itself.
 *
 * <p>A pass keeps the module it was given working on. The form is a value — its
 * blocks, its φ's and its statements are — so a pass that changes one thing builds
 * the form it means and leaves the old one alone, and two passes in a row cannot
 * interfere by accident.
 *
 * <p>What a pass may assume about its input is everything the SSA verifier checks
 * and nothing else: one definition per version, one version of a variable reaching
 * each use, the φ's filled in from the edges that arrive. What it may <em>not</em>
 * assume is written down in {@code docs/ssa.md} — in particular that two
 * occurrences of a variable's undefined value are the same value, and that a value
 * an inline assembly block depends on is dead because the block does not say it
 * reads it.
 */
public interface Pass {

    /** What this pass is called: the name the pipeline listing and the README use. */
    String name();

    /** The form with this transformation applied. */
    SsaForm run(SsaForm form);
}
