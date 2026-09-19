package i8086.ir;

import java.util.Locale;

/**
 * A comparison, before anyone has said which flag test it becomes.
 *
 * <p>It is IR vocabulary and not machine vocabulary: {@code <} is {@code <} on
 * any machine, while whether that is {@code jb} or {@code jl} is a fact about
 * this one's flags ({@code docs/ir.md} §7.2).
 *
 * <p>Comparisons are not value expressions — there is no {@code bool} in the IR
 * — so this exists for the control-flow sugar, which needs one, and for nothing
 * else. A comparison's result is a branch taken or not.
 */
public enum Comparison {

    EQUAL("=="),
    NOT_EQUAL("!="),
    LESS("<"),
    LESS_OR_EQUAL("<="),
    GREATER(">"),
    GREATER_OR_EQUAL(">=");

    private final String spelling;

    Comparison(String spelling) {
        this.spelling = spelling;
    }

    public String spelling() {
        return spelling;
    }

    /** The comparison this word names, or null if it names none. */
    public static Comparison named(String word) {
        String name = word.toLowerCase(Locale.ROOT);
        for (Comparison comparison : values()) {
            if (comparison.spelling.equals(name)) {
                return comparison;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return spelling;
    }
}
