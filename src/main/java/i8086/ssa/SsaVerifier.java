package i8086.ssa;

import i8086.CompileError;
import i8086.SourcePos;
import i8086.ir.Item;
import i8086.ir.Names;
import i8086.ir.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that a form really is SSA, before anything acts on it.
 *
 * <p>Being in SSA is a property, not a naming convention, and the property is
 * this: <b>exactly one version of a variable reaches any use of it</b>. The check
 * here asks that question directly — it works out which definitions reach each
 * point, following the graph the way execution does, and refuses a point where
 * two of them arrive and no φ merges them. That is what a wrong φ placement looks
 * like from the outside, which is the only way to catch it: a missing φ leaves
 * the code reading a value that is not always the right one, and a dominance
 * check alone would accept it happily.
 *
 * <p>Around that, the smaller questions a form has to answer for itself:
 *
 * <ul>
 *   <li>every version is defined exactly once, so a name means one thing;
 *   <li>every φ has one operand per predecessor, each a version of the variable
 *       the φ merges;
 *   <li>every name in an item is a version or a label — a variable still standing
 *       under its declared name is one name meaning several values, which is the
 *       ambiguity SSA exists to remove;
 *   <li>the version a φ or a statement defines is the one the form has recorded.
 * </ul>
 *
 * <p>What is <em>not</em> checked here is whether reading the flags was
 * legitimate: that is the surface's rule and it is enforced before this point
 * ({@code docs/ir.md} §4.3). This verifier answers for SSA, and the flags are
 * ordinary values as far as it is concerned.
 *
 * <p>Nothing here knows a target, a register or an instruction. It reasons about
 * versions, blocks and edges, so it holds for any machine.
 */
public final class SsaVerifier {

    private final SsaForm form;
    private final Cfg cfg;
    private final Names names;

    private SsaVerifier(SsaForm form) {
        this.form = form;
        this.cfg = form.cfg();
        this.names = Names.of(form.module());
    }

    public static void verify(SsaForm form) {
        new SsaVerifier(form).verifyAll();
    }

    private void verifyAll() {
        checkPhis();
        checkDefinitions();
        checkNames();
        checkFlagReads();
        checkReaching();
    }

    // --- the shapes of the pieces ------------------------------------------

    private void checkPhis() {
        for (Block block : cfg.blocks()) {
            for (Phi phi : form.phis(block)) {
                require(phi.name() != null && form.isVersion(phi.name()), phi.position(),
                        "a φ for '" + phi.variable() + "' was placed without being named");
                require(phi.variable().equals(form.variableOf(phi.name())), phi.position(),
                        "the φ defines '" + phi.name() + "', which is a version of '"
                                + form.variableOf(phi.name()) + "' and not of '"
                                + phi.variable() + "'");
                require(sameType(phi.type(), form.typeOf(phi.name())), phi.position(),
                        "the φ for '" + phi.variable() + "' is recorded with two different types");
                require(phi.operands().size() == block.predecessors().size(), phi.position(),
                        "the φ for '" + phi.variable() + "' has " + phi.operands().size()
                                + " operands, but " + block.predecessors().size()
                                + " paths arrive at this block; a φ takes one operand per path");
                for (String operand : phi.operands()) {
                    if (form.isUndef(operand)) {
                        require(phi.variable().equals(form.variableOf(operand)), phi.position(),
                                "the φ for '" + phi.variable() + "' takes '" + operand
                                        + "', which is the undefined value of '"
                                        + form.variableOf(operand) + "'");
                        continue;
                    }
                    require(form.isVersion(operand), phi.position(),
                            "the φ for '" + phi.variable() + "' takes '" + operand
                                    + "', and nothing defines it");
                    require(phi.variable().equals(form.variableOf(operand)), phi.position(),
                            "the φ for '" + phi.variable() + "' takes '" + operand
                                    + "', which is a version of '" + form.variableOf(operand)
                                    + "' instead");
                    require(sameType(phi.type(), form.typeOf(operand)), phi.position(),
                            "the φ for '" + phi.variable() + "' merges values of two widths");
                }
            }
        }
    }

