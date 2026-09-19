package i8086.ssa;

import i8086.SourcePos;
import i8086.ir.Item;
import i8086.ir.Module;
import i8086.ir.Names;
import i8086.ir.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Puts a module into SSA form.
 *
 * <p>Two steps, in this order, because the first is a question about the graph
 * and the second is a walk of it:
 *
 * <ol>
 *   <li><b>Place the φ's.</b> A variable needs one where its definitions meet:
 *       at the blocks in the dominance frontier of the blocks that define it —
 *       where it is defined on one path in and not on another — and only while
 *       it is live there, because a φ nobody reads costs a register and buys
 *       nothing.
 *   <li><b>Rename.</b> Walk the dominator tree from the entry, giving every
 *       definition a new version and every use the version in force at that
 *       point. Walking the tree rather than the graph is what makes the versions
 *       in force exactly the ones that dominate the point being walked, which is
 *       the SSA property itself.
 * </ol>
 *
 * <p>Definitions that arrive by several paths are merged by the φ's placed in the
 * first step, and each φ takes its operand from the predecessor that arrives:
 * that is why a φ's operands are in the order of the block's predecessors, an
 * order the graph fixes rather than this class.
 *
 * <p>A block nothing reaches is renamed on its own, with nothing in force, so
 * everything it reads is {@code undef} and the versions it defines stay inside it.
 * They cannot escape, because the only way a version leaves a block is as a φ
 * operand and no φ is placed in a block nothing reaches.
 *
 * <p>The module is not modified: renaming an item builds a new one, which is why
 * a module still prints as it was written after its SSA form has been built
 * ({@code AGENTS.md}, invariant 5).
 */
public final class SsaBuilder {

    private final Module module;
    private final Names names;
    private final Cfg cfg;
    private final Dominators dominators;
    private final Liveness liveness;

    /** Every variable in declaration order, with the flags last. */
    private final List<String> tracked = new ArrayList<String>();
    private final List<List<String>> phiVariables = new ArrayList<List<String>>();
    private final List<List<Phi>> phis = new ArrayList<List<Phi>>();
    private final List<List<SsaStatement>> statements = new ArrayList<List<SsaStatement>>();
    private final Map<String, Type> types = new LinkedHashMap<String, Type>();
    private final Map<String, String> variables = new LinkedHashMap<String, String>();
    private final Set<String> undefined = new LinkedHashSet<String>();
    private final Map<String, SourcePos> positions = new LinkedHashMap<String, SourcePos>();
    private int created;

    private SsaBuilder(Module module) {
        this.module = module;
        this.names = Names.of(module);
        this.cfg = Cfg.of(module);
        this.dominators = Dominators.of(cfg);
        this.liveness = Liveness.of(cfg, names);

        tracked.addAll(names.variables());
        tracked.add(Names.FLAGS);
        for (int i = 0; i < cfg.blocks().size(); i++) {
            phiVariables.add(new ArrayList<String>());
            phis.add(new ArrayList<Phi>());
            statements.add(new ArrayList<SsaStatement>());
        }
    }

    /**
     * Builds the SSA form of a module.
     *
     * <p>The module has to be one the verifier has accepted: a name that resolves
     * to nothing, or a width that does not add up, is refused there rather than
     * guessed at here.
     */
    public static SsaForm build(Module module) {
        return new SsaBuilder(module).run();
    }

    private SsaForm run() {
        placePhis();
        VersionStacks stacks = new VersionStacks();
        for (String variable : tracked) {
            stacks.track(variable);
            // Nothing is defined before the first item, so every variable starts
            // out undefined. Reading one is not an error here: the surface has no
            // definite-assignment rule yet (docs/ir.md §3.1, §12), so the honest
            // answer is "whatever was there" rather than a guess of zero.
            stacks.push(variable, SsaForm.undefined(variable));
            registerUndefined(variable);
        }
        rename(cfg.entry(), stacks);
        renameUnreachable();
        return new SsaForm(module, cfg, phis, statements, types, variables, undefined,
                positions);
    }

    /**
     * Records the name a variable's undefined value has.
     *
     * <p>It is in the same tables as a version so that anything asking "what
     * variable is this, and how wide" gets the same answer either way, and apart
     * from them so that nothing mistakes it for a definition.
     */
    private void registerUndefined(String variable) {
        String name = SsaForm.undefined(variable);
        types.put(name, names.typeOf(variable));
        variables.put(name, variable);
        undefined.add(name);
    }

    // --- placing the φ's ---------------------------------------------------

