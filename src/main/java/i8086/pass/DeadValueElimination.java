package i8086.pass;

import i8086.ir.Item;
import i8086.ir.Place;
import i8086.ssa.Block;
import i8086.ssa.Effects;
import i8086.ssa.Phi;
import i8086.ssa.SsaForm;
import i8086.ssa.SsaStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes what nothing can observe.
 *
 * <p>A version nothing reads is a value nobody wants, and in SSA "nothing reads
 * it" is a question about one name. Removing its statement is then the whole of
 * the transformation — with the one complication that a statement can do more than
 * define a value, and those other things are why it has to be looked at rather
 * than counted:
 *
 * <ul>
 *   <li>a store is an effect, and stays however dead the value in it is;
 *   <li>an inline assembly block is an interface the compiler cannot see through,
 *       and stays for the same reason;
 *   <li>a branch, a jump and a {@code ret} are the shape of the program;
 *   <li>and a statement that defines the flags stays when anyone reads them, which
 *       is what makes {@code cmp} without a following conditional branch go away —
 *       the flags are effects, but only on the condition that somebody looks
 *       ({@code docs/ir.md} §2.3).
 * </ul>
 *
 * <p>Repeated until nothing changes, because one removal makes the next one
 * possible: {@code y = eval(x + 1)} dying is what leaves {@code x} with no readers,
 * which is what lets {@code x = 0} go.
 *
 * <p>Nothing else is removed here. A φ whose name is unused goes, and its operands'
 * uses go with it, which is why a φ that turned out to be redundant does not
 * survive as a copy: the version it defined is simply not read any more.
 */
public final class DeadValueElimination implements Pass {

    @Override
    public String name() {
        return "dead value elimination";
    }

    @Override
    public SsaForm run(SsaForm form) {
        SsaForm current = form;
        while (true) {
            Uses uses = Uses.of(current);
            List<List<Phi>> phis = new ArrayList<List<Phi>>();
            List<List<SsaStatement>> statements = new ArrayList<List<SsaStatement>>();
            boolean changed = false;
            for (Block block : current.cfg().blocks()) {
                List<Phi> keptPhis = new ArrayList<Phi>();
                for (Phi phi : current.phis(block)) {
                    if (uses.isUsed(phi.name())) {
                        keptPhis.add(phi);
                    } else {
                        changed = true;
                    }
                }
                List<SsaStatement> kept = new ArrayList<SsaStatement>();
                for (SsaStatement statement : current.statements(block)) {
                    if (needed(statement, uses)) {
                        kept.add(statement);
                    } else {
                        changed = true;
                    }
                }
                phis.add(keptPhis);
                statements.add(kept);
            }
            if (!changed) {
                return current;
            }
            current = current.rewriting(phis, statements);
        }
    }

    /**
     * Whether a statement does anything anybody can observe.
     *
     * <p>Two different questions, asked together because the answer is the same. An
     * item that is not code — a declaration, a label, a data definition — is not
     * something to remove; an item that has an effect is not something that may be
     * removed. Neither is a value definition; those are removable exactly when
     * nothing reads them.
     */
    private static boolean needed(SsaStatement statement, Uses uses) {
        Item item = statement.item();
        if (item instanceof Item.Label || item instanceof Item.Var
                || item instanceof Item.Data || item instanceof Item.Pad) {
            return true;
        }
        if (Effects.hasEffect(item)) {
            return true;
        }
        if (statement.definedFlags() != null && uses.isUsed(statement.definedFlags())) {
            return true;
        }
        String written = Effects.writtenVariable(item);
        return written != null && uses.isUsed(written);
    }
}
