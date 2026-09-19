package i8086.ir;

import i8086.SourcePos;

/**
 * Something a statement writes.
 *
 * <p>A name is a variable — a virtual register, not a memory location — and a
 * memory operand is a store. That difference is the whole reason a variable
 * read is not a load ({@code docs/ir.md} §3.1).
 */
public abstract class Place {

    private final SourcePos position;

    Place(SourcePos position) {
        this.position = position;
    }

    public SourcePos position() {
        return position;
    }

    /** A variable. */
    public static final class Name extends Place {

        private final String name;

        public Name(SourcePos position, String name) {
            super(position);
            this.name = name;
        }

        public String name() {
            return name;
        }
    }

    /** A store into memory. */
    public static final class Memory extends Place {

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
