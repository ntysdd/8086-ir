package i8086.ssa;

import i8086.SourcePos;
import i8086.ir.Expression;
import i8086.ir.Item;
import i8086.ir.MemoryOperand;
import i8086.ir.Names;
import i8086.ir.Operation;
import i8086.ir.Place;
import i8086.ir.Value;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What one item defines and reads, in the vocabulary SSA construction works in.
 *
 * <p>This is the translation the middle end turns on. The surface says
 * {@code cmp x, y} and {@code x = eval(x + 1)}; SSA needs to know that the first
 * defines the flags and reads two variables, and that the second reads a variable
 * and the flags, then writes one variable and the flags. Nothing here knows what
 * an instruction is: an item defines a value, defines the flags, reads some, or
 * does none of it, and that is the whole of what a pass can do about one.
 *
 * <p><b>A value is computed and then written</b> ({@code docs/ir.md} §5.1), so
 * what an item reads is read before what it writes, whatever order the two are
 * written in. {@code x = eval(x + 1)} reads the old {@code x} and defines a new
 * one, and this class reports it in that order, because that is the order the
 * meaning is in and not the order the text is in.
 *
 * <p>The flag rules are the ones the verifier already enforces
 * ({@code docs/ir.md} §4.2, §4.3): {@code cmp}, {@code test} and {@code eval}
 * define the flags, a value computed with {@code expr} or a conversion gives them
 * up, and a move leaves them alone. That model is coarser than the machine — per
 * whole flag set rather than per flag — and it is deliberately the same model in
 * both places, because a second answer to "what does this do to the flags" is a
 * second thing to be wrong ({@code docs/ir.md} §4.2, {@code [open]}).
 */
final class Effects {

    private Effects() {
    }

    /**
     * A name an item mentions: a variable, a label used as an address, or a
     * version once the module has been renamed.
     */
    static final class Occurrence {

        private final String name;
        private final boolean written;
        private final SourcePos position;

        Occurrence(String name, boolean written, SourcePos position) {
            this.name = name;
            this.written = written;
            this.position = position;
        }

        String name() {
            return name;
        }

        /** Whether this occurrence is the place the item writes. */
        boolean written() {
            return written;
        }

        SourcePos position() {
            return position;
        }
    }

    /**
     * Every name an item mentions, in the order the meaning puts them: what is
     * read first, then the place that is written.
     *
     * <p>The name a declaration declares is not an occurrence — {@code var x: u16}
     * reads nothing — and neither is a label that says where an item is, as
     * opposed to one used as an address inside a value.
     */
    static List<Occurrence> occurrences(Item item) {
        List<Occurrence> found = new ArrayList<Occurrence>();
        if (item instanceof Item.Assign) {
            Item.Assign assign = (Item.Assign) item;
            if (assign.place() instanceof Place.Memory) {
                operandNames(((Place.Memory) assign.place()).operand(), found);
            }
            valueNames(assign.value(), found);
            if (assign.place() instanceof Place.Name) {
                Place.Name place = (Place.Name) assign.place();
                found.add(new Occurrence(place.name(), true, place.position()));
            }
        } else if (item instanceof Item.Compare) {
            Item.Compare compare = (Item.Compare) item;
            valueNames(compare.left(), found);
            valueNames(compare.right(), found);
        } else if (item instanceof Item.Eval) {
            operationNames(((Item.Eval) item).operation(), found);
        }
        return found;
    }

    /**
     * The variable an assignment writes, or null when it writes memory or
     * nothing at all.
     *
     * <p>A store is not a definition of a value: memory is not renamed, so
     * nothing in SSA has to be named for it ({@code docs/ir.md} §3.1).
     */
    static String writtenVariable(Item item) {
        if (item instanceof Item.Assign) {
            Place place = ((Item.Assign) item).place();
            if (place instanceof Place.Name) {
                return ((Place.Name) place).name();
            }
        }
        return null;
    }

    /** Whether this item leaves the flags defined. */
    static boolean writesFlags(Item item) {
        if (item instanceof Item.Compare || item instanceof Item.Eval) {
            return true;
        }
        if (item instanceof Item.Assign) {
            return ((Item.Assign) item).value() instanceof Value.Eval;
        }
        if (item instanceof Item.InlineAsm) {
            return !sparesFlags((Item.InlineAsm) item);
        }
        return false;
    }

    /** Whether this item destroys the flags, leaving them undefined. */
    static boolean killsFlags(Item item) {
        if (item instanceof Item.Assign) {
            Value value = ((Item.Assign) item).value();
            return value instanceof Value.Expr || value instanceof Value.Convert;
        }
        if (item instanceof Item.InlineAsm) {
            return sparesFlags((Item.InlineAsm) item);
        }
        return false;
    }

    private static boolean sparesFlags(Item.InlineAsm block) {
        return block.clobbers().contains(Names.FLAGS);
    }

