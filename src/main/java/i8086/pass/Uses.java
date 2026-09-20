package i8086.pass;

import i8086.ir.Item;
import i8086.ssa.Block;
import i8086.ssa.Effects;
import i8086.ssa.Phi;
import i8086.ssa.SsaForm;
import i8086.ssa.SsaStatement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How often each version is used, and therefore whether it is used at all.
 *
 * <p>Two passes ask this and neither should compute it twice. A value with no uses
 * is what dead value elimination exists to find, and a value with no uses is also
 * what tells constant propagation that an operation's flags do not matter — the
 * rule {@code docs/ir.md} §5.1 gives for folding: the flags that come out are the
 * operation's, so an operation may only be replaced by a value when nobody can
 * still be reading them.
 *
 * <p>Counted as uses are the occurrences in an item, the operands of φ's, and
 * <b>the flags a statement reads</b> — which a branch does implicitly and which is
 * why a {@code cmp} whose flags nobody reads is dead too. Which version of the
 * flags a statement reads is worked out within its block: a definition makes the
 * version in force, and the surface's own rule guarantees the definition is in the
 * same block, ahead of the read ({@code docs/ir.md} §4.3). That is the rule the
 * verifier applies before any of this runs, and it is sound, so a read with no
 * definition ahead of it cannot reach here.
 *
 * <h3>What a block the compiler cannot see through reads</h3>
 *
 * <p>An inline assembly block declares the registers it destroys and nothing else.
 * It does not say which registers it <em>reads</em>, because the surface has no way
 * to say that yet ({@code docs/ir.md} §9, §12), and a register holding a value it
 * reads is invisible either way. So in a module containing one, <b>every version
 * counts as used</b>: a value the block never looks at is then kept for nothing,
 * which costs registers, while one it does look at being deleted would cost the
 * program ({@code docs/ir.md} §2.3, {@code docs/ssa.md} §5).
 *
 * <p>That is a retreat in one direction only. The passes that read this answer
 * remove definitions, and a definition they leave alone is not a wrong program;
 * what they still do among inline blocks is everything that rewrites without
 * removing — substituting a known value into a use takes nothing away.
 */
public final class Uses {

    private final Map<String, Integer> counts;

    private Uses(Map<String, Integer> counts) {
        this.counts = counts;
    }

    public static Uses of(SsaForm form) {
        if (hasInlineAssembly(form)) {
            Map<String, Integer> every = new LinkedHashMap<String, Integer>();
            for (String version : form.versions()) {
                every.put(version, Integer.valueOf(1));
            }
            return new Uses(every);
        }

        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Block block : form.cfg().blocks()) {
            Map<String, String> flags = new LinkedHashMap<String, String>();
            for (Phi phi : form.phis(block)) {
                for (String operand : phi.operands()) {
                    count(counts, operand, form);
                }
            }
            for (SsaStatement statement : form.statements(block)) {
                for (Effects.Occurrence occurrence : Effects.occurrences(statement.item())) {
                    if (!occurrence.written()) {
                        count(counts, occurrence.name(), form);
                    }
                }
                for (String flag : Effects.flagsRead(statement.item())) {
                    count(counts, flags.get(flag), form);
                }
                for (Map.Entry<String, String> defined : statement.definedFlags().entrySet()) {
                    flags.put(defined.getKey(), defined.getValue());
                }
                for (String killed : Effects.flagsKilled(statement.item())) {
                    flags.remove(killed);
                }
            }
        }
        return new Uses(counts);
    }

    /** Whether anything in this module is assembly the compiler cannot read. */
    private static boolean hasInlineAssembly(SsaForm form) {
        for (Block block : form.cfg().blocks()) {
            for (SsaStatement statement : form.statements(block)) {
                if (statement.item() instanceof Item.InlineAsm) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Counts one mention, if it is a value at all.
     *
     * <p>A label used as an address is not a value and has no versions; the
     * undefined value of a variable is a value whose definition never exists, so
     * counting it says nothing. Both are left out rather than counted as
     * themselves.
     */
    private static void count(Map<String, Integer> counts, String name, SsaForm form) {
        if (name == null || !form.isVersion(name)) {
            return;
        }
        Integer seen = counts.get(name);
        counts.put(name, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
    }

    /** How many times this version is read. */
    public int count(String version) {
        Integer seen = counts.get(version);
        return seen == null ? 0 : seen.intValue();
    }

    /** Whether anything reads this version. */
    public boolean isUsed(String version) {
        return count(version) > 0;
    }
}
