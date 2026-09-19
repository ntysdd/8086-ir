package i8086.ir;

import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One thing a module is made of, in source order.
 *
 * <p>Order is the only structure at this level: a label is not attached to the
 * statement after it, and a block is not a thing. Whatever the pipeline builds
 * out of these — basic blocks, a control flow graph — is derived, not stored,
 * so that printing a module reproduces exactly what was parsed
 * ({@code AGENTS.md}, invariant 5).
 */
public abstract class Item {

    private final SourcePos position;

    Item(SourcePos position) {
        this.position = position;
    }

    public SourcePos position() {
        return position;
    }

    /** A label: {@code main:}. It names the place that follows it. */
    public static final class Label extends Item {

        private final String name;

        public Label(SourcePos position, String name) {
            super(position);
            this.name = name;
        }

        public String name() {
            return name;
        }
    }

    /**
     * The name this item is reached by, or null when nothing can reach it.
     *
     * <p>Three kinds of item put bytes in the image and can be named, and every place
     * that asks "does this item start a block" or "does it get a blank line before
     * it" asks this rather than listing them ({@code docs/ir.md} §10.2).
     */
    public static String labelOf(Item item) {
        if (item instanceof Label) {
            return ((Label) item).name();
        }
        if (item instanceof Data) {
            return ((Data) item).label();
        }
        if (item instanceof Pad) {
            return ((Pad) item).label();
        }
        return null;
    }

    /**
     * Bytes that exist in the image and mean nothing: {@code pad 32}, and
     * {@code pad to 510} to reach a fixed layout ({@code docs/ir.md} §10.2).
     *
     * <p>Two forms, one idea: {@link #count()} says how many bytes when the text says
     * so, and {@link #to()} says the image is that many bytes long when only the
     * layout can say so. The second is why this cannot be a byte list: nothing in the
     * front end knows how long the code before it is, and the pass that renames a
     * value or selects a smaller instruction can change that length.
     *
     * <p>The count is relative to the start of the image, not to an address: an image
     * says how it is laid out, and {@code org} says where it lands, so a boot sector
     * written with {@code pad to 510} is still a boot sector at a different load
     * address.
     */
    public static final class Pad extends Item {

        private final String label;
        private final boolean to;
        private final long amount;
        private final long fill;

        /** {@code pad count [, fill]}: this many bytes, here. */
        public static Pad ofCount(SourcePos position, String label, long count, long fill) {
            return new Pad(position, label, false, count, fill);
        }

        /** {@code pad to offset [, fill]}: bytes until the image is that long. */
        public static Pad ofOffset(SourcePos position, String label, long offset, long fill) {
            return new Pad(position, label, true, offset, fill);
        }

        private Pad(SourcePos position, String label, boolean to, long amount, long fill) {
            super(position);
            this.label = label;
            this.to = to;
            this.amount = amount;
            this.fill = fill;
        }

        /** The label this padding is named by, or null when it has none. */
        public String label() {
            return label;
        }

        /** Whether this is {@code pad to}, which only the assembler can resolve. */
        public boolean to() {
            return to;
        }

        /** How many bytes, or how long the image is, as {@link #to()} says. */
        public long amount() {
            return amount;
        }

        /** What the bytes are filled with. */
        public long fill() {
            return fill;
        }

        /**
         * Whether the assembler can work this out at all, or whether it is a length
         * only the layout decides. The IR checks what it can and leaves the rest.
         */
        public boolean isResolvable() {
            return !to;
        }
    }

    /**
     * A data definition: {@code msg: db "..."}, {@code tbl: dw 0x1234, 0x5678}.
     *
     * <p>The label is optional, because {@code db} is also allowed bare. There is no
     * separate "uninitialised" form: bytes in the image that mean nothing are
     * {@link Pad} ({@code docs/ir.md} §10.2).
     */
    public static final class Data extends Item {

        /** One element: a number, a string of bytes for {@code db}, or a label's address. */
        public static final class Atom {

            private final long number;
            private final String text;
            private final String name;

            private Atom(long number, String text, String name) {
                this.number = number;
                this.text = text;
                this.name = name;
            }

            public static Atom ofNumber(long number) {
                return new Atom(number, null, null);
            }

            public static Atom ofText(String text) {
                return new Atom(0, text, null);
            }

            /**
             * A label, whose value is its address ({@code docs/ir.md} §10.2).
             *
             * <p>Not known here: an address depends on where everything lands, so this
             * is stated by the IR and resolved by the assembler, like {@code pad to} and
             * like any operand naming a label.
             */
            public static Atom ofName(String name) {
                return new Atom(0, null, name);
            }

            public boolean isText() {
                return text != null;
            }

            /** Whether this element is a label rather than a value written out. */
            public boolean isName() {
                return name != null;
            }

            public long number() {
                return number;
            }

            public String text() {
                return text;
            }

            public String name() {
                return name;
            }
        }

        private final String label;
        private final Size elementSize;
        private final List<Atom> atoms;

