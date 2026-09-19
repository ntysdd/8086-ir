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

        /**
         * How many bytes this definition puts in the image.
         *
         * <p>A string is one byte per character, and that is exact rather than
         * approximate, because the tokenizer refuses a string that is not printable
         * ASCII. Every other element is as wide as the directive says, a name
         * included: a label's address is a near pointer, so one word.
         *
         * <p>This is what a home is checked against ({@code docs/ir.md} §3.1.2). The
         * address of these bytes is not known until the assembler places them, but
         * their *length* is the front end's to know, which is what makes "is this home
         * wide enough" a static question.
         */
        public long byteCount() {
            long bytes = 0;
            for (Atom atom : atoms) {
                bytes += atom.isText() ? atom.text().length() : elementSize.bytes();
            }
            return bytes;
        }
    }

    /** {@code ret}. */
    public static final class Return extends Item {

        public Return(SourcePos position) {
            super(position);
        }
    }

    /**
     * A declaration: {@code var x: u16}, and optionally where it may live:
     * {@code var x: u16 in cell}.
     *
     * <p>It introduces a mutable virtual register, not a memory location. The
     * input is not SSA; SSA construction renames these away.
     *
     * <p>A <b>home</b> is bytes in the image the value may live in when the registers
     * cannot hold it, and {@code writethrough} says those bytes are written on every
     * definition ({@code docs/ir.md} §3.1.2). Neither is decided here: whether the home
     * is used at all is the allocator's answer, and what the verifier checks is that the
     * name holds bytes and that they are wide enough.
     */
    public static final class Var extends Item {

        private final String name;
        private final Type type;
        private final String home;
        private final boolean writethrough;

        public Var(SourcePos position, String name, Type type, String home,
                   boolean writethrough) {
            super(position);
            this.name = name;
            this.type = type;
            this.home = home;
            this.writethrough = writethrough;
        }

        public String name() {
            return name;
        }

        public Type type() {
            return type;
        }

        /**
         * The bytes this variable may live in, named by the item that declares them,
         * or null when it has no home.
         */
        public String home() {
            return home;
        }

        /**
         * Whether the home is kept current: every definition of this variable writes
         * those bytes, whether or not the value needed somewhere to live.
         */
        public boolean writethrough() {
            return writethrough;
        }
    }

    /**
     * {@code movseg ds, 0}: putting a value into the machine's segmentation state
     * ({@code docs/ir.md} §8.1).
     *
     * <p>It is a statement of its own rather than an assignment, because a name in the position
     * an assignment writes cannot say whether it means the machine's segment register or a
     * variable of that name. The word settles it, and what it settles is that nothing has to be
     * reserved: {@code var ds: u16} is an ordinary variable and {@code ds = 0} assigns it.
     *
     * <p>The source is a value, or a name that is not a variable — which is how a segment
     * register is named, since this surface has no other way to say {@code mov ds, cs}. Writing
     * segmentation state is an effect and defines no value, so SSA has nothing to rename about
     * the statement itself.
     */
    public static final class MovSeg extends Item {

        private final String name;
        private final Value value;
        private final String segment;

        /** {@code movseg ds, 0}: a value, a literal or a variable, is put there. */
        public static MovSeg fromValue(SourcePos position, String name, Value value) {
            return new MovSeg(position, name, value, null);
        }

        /**
         * {@code movseg ds, cs}: another segment register is copied.
         *
         * <p>The name is the machine's either way, which is why it is not a value the rest of the
         * surface could do arithmetic with: reading a segment register into a variable is a
         * different thing, and nothing has asked for it yet.
         */
        public static MovSeg fromSegment(SourcePos position, String name, String segment) {
            return new MovSeg(position, name, null, segment);
        }

        private MovSeg(SourcePos position, String name, Value value, String segment) {
            super(position);
            this.name = name;
            this.value = value;
            this.segment = segment;
        }

        /** The name of the state written: one of the target's segment registers, or its stack. */
        public String name() {
            return name;
        }

        /** What is put there, or null when a segment register is copied instead. */
        public Value value() {
            return value;
        }

        /** The segment register copied, or null when a value is put there instead. */
        public String segment() {
            return segment;
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

    /** {@code jmp label}: go there, whatever the flags say. */
    public static final class Jump extends Item {

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
     * One machine instruction as a statement: {@code int 9}, {@code hlt}, {@code cli}
     * ({@code docs/ir.md} §11).
     *
     * <p>What it is worth is that the compiler *understands* it, where an inline block
     * is opaque in both directions: a block makes a whole module unoptimisable (§9),
     * and this says exactly what it does — an effect, and a list of what it destroys.
     *
     * <p>The clobbers are stamped here by the parser, from the target's worst case or
     * from what the author wrote, and they are what the allocator and SSA read: a
     * machine statement says what it destroys the same way a block does.
     */
    public static final class Machine extends Item {

        private final String mnemonic;
        private final List<Long> operands;
        private final List<String> clobbers;

        public Machine(SourcePos position, String mnemonic, List<Long> operands,
                       List<String> clobbers) {
            super(position);
            this.mnemonic = mnemonic;
            this.operands = Collections.unmodifiableList(new ArrayList<Long>(operands));
            this.clobbers = Collections.unmodifiableList(new ArrayList<String>(clobbers));
        }

        public String mnemonic() {
            return mnemonic;
        }

        /** The immediates it is given: one for {@code int}, none for the rest. */
        public List<Long> operands() {
            return operands;
        }

        /** The registers and flags it destroys, which is what the optimiser believes. */
        public List<String> clobbers() {
            return clobbers;
        }
    }

    /**
     * {@code jmp 0x0000:0x7E00}: a far jump, out of this image and into another.
     *
     * <p>This is how a boot loader hands control to a kernel, and it is a statement
     * rather than something inside a block for one reason: **the compiler knows it
     * leaves**. Nothing after it runs, so nothing after it is reachable, which is the
     * fact the graph needs and the fact a block cannot state ({@code docs/ir.md}
     * §7.1, §9).
     *
     * <p>The address is two numbers. A far pointer whose offset is a label would be
     * the label's place *within the segment*, which nothing here knows until the
     * assembler has placed it; that spelling is still [open] ({@code docs/asm.md} §4).
     */
    public static final class FarJump extends Item {

        private final long segment;
        private final long offset;

        public FarJump(SourcePos position, long segment, long offset) {
            super(position);
            this.segment = segment;
            this.offset = offset;
        }

        public long segment() {
            return segment;
        }

        public long offset() {
            return offset;
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
