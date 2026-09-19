package i8086.ssa;

import i8086.ir.Item;
import i8086.ir.Module;

import java.util.ArrayList;
import java.util.List;

/**
 * Leaves the SSA form: the module again, with one name per variable and no φ's.
 *
 * <p>This is the other half of what {@code docs/ir.md} §3.1 says about the
 * surface — the input is not SSA, and the output is not either. The passes work on
 * versions because that is what makes a use have one definition; the back end
 * works on variables because that is what the surface has.
 *
 * <h3>Why there are no copies</h3>
 *
 * <p>Leaving SSA in a compiler that lowers to a register machine usually means
 * inserting copies for the φ's, sequentialising them, and splitting the edges
 * where a predecessor has two ways out ({@code AGENTS.md} calls the whole thing
 * "promoting memory" in the README's pipeline, and it is where most of the
 * trouble in an SSA backend lives). None of that is needed here, and the reason is
 * worth writing down:
 *
 * <blockquote>
 * every operand of a φ is a version of the same variable the φ defines.
 * </blockquote>
 *
 * <p>So renaming every version back to the variable it belongs to turns each φ
 * into {@code x = x}. The value the φ would have produced is the value the
 * variable already holds on that path — that is what "the version reaching the end
 * of the predecessor" means — so the copy is an identity, and it is not just
 * skippable but unnecessary. A predecessor with two edges needs no splitting for
 * the same reason: there is nothing edge-specific left to place.
 *
 * <p>What this costs is precision rather than correctness: the variable is one
 * mutable name again, so the allocator sees the whole of its life as one interval.
 * That is exactly the shape the input had, and the shape the back end was written
 * for.
 */
public final class OutOfSsa {

    private OutOfSsa() {
    }

    /** The module of this form: every variable back to one name, and no φ's at all. */
    public static Module module(SsaForm form) {
        final SsaForm table = form;
        Renamer.Versions variables = new Renamer.Versions() {
            @Override
            public String of(String name) {
                // A version becomes the variable it is a version of, and a
                // variable's undefined value becomes the variable itself: in the
                // mutable form that is what reading before writing is.
                return table.isValue(name) ? table.variableOf(name) : null;
            }
        };

        List<Item> items = new ArrayList<Item>();
        for (Block block : form.cfg().blocks()) {
            for (SsaStatement statement : form.statements(block)) {
                items.add(Renamer.rename(statement.item(), variables));
            }
        }
        Module module = form.module();
        return new Module(module.target(), module.origin(), module.entry(),
                module.entryPosition(), items);
    }
}
