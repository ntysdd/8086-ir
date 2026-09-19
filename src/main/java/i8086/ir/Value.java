package i8086.ir;

import i8086.SourcePos;

/**
 * Something a statement reads.
 *
 * <p>There are three shapes, and the verifier decides what each one <em>is</em>:
 * a {@link Name} is a variable's value or a label's address depending on what
 * the name resolves to, and a {@link Memory} is a load through a register or
 * from a fixed address for the same reason. Keeping that decision out of the
 * parser is what lets one parser serve the whole surface
 * ({@code docs/asm.md} §1).
 *
 * <p>{@code [open]} {@code eval(...)}, {@code expr(...)} and the conversions are
 * not here yet ({@code docs/ir.md} §5).
 */
public abstract class Value {

    private final SourcePos position;

    Value(SourcePos position) {
        this.position = position;
    }

    public SourcePos position() {
        return position;
    }

    /** A name: a variable, or a label used where an address is wanted. */
    public static final class Name extends Value {

        private final String name;

        public Name(SourcePos position, String name) {
            super(position);
            this.name = name;
        }

        public String name() {
            return name;
        }
    }

    /** A literal. It has no type of its own; it takes the width of where it goes. */
    public static final class Number extends Value {

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

    /** A load. */
    public static final class Memory extends Value {

        private final MemoryOperand operand;

        public Memory(SourcePos position, MemoryOperand operand) {
            super(position);
            this.operand = operand;
        }

        public MemoryOperand operand() {
            return operand;
        }
    }
}
