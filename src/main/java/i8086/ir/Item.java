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
     * A data definition: {@code msg: db "..."}, {@code tbl: dw 0x1234, 0x5678}.
     *
     * <p>The label is optional, because {@code db} is also allowed bare. There
     * is no separate "uninitialised" form: space is a repeat of a value, and
     * the value is zero unless written ({@code docs/ir.md} §10).
     */
    public static final class Data extends Item {

        /** One element: a number, or a string of bytes for {@code db}. */
        public static final class Atom {

            private final long number;
            private final String text;

            private Atom(long number, String text) {
                this.number = number;
                this.text = text;
            }

            public static Atom ofNumber(long number) {
                return new Atom(number, null);
            }

            public static Atom ofText(String text) {
                return new Atom(0, text);
            }

            public boolean isText() {
                return text != null;
            }

            public long number() {
                return number;
            }

            public String text() {
                return text;
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
