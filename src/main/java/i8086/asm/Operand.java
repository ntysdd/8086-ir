package i8086.asm;

import i8086.SourcePos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One operand of an instruction, in the shape the syntax allows.
 *
 * <p>The shape is all this level knows. Whether a name is a register, a label
 * or something else, and which of these shapes a given mnemonic accepts, are
 * facts about the target and are decided against the target description, not
 * here ({@code docs/asm.md} §1).
 */
public abstract class Operand {

    private final SourcePos position;

    Operand(SourcePos position) {
        this.position = position;
    }

    public SourcePos position() {
        return position;
    }

    /** A bare name: a register, or a label used where an address is wanted. */
    public static final class Name extends Operand {

        private final String name;

        public Name(SourcePos position, String name) {
            super(position);
            this.name = name;
        }

        public String name() {
            return name;
        }
    }

    /**
     * A register nobody has chosen yet.
     *
     * <p>This is what instruction selection produces and what register
     * allocation removes: a variable becomes code before anyone has said which
     * register it lives in. The name is the variable's, and it is deliberately
     * unprintable — {@link InstructionPrinter} refuses one — so that "this can be
     * written out" and "every register has been decided" are the same thing, and
     * getting the order wrong is a loud failure rather than a line of assembly
     * naming something the machine has never heard of.
     */
    public static final class Virtual extends Operand {

        private final String name;

        public Virtual(SourcePos position, String name) {
            super(position);
            this.name = name;
        }

        public String name() {
            return name;
        }

        /** The same operand with the register it turned out to live in. */
        public Name resolvedTo(String register) {
            return new Name(position(), register);
        }
    }

    /** A numeric literal. */
    public static final class Number extends Operand {

        private final long value;
        private final String spelling;

        public Number(SourcePos position, long value, String spelling) {
            super(position);
            this.value = value;
            this.spelling = spelling;
        }

        public long value() {
            return value;
        }

        /** The literal as written, for diagnostics. */
        public String spelling() {
            return spelling;
        }
    }

    /** {@code offset name}: the address of a label, as an immediate. */
    public static final class Offset extends Operand {

        private final String name;

        public Offset(SourcePos position, String name) {
            super(position);
            this.name = name;
        }

        public String name() {
            return name;
        }
    }

    /**
     * A memory reference: an optional size prefix, an optional segment
     * override, and the contents of the brackets.
     *
     * <p>The contents are a list of {@link Atom}s added and subtracted, which
     * is as much structure as the syntax has. Which atoms may be combined, and
     * which register may be a base rather than an index, is the 8086's
     * constraint and therefore the target's to enforce.
     */
    public static final class Memory extends Operand {

        /** One term inside the brackets: a name or a number, added or subtracted. */
        public static final class Atom {

            private final boolean subtracted;
            private final String name;
            private final long number;

            private Atom(boolean subtracted, String name, long number) {
                this.subtracted = subtracted;
                this.name = name;
                this.number = number;
            }

            public static Atom ofName(String name) {
                return new Atom(false, name, 0);
            }

            public static Atom ofNumber(long number) {
                return new Atom(false, null, number);
            }

            public Atom subtracted() {
                return new Atom(true, name, number);
            }

            public boolean isSubtracted() {
                return subtracted;
            }

            /** True when this atom is a number rather than a name. */
            public boolean isNumber() {
                return name == null;
            }

            public String name() {
                return name;
            }

            public long number() {
                return number;
            }
        }

        private final Size size;
        private final String segment;
        private final List<Atom> atoms;

        public Memory(SourcePos position, Size size, String segment, List<Atom> atoms) {
            super(position);
            this.size = size;
            this.segment = segment;
            this.atoms = Collections.unmodifiableList(new ArrayList<Atom>(atoms));
        }

        public Size size() {
            return size;
        }

        /** The segment register named before the bracket, or null. */
        public String segment() {
            return segment;
        }

        public List<Atom> atoms() {
            return atoms;
        }
    }
}
