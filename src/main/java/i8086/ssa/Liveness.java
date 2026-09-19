package i8086.ssa;

import i8086.ir.Item;
import i8086.ir.Names;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which variables are live where.
 *
 * <p>A variable is live at a point when its value can still be read before it is
 * written again. That is the question SSA construction asks to decide whether a
 * merge point deserves a φ: a φ for a value nobody will read is a φ that costs a
 * register and buys nothing ({@code docs/ssa.md}).
 *
 * <p>The computation is the ordinary backward one. What a block reads before
 * writing is its own business; what is live going in is that, plus whatever is
 * live coming out and not written here. A block that writes a variable and reads
 * it after does not make it live going in, which is the whole point of the
 * "before writing" in the first sentence.
 *
 * <p>The answer is allowed to be too large and never too small: a φ that is not
 * strictly needed is harmless, while one that is missing is wrong code. Which is
 * why an unreachable block's reads count too, even though nothing gets there from
 * the entry.
 */
public final class Liveness {

    private final List<Set<String>> liveIn;

    private Liveness(Cfg cfg, Names names) {
        int count = cfg.blocks().size();
        List<Set<String>> uses = new ArrayList<Set<String>>();
        List<Set<String>> defs = new ArrayList<Set<String>>();
        for (Block block : cfg.blocks()) {
            Set<String> used = new LinkedHashSet<String>();
            Set<String> defined = new LinkedHashSet<String>();
            for (Item item : block.items()) {
                for (String variable : Effects.readVariables(item, names)) {
                    if (!defined.contains(variable)) {
                        used.add(variable);
                    }
                }
                defined.addAll(Effects.definedBy(item));
            }
            uses.add(used);
            defs.add(defined);
        }

        List<Set<String>> live = new ArrayList<Set<String>>();
        for (int i = 0; i < count; i++) {
            live.add(new LinkedHashSet<String>());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = count - 1; i >= 0; i--) {
                Set<String> in = new LinkedHashSet<String>(uses.get(i));
                for (Block successor : cfg.blocks().get(i).successors()) {
                    for (String variable : live.get(successor.index())) {
                        if (!defs.get(i).contains(variable)) {
                            in.add(variable);
                        }
                    }
                }
                if (!in.equals(live.get(i))) {
                    live.set(i, in);
                    changed = true;
                }
            }
        }
        this.liveIn = Collections.unmodifiableList(live);
    }

    public static Liveness of(Cfg cfg, Names names) {
        return new Liveness(cfg, names);
    }

    /** The variables whose value can still be read when this block is entered. */
    public Set<String> liveIn(Block block) {
        return Collections.unmodifiableSet(liveIn.get(block.index()));
    }

    public boolean isLiveIn(Block block, String variable) {
        return liveIn.get(block.index()).contains(variable);
    }
}