        public Data(SourcePos position, String label, Size elementSize, List<Atom> atoms) {
            super(position);
            this.label = label;
            this.elementSize = elementSize;
            this.atoms = Collections.unmodifiableList(new ArrayList<Atom>(atoms));
        }

        /** The label this definition is named by, or null when it has none. */
        public String label() {
            return label;
        }

        public Size elementSize() {
            return elementSize;
        }

        public List<Atom> atoms() {
            return atoms;
        }
    }

    /** {@code ret}. */
    public static final class Return extends Item {

        public Return(SourcePos position) {
            super(position);
        }
    }

    /**
     * A declaration: {@code var x: u16}.
     *
     * <p>It introduces a mutable virtual register, not a memory location. The
     * input is not SSA; SSA construction renames these away.
     */
    public static final class Var extends Item {

        private final String name;
        private final Type type;

        public Var(SourcePos position, String name, Type type) {
            super(position);
            this.name = name;
            this.type = type;
        }

        public String name() {
            return name;
        }

        public Type type() {
            return type;
        }
    }

    /**
     * An assignment: {@code x = 5}, {@code [p] = x}, {@code p = msg}.
     *
     * <p>The two sides must have the same width. Signedness may differ, because
     * that changes no bits and emits no instruction; any change of width is
     * written as a conversion ({@code docs/ir.md} §3.5).
     */
    public static final class Assign extends Item {

        private final Place place;
        private final Value value;

        public Assign(SourcePos position, Place place, Value value) {
            super(position);
            this.place = place;
            this.value = value;
        }

        public Place place() {
            return place;
        }

        public Value value() {
            return value;
        }
    }

    /**
     * {@code cmp a, b} or {@code test a, b}: set the flags from two values and
     * produce nothing.
     *
     * <p>This is the only thing that defines flags so far. When arithmetic
     * arrives it will define them too, and a branch that reads flags nobody has
     * defined is a hard error ({@code docs/ir.md} §4.3).
     */
    public static final class Compare extends Item {

        /** Which of the two comparisons this is. */
        public enum Kind {
            CMP("cmp"),
            TEST("test");

            private final String spelling;

            Kind(String spelling) {
                this.spelling = spelling;
            }

            public String spelling() {
                return spelling;
            }
        }

        private final Kind kind;
        private final Value left;
        private final Value right;

        public Compare(SourcePos position, Kind kind, Value left, Value right) {
            super(position);
            this.kind = kind;
            this.left = left;
            this.right = right;
        }

        public Kind kind() {
            return kind;
        }

        public Value left() {
            return left;
        }

        public Value right() {
            return right;
        }
    }

    /** {@code jmp label}: go there, whatever the flags say. */    public static final class Jump extends Item {

        private final String target;

        public Jump(SourcePos position, String target) {
            super(position);
            this.target = target;
        }

        public String target() {
            return target;
        }
    }

    /**
     * {@code jc label} and the rest of the family: branch on the current flags.
     *
     * <p>The condition is stored in the canonical spelling its target answered
     * with, so {@code jb}, {@code jc} and {@code jnae} all become {@code jc}.
     * That keeps one condition one word, and it is the word the printer writes.
     */
    public static final class Branch extends Item {

        private final String condition;
        private final String target;

        public Branch(SourcePos position, String condition, String target) {
            super(position);
            this.condition = condition;
            this.target = target;
        }

        public String condition() {
            return condition;
        }

        public String target() {
            return target;
        }
    }

    /**
     * {@code eval(...)} used as a statement: do one operation, throw the value
     * away, and leave the flags defined.
     *
     * <p>It is the arithmetic counterpart of a comparison on a line of its own,
     * and like a comparison it is one operation, so what the flags are afterwards
     * is not a matter of inference ({@code docs/ir.md} §5.1).
     */
    public static final class Eval extends Item {

        private final Operation operation;

        public Eval(SourcePos position, Operation operation) {
            super(position);
            this.operation = operation;
        }

        public Operation operation() {
            return operation;
        }
    }

    /**
     * An inline assembly block: the escape hatch for register-based interfaces
     * ({@code docs/ir.md} §9).
     *
     * <p>The body is assembly, in the syntax of {@code docs/asm.md}, and is
     * kept as parsed instructions rather than as raw text: it is printed back
     * canonically, and it is what the assembler will encode.
     *
     * <p>{@code clobbers} names the registers the block destroys. It does
     * <em>not</em> yet name the flags the block reads: {@code docs/ir.md} §9
     * requires that declaration but gives no syntax for it, so it is not
     * implemented.
     */
    public static final class InlineAsm extends Item {

        private final List<String> clobbers;
        private final List<Instruction> body;

        public InlineAsm(SourcePos position, List<String> clobbers, List<Instruction> body) {
            super(position);
            this.clobbers = Collections.unmodifiableList(new ArrayList<String>(clobbers));
            this.body = Collections.unmodifiableList(new ArrayList<Instruction>(body));
        }

        public List<String> clobbers() {
            return clobbers;
        }

        public List<Instruction> body() {
            return body;
        }
    }
}
