package i8086.ssa;

import i8086.SourcePos;
import i8086.ir.Expression;
import i8086.ir.Item;
import i8086.ir.MemoryOperand;
import i8086.ir.Names;
import i8086.ir.Operation;
import i8086.ir.Operator;
import i8086.ir.Place;
import i8086.ir.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
public final class Effects {

    private Effects() {
    }

    /**
     * A name an item mentions: a variable, a label used as an address, or a
     * version once the module has been renamed.
     *
     * <p>The name may be absent. A volatile access written as a bare displacement
     * — {@code volatile [0x1234]} — has no name to report, and the item's effect
     * does not depend on one.
     */
    public static final class Occurrence {

        private final String name;
        private final boolean written;
        private final boolean isVolatile;
        private final SourcePos position;

        Occurrence(String name, boolean written, SourcePos position) {
            this(name, written, false, position);
        }

        Occurrence(String name, boolean written, boolean isVolatile, SourcePos position) {
            this.name = name;
            this.written = written;
            this.isVolatile = isVolatile;
            this.position = position;
        }

        public String name() {
            return name;
        }

        /** Whether this occurrence is the place the item writes. */
        public boolean written() {
            return written;
        }

        /** Whether the access this name was found in is marked {@code volatile}. */
        public boolean isVolatile() {
            return isVolatile;
        }

        public SourcePos position() {
            return position;
        }
    }