    /**
     * Puts a φ where a variable's definitions meet, and only where it is live.
     *
     * <p>The walk is the classic one: from each block that defines the variable,
     * follow the dominance frontier, stopping where the variable is dead — a
     * value nobody reads does not need to be merged — and starting a new walk from
     * each block that gains a φ, because that φ is a definition too.
     */
    private void placePhis() {
        for (String variable : tracked) {
            List<Block> worklist = new ArrayList<Block>();
            Set<Block> queued = new LinkedHashSet<Block>();
            for (Block block : cfg.blocks()) {
                if (Effects.definedBy(block.items()).contains(variable)) {
                    worklist.add(block);
                    queued.add(block);
                }
            }
            while (!worklist.isEmpty()) {
                Block block = worklist.remove(0);
                for (Block join : dominators.frontierOf(block)) {
                    if (!liveness.isLiveIn(join, variable)
                            || phiVariables.get(join.index()).contains(variable)) {
                        continue;
                    }
                    phiVariables.get(join.index()).add(variable);
                    phis.get(join.index()).add(new Phi(join.items().get(0).position(), variable,
                            names.typeOf(variable), join.predecessors().size()));
                    if (queued.add(join)) {
                        worklist.add(join);
                    }
                }
            }
        }
    }

    // --- renaming ----------------------------------------------------------

    /**
     * Renames a block, the blocks it dominates, and everything they dominate.
     *
     * <p>The versions this block pushes are in force for the whole subtree and
     * stop being in force when the walk comes back out, which is the dominator
     * tree doing the work that a graph walk would have to do with dataflow.
     */
    private void rename(Block block, VersionStacks stacks) {
        List<String> pushed = new ArrayList<String>();
        for (Phi phi : phis.get(block.index())) {
            String version = fresh(phi.variable(), phi.position());
            phi.setName(version);
            stacks.push(phi.variable(), version);
            pushed.add(phi.variable());
        }
        renameItems(block, stacks, pushed);
        fillSuccessors(block, stacks);
        for (Block child : dominators.childrenOf(block)) {
            rename(child, stacks);
        }
        for (int i = pushed.size() - 1; i >= 0; i--) {
            stacks.pop(pushed.get(i));
        }
    }

    private void renameItems(Block block, VersionStacks stacks, List<String> pushed) {
        List<SsaStatement> out = new ArrayList<SsaStatement>();
        for (Item item : block.items()) {
            Item renamed = Renamer.rename(item, stacks);
            String defined = Effects.writtenVariable(item);
            if (defined != null) {
                String version = fresh(defined, item.position());
                renamed = Renamer.define((Item.Assign) renamed, version);
                stacks.push(defined, version);
                pushed.add(defined);
            }
            String flags = null;
            if (Effects.writesFlags(item)) {
                flags = fresh(Names.FLAGS, item.position());
                stacks.push(Names.FLAGS, flags);
                pushed.add(Names.FLAGS);
            } else if (Effects.killsFlags(item)) {
                // Giving the flags up is a state, not a value: what is in force
                // afterwards is that nothing is.
                stacks.push(Names.FLAGS, SsaForm.undefined(Names.FLAGS));
                pushed.add(Names.FLAGS);
            }
            out.add(new SsaStatement(renamed, flags));
        }
        statements.set(block.index(), out);
    }

    /** Gives every successor's φ the version that arrives along this edge. */
    private void fillSuccessors(Block block, VersionStacks stacks) {
        for (Block successor : block.successors()) {
            int slot = successor.predecessors().indexOf(block);
            for (Phi phi : phis.get(successor.index())) {
                phi.setOperand(slot, stacks.current(phi.variable()));
            }
        }
    }

    /**
     * Renames the blocks nothing reaches.
     *
     * <p>Each one gets its own empty set of stacks, so it reads {@code undef} and
     * its versions do not leak. Its successors' φ operands are left unfilled,
     * which reads as {@code undef}: a value defined in a block nothing reaches
     * cannot arrive anywhere, because the edge that would carry it is never taken.
     */
    private void renameUnreachable() {
        for (Block block : cfg.blocks()) {
            if (cfg.isReachable(block)) {
                continue;
            }
            VersionStacks stacks = new VersionStacks();
            for (String variable : tracked) {
                stacks.track(variable);
            }
            renameItems(block, stacks, new ArrayList<String>());        }
    }

    /** A new version of a variable, numbered in the order versions are created. */
    private String fresh(String variable, SourcePos where) {
        created++;
        String version = variable + "#" + created;
        types.put(version, names.typeOf(variable));
        variables.put(version, variable);
        positions.put(version, where);
        return version;
    }
}