    /**
     * Whether this item reads the flags.
     *
     * <p>An inline block is a promise rather than an answer: it may read the flags
     * and the surface has no way to say so yet ({@code docs/ir.md} §9), so it is
     * taken to read none and to leave them defined unless it says it clobbers
     * them. That is the verifier's model too, and it is the honest one: this
     * compiler cannot see inside the block.
     */
    static boolean readsFlags(Item item) {
        if (item instanceof Item.Branch) {
            return true;
        }
        if (item instanceof Item.Eval) {
            return ((Item.Eval) item).operation().readsFlags();
        }
        if (item instanceof Item.Assign) {
            return valueReadsFlags(((Item.Assign) item).value());
        }
        if (item instanceof Item.Compare) {
            Item.Compare compare = (Item.Compare) item;
            return valueReadsFlags(compare.left()) || valueReadsFlags(compare.right());
        }
        return false;
    }

    /**
     * The variables this item reads, in the order it mentions them.
     *
     * <p>The flags count as a variable here, because that is what they are
     * ({@code docs/ir.md} §4.1): a branch reads them, and an operation that reads
     * the carry reads them, so both make them live.
     */
    static List<String> readVariables(Item item, Names names) {
        List<String> read = new ArrayList<String>();
        for (Occurrence occurrence : occurrences(item)) {
            if (!occurrence.written() && names.isVariable(occurrence.name())) {
                read.add(occurrence.name());
            }
        }
        if (readsFlags(item)) {
            read.add(Names.FLAGS);
        }
        return read;
    }

    /** The variables this item defines, flags included. */
    static Set<String> definedBy(Item item) {
        Set<String> defined = new LinkedHashSet<String>();
        String variable = writtenVariable(item);
        if (variable != null) {
            defined.add(variable);
        }
        if (writesFlags(item) || killsFlags(item)) {
            defined.add(Names.FLAGS);
        }
        return defined;
    }

    /** The variables a run of items defines, in the order they are first defined. */
    static Set<String> definedBy(List<Item> items) {
        Set<String> defined = new LinkedHashSet<String>();
        for (Item item : items) {
            defined.addAll(definedBy(item));
        }
        return defined;
    }

    private static void operationNames(Operation operation, List<Occurrence> found) {
        for (Value operand : operation.operands()) {
            valueNames(operand, found);
        }
    }

    /**
     * The names a value mentions.
     *
     * <p>A name that is not a variable is a label, and a label is an address rather
     * than something that gets a version, so it is reported but never renamed. A
     * memory operand is read through its base, which is a variable when the
     * address is held in one and a label when it is fixed.
     */
    private static void valueNames(Value value, List<Occurrence> found) {
        if (value instanceof Value.Name) {
            Value.Name name = (Value.Name) value;
            found.add(new Occurrence(name.name(), false, name.position()));
        } else if (value instanceof Value.Memory) {
            operandNames(((Value.Memory) value).operand(), found);
        } else if (value instanceof Value.Eval) {
            operationNames(((Value.Eval) value).operation(), found);
        } else if (value instanceof Value.Expr) {
            expressionNames(((Value.Expr) value).expression(), found);
        } else if (value instanceof Value.Convert) {
            valueNames(((Value.Convert) value).operand(), found);
        }
    }

    private static void expressionNames(Expression expression, List<Occurrence> found) {
        if (expression instanceof Expression.Leaf) {
            valueNames(((Expression.Leaf) expression).value(), found);
        } else if (expression instanceof Expression.Complement) {
            expressionNames(((Expression.Complement) expression).operand(), found);
        } else {
            Expression.Apply apply = (Expression.Apply) expression;
            expressionNames(apply.left(), found);
            expressionNames(apply.right(), found);
        }
    }

    private static void operandNames(MemoryOperand operand, List<Occurrence> found) {
        if (operand.base() != null) {
            found.add(new Occurrence(operand.base(), false, operand.position()));
        }
    }

    /** Whether a value, wherever it sits, reads the flags. */
    static boolean valueReadsFlags(Value value) {
        if (value instanceof Value.Eval) {
            return ((Value.Eval) value).operation().readsFlags();
        }
        if (value instanceof Value.Expr) {
            return expressionReadsFlags(((Value.Expr) value).expression());
        }
        if (value instanceof Value.Convert) {
            return valueReadsFlags(((Value.Convert) value).operand());
        }
        return false;
    }

    private static boolean expressionReadsFlags(Expression expression) {
        if (expression instanceof Expression.Leaf) {
            return valueReadsFlags(((Expression.Leaf) expression).value());
        }
        if (expression instanceof Expression.Complement) {
            return expressionReadsFlags(((Expression.Complement) expression).operand());
        }
        Expression.Apply apply = (Expression.Apply) expression;
        return apply.operator().readsFlags()
                || expressionReadsFlags(apply.left())
                || expressionReadsFlags(apply.right());
    }
}