    /**
     * Whether this item does something the compiler may not remove, whoever reads
     * what.
     *
     * <p>Four kinds of thing: a store, because writing memory is an effect; a block
     * of assembly, because the compiler cannot see inside it; control flow, which is
     * the shape of the program; and a volatile access, which the program needs to
     * happen even when nobody uses the value it produces
     * ({@code AGENTS.md}, invariant 3).
     *
     * <p>This is the rule a pass asks before deleting a statement, so it lives here
     * rather than inside one pass: a second answer to "does this have an effect"
     * would be a second thing to be wrong.
     */
    public static boolean hasEffect(Item item) {
        if (item instanceof Item.Branch || item instanceof Item.Jump
                || item instanceof Item.FarJump || item instanceof Item.Return
                || item instanceof Item.InlineAsm || item instanceof Item.Machine) {
            return true;
        }
        if (item instanceof Item.MovReg) {
            // Setting up a segment register or the stack pointer is state the program observes,
            // the same way a store to memory is (docs/ir.md §2.3, §8.1).
            return true;
        }
        if (item instanceof Item.Assign
                && ((Item.Assign) item).place() instanceof Place.Memory) {
            return true;
        }
        for (Occurrence occurrence : occurrences(item)) {
            if (occurrence.isVolatile()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every name an item mentions, in the order the meaning puts them: what is
     * read first, then the place that is written.
     *
     * <p>The name a declaration declares is not an occurrence — {@code var x: u16}
     * reads nothing — and neither is a label that says where an item is, as
     * opposed to one used as an address inside a value.
     */
    public static List<Occurrence> occurrences(Item item) {
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
        } else if (item instanceof Item.MovReg) {
            // What is written is the machine's state and not a name of the module's, so only the
            // value being put there is an occurrence — and it is read, like any other operand.
            Item.MovReg movreg = (Item.MovReg) item;
            if (movreg.value() != null) {
                valueNames(movreg.value(), found);
            }
        } else if (item instanceof Item.MovRegRead) {
            // The other direction of the same statement: the register is the machine's, so the
            // only name here is the value it is read into (docs/ir.md §8.1).
            found.add(new Occurrence(((Item.MovRegRead) item).variable(), true, item.position()));
        }
        for (Item.Argument argument : argumentsOf(item)) {
            // A clause reads what it puts into the registers, which is what keeps the value
            // alive across the statement that is going to use it (docs/ir.md §11).
            valueNames(argument.value(), found);
        }
        return found;
    }

    /** The {@code with} clause of an item, or nothing when it has none. */
    public static List<Item.Argument> argumentsOf(Item item) {
        if (item instanceof Item.Machine) {
            return ((Item.Machine) item).arguments();
        }
        if (item instanceof Item.InlineAsm) {
            return ((Item.InlineAsm) item).arguments();
        }
        if (item instanceof Item.FarJump) {
            return ((Item.FarJump) item).arguments();
        }
        return Collections.emptyList();
    }

    /**
     * The variable a definition defines, or null when the item defines no value: a store writes
     * memory, and the write direction of {@code movreg} writes machine state.
     *
     * <p>A store is not a definition of a value: memory is not renamed, so nothing in SSA has
     * to be named for it ({@code docs/ir.md} §3.1). A {@code movreg} that reads a register is a
     * definition of the variable it reads it into, which is what SSA renames and what keeps the
     * value alive until it is read ({@code docs/ir.md} §8.1).
     */
    public static String writtenVariable(Item item) {
        if (item instanceof Item.Assign) {
            Place place = ((Item.Assign) item).place();
            if (place instanceof Place.Name) {
                return ((Place.Name) place).name();
            }
        }
        if (item instanceof Item.MovRegRead) {
            return ((Item.MovRegRead) item).variable();
        }
        return null;
    }

    /**
     * The flags this item leaves a value of its own in: what it computed, rather than what was
     * there before it ({@code docs/ir.md} §4.2).
     *
     * <p>The three states are asked together and they are not the same question. An arithmetic
     * statement computes flags, so what is in force afterwards is what it computed. A conversion
     * or an {@code expr} gives them up, so nothing is. Everything else — a move, a comparison of two
     * loads, a statement about machine state — leaves them exactly as they were, which is a third
     * answer and not the second one: the value in force is still the older definition, and a
     * compiler that thought otherwise would be free to delete it, leaving the next branch to read
     * whatever the machine happened to have.
     *
     * <p>A machine statement is where the surface cannot say which of the last two it means, and the
     * target is asked instead ({@link i8086.target.Target#machineFlags}): a clobber list that does
     * not name a flag says it is not destroyed, and whether the statement <em>made</em> it is a fact
     * about the instruction. An inline block is not asked, because there is nobody to ask: it
     * destroys the flags, and a program that needs what it left behind says so with a statement the
     * compiler understands ({@link #flagsKilled}).
     */
    public static Set<String> flagsDefined(Item item) {
        Set<String> defined = new LinkedHashSet<String>();
        for (String flag : Names.flagNames()) {
            if (defines(item, flag)) {
                defined.add(flag);
            }
        }
        return defined;
    }

    /** Whether this item makes this flag its own. */
    private static boolean defines(Item item, String flag) {
        Operator operator = operatorOf(item);
        if (operator != null) {
            // An operation leaves the conditions and the carry, except that an increment and a
            // decrement leave the carry exactly as they found it (docs/ir.md §4.2).
            return flag.equals(Names.FLAGS)
                    || (flag.equals(Names.CARRY) && !operator.keepsCarry());
        }
        if (item instanceof Item.Machine) {
            Item.Machine machine = (Item.Machine) item;
            return machine.flags().defined().contains(flag)
                    && !clobbersFlag(machine.clobbers(), flag);
        }
        return false;
    }

    /**
     * The operation this item computes with, or null when it is not one.
     *
     * <p>A comparison and an {@code eval} are one operation; an assignment is one only when its
     * value is an {@code eval}, because an {@code expr} gives the flags up and a conversion has its
     * own business with them ({@code docs/ir.md} §4.2, §5.2).
     */
    private static Operator operatorOf(Item item) {
        if (item instanceof Item.Compare) {
            return Operator.SUBTRACT;
        }
        if (item instanceof Item.Eval) {
            return ((Item.Eval) item).operation().operator();
        }
        if (item instanceof Item.Assign) {
            Value value = ((Item.Assign) item).value();
            if (value instanceof Value.Eval) {
                return ((Value.Eval) value).operation().operator();
            }
        }
        return null;
    }

    /**
     * The flags a value computed with {@code expr} gives up.
     *
     * <p>The surface says an {@code expr} reads no flags and leaves them undefined
     * ({@code docs/ir.md} §5.2), and this is which ones that is: the flags an operation computes and
     * the carry with them. The direction flag is not here — a computation has no business with the
     * way a copy walks — so a statement that leaves nothing but the direction flag standing is a
     * statement {@code expr} can hold.
     */
    public static Set<String> flagsAnExpressionGivesUp() {
        return new LinkedHashSet<String>(Arrays.asList(Names.FLAGS, Names.CARRY));
    }

    /**
     * The flags this item destroys, leaving nothing in their place ({@code docs/ir.md} §4.2).
     *
     * <p>A block destroys them whatever its list says. The list is about registers, and about the
     * code the compiler cannot see the only honest answer for the flags is the worst one: most of
     * what this machine does writes them, and an author who has to remember to write down every
     * flag-setting instruction will sometimes not. GCC's x86 back end takes the same line — a
     * {@code cc} clobber is implicit in every {@code asm} statement there, for the reason that "most
     * x86 instructions write FLAGS" — and the cost is the same one: a program that needs the flags
     * a block left behind has to say so in a form the compiler understands, which a machine
     * statement is ({@code docs/ir.md} §11).
     */
    public static Set<String> flagsKilled(Item item) {
        Set<String> killed = new LinkedHashSet<String>();
        for (String flag : Names.flagNames()) {
            if (kills(item, flag)) {
                killed.add(flag);
            }
        }
        return killed;
    }

    /** Whether this item destroys this flag. */
    private static boolean kills(Item item, String flag) {
        if (item instanceof Item.Assign) {
            Value value = ((Item.Assign) item).value();
            return flagsAnExpressionGivesUp().contains(flag)
                    && (value instanceof Value.Expr || value instanceof Value.Convert);
        }
        if (item instanceof Item.InlineAsm) {
            return true;
        }
        if (item instanceof Item.Machine) {
            return clobbersFlag(((Item.Machine) item).clobbers(), flag);
        }
        return false;
    }

    /**
     * Whether a clobber list says a flag is destroyed.
     *
     * <p>One rule for the two things that carry a list — an inline block and a machine
     * statement — because they mean the same thing by it: a list that names a flag
     * takes it away, and one that does not leaves it standing.
     */
    private static boolean clobbersFlag(List<String> clobbers, String flag) {
        return clobbers.contains(flag);
    }

    /**
     * The flags this item reads.
     *
     * <p>A branch reads the arithmetic ones and so does an operation that asks for the carry. A
     * machine statement reads whatever the target says it reads, which on this machine is the
     * direction flag and the string operations ({@link i8086.ir.FlagUse}). An inline block is a
     * promise rather than an answer: it may read the flags and the surface has no way to say so yet
     * ({@code docs/ir.md} §9), so it is taken to read none — which costs nothing, because it
     * destroys them anyway ({@link #flagsKilled}) and a read of them is refused. That is the
     * verifier's model too, and it is the honest one: this compiler cannot see inside the block.
     */
    public static Set<String> flagsRead(Item item) {
        Set<String> read = new LinkedHashSet<String>();
        if (item instanceof Item.Branch) {
            // Which flag a condition reads is the target's answer, stamped on the statement when it
            // was read: 'jc' is the carry and 'jz' is not (docs/ir.md §4.4).
            read.addAll(((Item.Branch) item).flags());
        } else if (readsTheCarry(item)) {
            read.add(Names.CARRY);
        }
        if (item instanceof Item.Machine) {
            read.addAll(((Item.Machine) item).flags().read());
        }
        return read;
    }

    /**
     * Whether this item reads the carry, which is the one flag an operation can ask for.
     *
     * <p>The operators that do are the ones whose tag says so — {@code adc}, {@code sbb},
     * {@code rcl}, {@code rcr} — and an operand of a comparison may be one of them.
     */
    private static boolean readsTheCarry(Item item) {
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
     * <p>The flags count as variables here, because that is what they are
     * ({@code docs/ir.md} §4.1): a branch reads them, and an operation that reads
     * the carry reads them, so both make them live.
     */
    public static List<String> readVariables(Item item, Names names) {
        List<String> read = new ArrayList<String>();
        for (Occurrence occurrence : occurrences(item)) {
            if (!occurrence.written() && names.isVariable(occurrence.name())) {
                read.add(occurrence.name());
            }
        }
        read.addAll(flagsRead(item));
        return read;
    }

    /** The variables this item defines, the flags included. */
    public static Set<String> definedBy(Item item) {
        Set<String> defined = new LinkedHashSet<String>();
        String variable = writtenVariable(item);
        if (variable != null) {
            defined.add(variable);
        }
        defined.addAll(flagsDefined(item));
        defined.addAll(flagsKilled(item));
        return defined;
    }

    /** The variables a run of items defines, in the order they are first defined. */
    public static Set<String> definedBy(List<Item> items) {
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
        } else if (expression instanceof Expression.Unary) {
            expressionNames(((Expression.Unary) expression).operand(), found);
        } else {
            Expression.Apply apply = (Expression.Apply) expression;
            expressionNames(apply.left(), found);
            expressionNames(apply.right(), found);
        }
    }

    private static void operandNames(MemoryOperand operand, List<Occurrence> found) {
        if (operand.base() != null || operand.isVolatile()) {
            // An access with a base mentions a name, which may or may not be a
            // variable. An access with none mentions nothing — but if it is
            // volatile the item still has an effect, so it is reported with no name
            // at all rather than left out.
            found.add(new Occurrence(operand.base(), false, operand.isVolatile(),
                    operand.position()));
        }
    }

    /** Whether a value, wherever it sits, reads the flags. */
    public static boolean valueReadsFlags(Value value) {
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
        if (expression instanceof Expression.Unary) {
            return expressionReadsFlags(((Expression.Unary) expression).operand());
        }
        Expression.Apply apply = (Expression.Apply) expression;
        return apply.operator().readsFlags()
                || expressionReadsFlags(apply.left())
                || expressionReadsFlags(apply.right());
    }
}
