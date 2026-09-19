package i8086.ir;

import java.util.Locale;

/**
 * The type of a value: a width and a signedness.
 *
 * <p>Signedness belongs to the value, and an operation follows it; a single
 * instruction may override it, which is what the mnemonic forms are for
 * ({@code docs/ir.md} §3.2, §5.5).
 *
 * <p>There is deliberately no pointer type. A near pointer is a {@code u16} byte
 * offset and a far pointer is a {@code u32} seg:offset pair, so a pointer is a
 * value like any other and pointer arithmetic is byte arithmetic (§3.3). There is
 * no {@code u64} either.
 */
public enum Type {

    U8("u8", 1, false),
    U16("u16", 2, false),
    U32("u32", 4, false),
    I8("i8", 1, true),
    I16("i16", 2, true),
    I32("i32", 4, true);

    private final String spelling;
    private final int bytes;
    private final boolean signed;

    Type(String spelling, int bytes, boolean signed) {
        this.spelling = spelling;
        this.bytes = bytes;
        this.signed = signed;
    }

    public String spelling() {
        return spelling;
    }

    public int bytes() {
        return bytes;
    }

    public boolean isSigned() {
        return signed;
    }

    /**
     * Whether a literal can be written as a value of this type.
     *
     * <p>It is a question about bits, not about signs: {@code i16} holds
     * {@code 0xFFFF} as readily as {@code u16} does, because that is the same
     * sixteen bits and this is assembly.
     */
    public boolean canHold(long value) {
        return value >= 0 && value < (1L << (bytes * 8));
    }

    /** The type named by the given word, or null if it names none. */
    public static Type named(String word) {
        String name = word.toLowerCase(Locale.ROOT);
        for (Type type : values()) {
            if (type.spelling.equals(name)) {
                return type;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return spelling;
    }
}
