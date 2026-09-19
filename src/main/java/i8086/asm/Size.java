package i8086.asm;

import java.util.Locale;

/**
 * The size of a memory operand, written as a prefix: {@code byte [p]}.
 *
 * <p>This is a statement about meaning — how many bytes the access touches —
 * and not a request for an encoding. It is required exactly when no other
 * operand implies the size ({@code docs/asm.md} §5).
 *
 * <p>It is deliberately not {@link i8086.ir.Type}: a type is a property of a
 * value, with a width and a signedness, while this is the width of an access.
 * They share a spelling here and nowhere else.
 */
public enum Size {

    BYTE(1, "byte", "db"),
    WORD(2, "word", "dw"),
    DWORD(4, "dword", "dd");

    private final int bytes;
    private final String spelling;
    private final String directive;

    Size(int bytes, String spelling, String directive) {
        this.bytes = bytes;
        this.spelling = spelling;
        this.directive = directive;
    }

    public int bytes() {
        return bytes;
    }

    /** The prefix that states the width of an access: {@code byte [p]}. */
    public String spelling() {
        return spelling;
    }

    /** The directive that defines elements of this size: {@code db}, {@code dw}, {@code dd}. */
    public String directive() {
        return directive;
    }

    /** The size named by the given word, or null if it names none. */
    public static Size named(String word) {
        String name = word.toLowerCase(Locale.ROOT);
        for (Size size : values()) {
            if (size.spelling.equals(name)) {
                return size;
            }
        }
        return null;
    }

    /**
     * The size a data directive defines, or null if the word is not one.
     *
     * <p>{@code db} and {@code byte} are two spellings of one width, and they
     * are not interchangeable: the first defines data, the second states how
     * wide an access is.
     */
    public static Size fromDirective(String word) {
        String name = word.toLowerCase(Locale.ROOT);
        for (Size size : values()) {
            if (size.directive.equals(name)) {
                return size;
            }
        }
        return null;
    }

    /**
     * The size of a value of the given width in bytes, or null if no size has
     * that width.
     */
    public static Size ofBytes(int count) {
        for (Size size : values()) {
            if (size.bytes == count) {
                return size;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return spelling;
    }
}