    /**
     * Every version has one definition, and every definition is a version.
     *
     * <p>A φ defines its name and so does the place an item writes; the flags
     * version a statement carries defines the flags. Anything defined twice is a
     * name that means two things, and anything in the version tables with nothing
     * defining it is a definition that was lost.
     */
    private void checkDefinitions() {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Block block : cfg.blocks()) {
            for (Phi phi : form.phis(block)) {
                count(counts, phi.name(), phi.position());
            }
            for (SsaStatement statement : form.statements(block)) {
                require(statement.definedFlags().keySet()
                                .equals(Effects.flagsDefined(statement.item())),
                        statement.item().position(),
                        "this statement disagrees with itself about the flags: it answers with "
                                + (statement.definedFlags().isEmpty()
                                ? "none"
                                : statement.definedFlags().keySet().toString()));
                String written = Effects.writtenVariable(statement.item());
                if (written != null) {
                    count(counts, written, statement.item().position());
                }
                for (String version : statement.definedFlags().values()) {
                    count(counts, version, statement.item().position());
                }
            }
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            require(entry.getValue().intValue() == 1, form.positionOf(entry.getKey()),
                    "'" + entry.getKey() + "' is defined " + entry.getValue() + " times; a "
                            + "version is one value and has one definition");
        }
        for (String version : form.versions()) {
            require(counts.containsKey(version), form.positionOf(version),
                    "'" + version + "' is a version that nothing defines");
        }
    }

    private void count(Map<String, Integer> counts, String version, SourcePos where) {
        require(form.isVersion(version), where,
                "this defines '" + version + "', which is not a version of anything");
        Integer seen = counts.get(version);
        counts.put(version, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
    }

    /**
     * Every name an item mentions means one thing.
     *
     * <p>After renaming, a name in an item is a version, the undefined value, or a
     * label, and nothing else. A declared variable name still standing in an item
     * is the failure this catches: a use that was never renamed reads whichever
     * definition happens to be live at that point.
     */
    private void checkNames() {
        for (Block block : cfg.blocks()) {
            for (SsaStatement statement : form.statements(block)) {
                for (Effects.Occurrence occurrence : Effects.occurrences(statement.item())) {
                    String name = occurrence.name();
                    if (name == null || form.isVersion(name) || form.isUndef(name)
                            || names.isLabel(name)) {
                        // No name at all is an access with nothing in the brackets:
                        // there is no value to have a version, and the address is the
                        // assembler's to resolve.
                        continue;
                    }
                    if (names.isVariable(name)) {
                        throw new CompileError(occurrence.position(),
                                "'" + name + "' is still the name it was declared with, so this "
                                        + "use was never renamed; in SSA a variable is read "
                                        + "through a version");
                    }
                    throw new CompileError(occurrence.position(),
                            "unknown name '" + name + "': no version or label has that name");
                }
            }
        }
    }

    // --- the property ------------------------------------------------------

    /**
     * Every flag a statement reads has been defined on <b>every</b> path to it ({@code docs/ir.md}
     * §4.3).
     *
     * <p>This is not the same question as the one {@link #checkReaching} asks. That one is about SSA:
     * does exactly one version reach this use. This one is about the program: a flag is a value the
     * machine has, and a statement that reads one — a branch reading the arithmetic flags, a copy
     * reading the direction flag — needs it to have been set however the program got here. A join
     * where one path set it and the other did not has exactly one version reaching the use as far as
     * this form is concerned, and reading it there is a flag that is whatever the machine had.
     *
     * <p>The surface refuses the same thing in source order before there is a form to ask
     * ({@code IrVerifier}), and it does it soundly but bluntly: it cannot see that a label is only
     * reached one way, so it refuses programs that are right. This asks it of the graph, which is
     * where {@code docs/ir.md} §4.3 says the question belongs — and it is what makes {@code cld}
     * usable at all, since the copy it is for is usually a stretch of code away from it.
     *
     * <p>Nothing here knows a target: the flags are names, and which names a statement reads and
     * defines is the statement's own business ({@code Effects}).
     */
    private void checkFlagReads() {
        List<Set<String>> entering = new ArrayList<Set<String>>();
        for (Block block : cfg.blocks()) {
            // Optimistic to start with, because this is an intersection: a flag is defined on entering
            // a block only when it is defined on leaving every predecessor. The entry block has no
            // predecessors, so nothing is defined there, and a block nothing reaches reads nothing.
            Set<String> defined = new LinkedHashSet<String>();
            if (block != cfg.entry() && cfg.isReachable(block)) {
                defined.addAll(Names.flagNames());
            }
            entering.add(defined);
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Block block : cfg.blocks()) {
                if (block == cfg.entry() || !cfg.isReachable(block)) {
                    continue;
                }
                Set<String> from = new LinkedHashSet<String>(Names.flagNames());
                for (Block predecessor : block.predecessors()) {
                    if (cfg.isReachable(predecessor)) {
                        from.retainAll(leavingFlags(predecessor, entering.get(predecessor.index())));
                    }
                }
                if (!from.equals(entering.get(block.index()))) {
                    entering.set(block.index(), from);
                    changed = true;
                }
            }
        }

        for (Block block : cfg.blocks()) {
            Set<String> defined = new LinkedHashSet<String>(entering.get(block.index()));
            for (SsaStatement statement : form.statements(block)) {
                for (String flag : Effects.flagsRead(statement.item())) {
                    require(defined.contains(flag), statement.item().position(),
                            "'" + flag + "' is read here, and nothing on every way here defines it: "
                                    + "a statement that sets it has to come first (docs/ir.md §4.3)");
                }
                defined.addAll(statement.definedFlags().keySet());
                defined.removeAll(Effects.flagsKilled(statement.item()));
            }
        }
    }

    /** Which flags are defined after a block, given which ones were defined before it. */
    private Set<String> leavingFlags(Block block, Set<String> entering) {
        Set<String> defined = new LinkedHashSet<String>(entering);
        for (SsaStatement statement : form.statements(block)) {
            defined.addAll(statement.definedFlags().keySet());
            defined.removeAll(Effects.flagsKilled(statement.item()));
        }
        return defined;
    }

    /**
     * Exactly one version of a variable reaches every use.
     *
     * <p>Which definitions reach a point is worked out the way the machine would:
     * everything a predecessor leaves behind arrives, a definition replaces every
     * version of the variable it defines, and going round a loop has to give the
     * same answer as coming into it, so the computation is repeated until it
     * settles.
     *
     * <p>A block no path reaches is given nothing rather than what its
     * predecessors leave behind, and so is the entry block: execution begins
     * there, so nothing arrives at it. That is what lets a back edge to the entry
     * be handled by a φ like any other edge.
     */
    private void checkReaching() {
        List<Set<String>> reaching = new ArrayList<Set<String>>();
        for (int i = 0; i < cfg.blocks().size(); i++) {
            reaching.add(new LinkedHashSet<String>());
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Block block : cfg.blocks()) {
                Set<String> leaving = after(block, arrivingAt(block, reaching));
                if (!leaving.equals(reaching.get(block.index()))) {
                    reaching.set(block.index(), leaving);
                    changed = true;
                }
            }
        }

        for (Block block : cfg.blocks()) {
            checkBlock(block, arrivingAt(block, reaching));
        }
        checkPhiOperands(reaching);
    }

    /** What arrives at a block: what its reachable predecessors leave behind. */
    private Set<String> arrivingAt(Block block, List<Set<String>> reaching) {
        Set<String> arriving = new LinkedHashSet<String>();
        if (block == cfg.entry() || !cfg.isReachable(block)) {
            return arriving;
        }
        for (Block predecessor : block.predecessors()) {
            if (cfg.isReachable(predecessor)) {
                arriving.addAll(reaching.get(predecessor.index()));
            }
        }
        return arriving;
    }

    /** What a block leaves behind: what arrives, with its own definitions replacing it. */
    private Set<String> after(Block block, Set<String> arriving) {
        Set<String> leaving = new LinkedHashSet<String>(arriving);
        for (Phi phi : form.phis(block)) {
            drop(leaving, phi.variable());
            leaving.add(phi.name());
        }
        for (SsaStatement statement : form.statements(block)) {
            define(leaving, statement);
        }
        return leaving;
    }

    /**
     * Walks a block, asking at every use whether one version reaches it.
     *
     * <p>What an item reads it reads before what it writes ({@code docs/ir.md}
     * §5.1), so an item's definitions are applied after its uses have been
     * checked. That is what makes {@code x#4 = eval(x#3 + 1)} read the version it
     * says it reads and define the one it says it defines.
     */
    private void checkBlock(Block block, Set<String> arriving) {
        Set<String> current = new LinkedHashSet<String>(arriving);
        for (Phi phi : form.phis(block)) {
            drop(current, phi.variable());
            current.add(phi.name());
        }
        for (SsaStatement statement : form.statements(block)) {
            for (Effects.Occurrence occurrence : Effects.occurrences(statement.item())) {
                if (occurrence.written()) {
                    continue;
                }
                if (!form.isValue(occurrence.name())) {
                    // A label used as an address, or nothing at all: an address is not
                    // a value and has no versions. That it is a name at all is
                    // checkNames' business.
                    continue;
                }
                checkUse(current, null, occurrence.name(), occurrence.position());
            }
            for (String flag : Effects.flagsRead(statement.item())) {
                checkUse(current, flag, null, statement.item().position());
            }
            define(current, statement);
        }
    }

    /**
     * One use, checked against what reaches it.
     *
     * <p>Three cases, and they are different questions. A branch reads the flags,
     * which the surface never names, so what it reads is whatever version is in
     * force and there has to be exactly one of those. A use of a variable's
     * undefined value is allowed when nothing reaches at all: the module lets a
     * variable be read before anything writes it ({@code docs/ir.md} §3.1), and a
     * value this compiler cannot know is not the same as a value it got wrong. A
     * use of a version is allowed exactly when that version is the one that
     * arrives — not another one, and not nothing.
     */
    private void checkUse(Set<String> current, String flag, String version, SourcePos where) {
        if (version == null) {
            List<String> reached = versionsOf(current, flag);
            require(reached.size() == 1, where, "'" + flag + "' is read here, but what reaches "
                    + "is " + describe(reached, flag));
            return;
        }
        String variable = form.variableOf(version);
        List<String> reached = versionsOf(current, variable);
        if (form.isUndef(version)) {
            require(reached.isEmpty(), where, "this reads " + version + ", but "
                    + describe(reached, variable) + " of '" + variable + "' reaches it");
            return;
        }
        require(reached.size() == 1 && reached.contains(version), where,
                "this reads '" + version + "', but what reaches it is "
                        + describe(reached, variable));
    }

    private List<String> versionsOf(Set<String> current, String variable) {
        List<String> found = new ArrayList<String>();
        for (String candidate : current) {
            if (variable.equals(form.variableOf(candidate))) {
                found.add(candidate);
            }
        }
        return found;
    }

    private static String describe(List<String> reached, String variable) {
        if (reached.isEmpty()) {
            return "nothing at all";
        }
        if (reached.size() == 1) {
            return reached.get(0);
        }
        return reached + "; two versions of '" + variable
                + "' arrive with no φ to merge them";
    }

    private void define(Set<String> current, SsaStatement statement) {
        String written = Effects.writtenVariable(statement.item());
        if (written != null) {
            drop(current, form.variableOf(written));
            current.add(written);
        }
        for (String flag : Names.flagNames()) {
            String version = statement.definedFlag(flag);
            if (version != null) {
                drop(current, flag);
                current.add(version);
            } else if (Effects.flagsKilled(statement.item()).contains(flag)) {
                drop(current, flag);
            }
        }
    }

    private void drop(Set<String> current, String variable) {
        List<String> stale = new ArrayList<String>();
        for (String version : current) {
            if (variable.equals(form.variableOf(version))) {
                stale.add(version);
            }
        }
        current.removeAll(stale);
    }

    /**
     * Every φ operand is what its predecessor leaves behind.
     *
     * <p>A φ says "coming from this predecessor, the variable was this", and the
     * only way to check it is against what that predecessor actually leaves. A path
     * nothing reaches leaves nothing: the edge is never taken, so the operand has
     * to be {@code undef}.
     */
    private void checkPhiOperands(List<Set<String>> reaching) {
        for (Block block : cfg.blocks()) {
            for (Phi phi : form.phis(block)) {
                for (int i = 0; i < block.predecessors().size(); i++) {
                    Block predecessor = block.predecessors().get(i);
                    String expected = SsaForm.undefined(phi.variable());
                    if (cfg.isReachable(predecessor)) {
                        expected = single(reaching.get(predecessor.index()), phi.variable());
                    }
                    require(expected.equals(phi.operand(i)), phi.position(),
                            "the φ for '" + phi.variable() + "' takes '" + phi.operand(i)
                                    + "' from " + predecessor + ", but that path arrives with '"
                                    + expected + "'");
                }
            }
        }
    }

    /** The one version of a variable in a set, or its undefined value when there is none. */
    private String single(Set<String> versions, String variable) {
        String found = SsaForm.undefined(variable);
        for (String version : versions) {
            if (variable.equals(form.variableOf(version))) {
                found = version;
            }
        }
        return found;
    }

    private static boolean sameType(Type left, Type right) {
        return left == null ? right == null : left.equals(right);
    }

    private void require(boolean condition, SourcePos where, String message) {
        if (!condition) {
            throw new CompileError(where, message);
        }
    }
}
