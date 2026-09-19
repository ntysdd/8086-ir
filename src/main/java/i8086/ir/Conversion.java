package i8086.ir;

import java.util.Locale;

/**
 * A conversion: how a value of one width becomes a value of another.
 *
 * <p>These four words are the whole mechanism, and there is deliberately no cast
 * syntax ({@code docs/ir.md} §3.5). Widening is not free on this machine — it is
 * `xor ah, ah`, or `cbw`, or `cwd` — so the word that says which extension
 * happens is worth more than a bracket a reader has to decode from the source
 * type.
 *
 * <p>Narrowing is the other direction, and it reuses the vocabulary of memory
 * operands: {@code byte} and {@code word} mean the low byte and the low word
 * whether they qualify an access or a value. {@code dword} is not here because
 * nothing is wider than a double word, so there is nothing to narrow from.
 *
 * <p>Signedness is not part of a conversion. The bits are decided here and what
 * they mean is decided by the type of wherever the value goes, which costs
 * nothing and emits nothing.
 */
public enum Conversion {

    /** Widen, filling the top with zeroes. */
    ZERO_EXTEND("movzx", Direction.WIDEN),

    /** Widen, filling the top with a copy of the sign bit. */
    SIGN_EXTEND("movsx", Direction.WIDEN),

    /** Narrow to the low byte. */
    LOW_BYTE("byte", Direction.NARROW),

    /** Narrow to the low word. */
    LOW_WORD("word", Direction.NARROW);

    /** Which way the width moves. */
    public enum Direction {
        WIDEN,
        NARROW
    }

    private final String spelling;
    private final Direction direction;

    Conversion(String spelling, Direction direction) {
        this.spelling = spelling;
        this.direction = direction;
    }

    public String spelling() {
        return spelling;
    }

    public Direction direction() {
        return direction;
    }

    /** How many bytes the result occupies, or zero when the destination decides. */
    public int resultBytes() {
        switch (this) {
            case LOW_BYTE:
                return 1;
            case LOW_WORD:
                return 2;
            default:
                return 0;
        }
    }

    /** The conversion this word names, or null if it names none. */
    public static Conversion named(String word) {
        String name = word.toLowerCase(Locale.ROOT);
        for (Conversion conversion : values()) {
            if (conversion.spelling.equals(name)) {
                return conversion;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return spelling;
    }
}
