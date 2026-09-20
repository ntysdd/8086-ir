package i8086.pass;

import i8086.ir.Item;
import i8086.ir.Place;
import i8086.ir.Value;
import i8086.ssa.Block;
import i8086.ssa.Phi;
import i8086.ssa.SsaForm;
import i8086.ssa.SsaStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * A load whose only reader can read memory itself.
 *
 * <p>{@code flag = byte [bx]} and then {@code cmp flag, 0x80} is two instructions and four bytes,
 * and the machine has one instruction that does the whole of it: {@code cmp byte [bx], 0x80} is
 * three ({@code docs/ir.md} §5.4). The load exists to give the comparison a name to read, so when
 * that name has no other reader there is no value to speak of — the comparison carries the access,
 * and the load goes.
 *
 * <p>That is all this pass does, and it is deliberately the smallest piece of what
 * {@code README.md} calls load elimination. What makes a load reusable in general is knowing when
 * two accesses are the same memory, which is the aliasing question and is {@code [open]}
 * ({@code docs/ssa.md} §9). What is asked here needs none of it: the reader is the <b>next</b>
 * statement in the same block, so nothing at all can have run between the access and the use of
 * it, and the value is used exactly once, so nothing else was reading it either.
 *
 * <p>Three things say no, and the first is about the access rather than about the value:
 *
 * <ul>
 *   <li>a <b>volatile</b> access is not folded. It would still happen exactly once, in the same
 *       place in the stream, but "the access happens where it was written" is an invariant worth
 *       keeping visible rather than argued about ({@code AGENTS.md} invariant 3);
 *   <li>a comparison with <b>zero</b> is not folded into. Zero is the one operand a comparison may
 *       not have to name at all — this target tests a register against itself instead — and a pass
 *       that does not know what the machine charges for an access must decline rather than guess
 *       (measuring is the assembler's business, {@code README.md} step 9);
 *   <li>a value with <b>another reader</b> is not folded, because the load would have to stay for
 *       that reader and the comparison would gain nothing.
 * </ul>
 *
 * <p>A comparison with the loaded value on <b>both sides</b> — {@code cmp v, v} — is left alone
 * too, because one access on both sides is a shape this machine cannot encode.
 *
 * <p>What the pass gives up is a name: the value it removes is a value a later pass could have
 * folded a constant into, and there is no constant where a memory operand now stands. That is the
 * trade the surface already made when the program wrote the load — measuring the two is what the
 * byte counts in {@code README.md} are for.
 */
public final class LoadFolding implements Pass {

    @Override
    public String name() {
        return "load folding";
    }

    @Override
    public SsaForm run(SsaForm form) {
        Uses uses = Uses.of(form);
        List<List<Phi>> phis = new ArrayList<List<Phi>>();
        List<List<SsaStatement>> statements = new ArrayList<List<SsaStatement>>();
        boolean changed = false;
        for (Block block : form.cfg().blocks()) {
            phis.add(form.phis(block));
            List<SsaStatement> rewritten = new ArrayList<SsaStatement>();
            List<SsaStatement> block1 = form.statements(block);
            int at = 0;
            while (at < block1.size()) {
                SsaStatement load = block1.get(at);
                String loaded = loadedValue(load);
                if (loaded != null && uses.count(loaded) == 1 && at + 1 < block1.size()) {
                    SsaStatement folded = foldedInto(block1.get(at + 1), loaded, load);
                    if (folded != null) {
                        rewritten.add(folded);
                        changed = true;
                        at += 2;
                        continue;
                    }
                }
                rewritten.add(load);
                at++;
            }
            statements.add(rewritten);
        }
        return changed ? form.rewriting(phis, statements) : form;
    }

    /**
     * The value this statement loads, or null when it loads nothing.
     *
     * <p>One item, and one shape: an assignment to a name whose value is an access. What is read
     * out of memory is the only thing here that could have been read where it is used instead — a
     * value computed with an operation has no such place.
     */
    private static String loadedValue(SsaStatement statement) {
        Item item = statement.item();
        if (!(item instanceof Item.Assign)) {
            return null;
        }
        Item.Assign assign = (Item.Assign) item;
        if (!(assign.place() instanceof Place.Name) || !(assign.value() instanceof Value.Memory)) {
            return null;
        }
        if (((Value.Memory) assign.value()).operand().isVolatile()) {
            return null;
        }
        return ((Place.Name) assign.place()).name();
    }

    /**
     * This statement with the loaded value replaced by the access it came from, or null when the
     * statement is not a comparison that reads it.
     */
    private static SsaStatement foldedInto(SsaStatement reader, String loaded, SsaStatement load) {
        Item item = reader.item();
        if (!(item instanceof Item.Compare)) {
            return null;
        }
        Item.Compare compare = (Item.Compare) item;
        Value left = access(compare.left(), loaded, load);
        Value right = access(compare.right(), loaded, load);
        if (left == null && right == null) {
            return null;
        }
        if (left != null && right != null) {
            return null; // one access on both sides is not an instruction this machine has
        }
        Value other = left == null ? compare.left() : compare.right();
        if (other instanceof Value.Memory) {
            return null; // and neither is an access on one side and an access on the other
        }
        if (zero(other)) {
            return null; // a comparison with zero is one the machine may have a register form for
        }
        return new SsaStatement(new Item.Compare(item.position(), compare.kind(),
                left == null ? compare.left() : left,
                right == null ? compare.right() : right), reader.definedFlags());
    }

    /** Whether a value is the literal zero. */
    private static boolean zero(Value value) {
        return value instanceof Value.Number && ((Value.Number) value).value() == 0;
    }

    /** The access this value was loaded from, when the value is the one being folded. */
    private static Value access(Value value, String loaded, SsaStatement load) {
        if (!(value instanceof Value.Name) || !((Value.Name) value).name().equals(loaded)) {
            return null;
        }
        return ((Item.Assign) load.item()).value();
    }
}
