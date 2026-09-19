package i8086.ir;

import i8086.SourcePos;
import i8086.asm.Size;

/**
 * A memory reference in the IR: {@code byte [p+2]}, {@code es:[msg]},
 * {@code word [0x1234]}.
 *
 * <p>The address is a near pointer — a value of two bytes — written either as a
 * name and a byte displacement or as a displacement alone. Whether that name is
 * a variable, and therefore an address held in a register, or a label, and
 * therefore a fixed address, is not a distinction the syntax makes: it is what
 * the name resolves to, which the verifier decides and which the target turns
 * into an addressing mode. That is the spine of the whole design in one field
 * ({@code docs/ir.md} §2, §3.4).
 *
 * <p>{@code [open]} a general address expression — {@code [p + i*2]} — is not
 * written here yet.
 */
public final class MemoryOperand {

    private final SourcePos position;
    private final Size size;
    private final String segment;
    private final String base;
    private final long displacement;

    public MemoryOperand(SourcePos position, Size size, String segment, String base,
                         long displacement) {
        this.position = position;
        this.size = size;
        this.segment = segment;
        this.base = base;
        this.displacement = displacement;
    }

    /** Where the operand was written, for diagnostics. */
    public SourcePos position() {
        return position;
    }

    /**
     * How wide the access is, when the text says so.
     *
     * <p>Null means the text did not say, and the width has to come from
     * somewhere else — normally the other side of the assignment
     * ({@code docs/ir.md} §3.4).
     */
    public Size size() {
        return size;
    }

    /** The segment register named before the bracket, or null for the default. */
    public String segment() {
        return segment;
    }

    /** The name the address is based on, or null when it is a bare displacement. */
    public String base() {
        return base;
    }

    /** The byte displacement added to the base. Zero when none was written. */
    public long displacement() {
        return displacement;
    }
}
