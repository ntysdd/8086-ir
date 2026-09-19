package i8086.ir;

import i8086.asm.Size;
import i8086.target.Target;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The words this surface gives a meaning to, and the two questions asked about
 * them.
 *
 * <p>There is exactly one question here now, and the answer to "what may be a plain
 * name" is "anything". A word's meaning is decided by where it stands, because
 * values never stand next to each other in this surface: {@code eval(a adc b)} is
 * an operation and {@code eval(adc + 1)} is a variable, and no reading of either
 * is also a reading of the other. The two exceptions are not exceptions to that
 * rule but to the things around it — a name the compiler generated (§7.2), and the
 * sugar, which is read as a shape rather than as a statement (§7.2) — and both are
 * dealt with where they arise.
 *
 * <p>What remains is {@link #markedWhenPrinted}, which is not about what may be
 * written but about what is written back: the printer writes the canonical form,
 * and a reader of canonical text should never have to work out whether the
 * {@code eval} in front of them is a variable or the surface's word. The same
 * split as {@code docs/ir.md} §4.4, where thirty spellings are accepted and one is
 * written back.
 */
public final class Vocabulary {

    /**
     * The words that shape the text rather than asking for something: they begin a
     * statement or a directive, and a plain name may not be one.
     */
    private static final Set<String> STRUCTURE = new LinkedHashSet<String>(Arrays.asList(
            "var", "ret", "asm", "jmp", "cmp", "test",
            "target", "org", "entry",
            ".if", ".elseif", ".else", ".endif", ".while", ".endw"));

    /**
     * The words that are only ever meaningful where a name is not written: after
     * {@code =} nothing makes {@code eval} an operation, and {@code to} means
     * something only inside {@code pad}. A plain name may be one of these — that is
     * the liberal input side — and the printer marks it anyway, because a reader has
     * no way to know that {@code eval} here is the author's.
     */
    private static final Set<String> ELSEWHERE = new LinkedHashSet<String>(Arrays.asList(
            "eval", "expr", "volatile", "clobbers", "pad", "to"));

    private Vocabulary() {
    }

    /**
     * Whether the printer writes {@code $} in front of this name: everything the
     * surface knows that could be a spelling a name has. A symbol like {@code -} is
     * left out, because a name cannot be one, so there is nothing to mark.
     */
    public static boolean markedWhenPrinted(String name, Target target) {
        if (!isWordShape(name)) {
            return false;
        }
        return Size.named(name) != null
                || Size.fromDirective(name) != null
                || Operator.named(name) != null
                || Conversion.named(name) != null
                || Type.named(name) != null
                || target.condition(name) != null
                || target.statementOperator(name) != null
                || target.isRegister(name)
                || target.isSegmentRegister(name)
                || STRUCTURE.contains(name)
                || ELSEWHERE.contains(name);
    }

    /**
     * Whether a spelling could be a name at all: a symbol like {@code -} is an
     * operator and never a name, so it is neither reserved nor marked.
     */
    private static boolean isWordShape(String name) {
        if (name.isEmpty()) {
            return false;
        }
        char first = name.charAt(0);
        return first == '.' || first == '_' || Character.isLetter(first);
    }

    /**
     * Whether this name is one the compiler generated for itself: it begins with
     * {@code ..@} ({@code docs/ir.md} §7.2).
     */
    public static boolean generated(String name) {
        return name.startsWith("..@");
    }

    /**
     * Every word of the wide list that could be a name, in a fixed order, for the
     * test that checks the escape works for all of them. A symbol is left out: it is
     * not a spelling a name could have, which is why it is neither reserved nor
     * marked.
     */
    public static List<String> words(Target target) {
        List<String> words = new ArrayList<String>();
        for (Size size : Size.values()) {
            words.add(size.spelling());
            words.add(size.directive());
        }
        for (Operator operator : Operator.values()) {
            words.add(operator.spelling());
        }
        for (Conversion conversion : Conversion.values()) {
            words.add(conversion.spelling());
        }
        for (Type type : Type.values()) {
            words.add(type.spelling());
        }
        for (String word : target.conditions()) {
            words.add(word);
        }
        for (String word : target.statementWords()) {
            words.add(word);
        }
        for (String word : target.valueRegisters()) {
            words.add(word);
        }
        words.addAll(STRUCTURE);
        words.addAll(ELSEWHERE);
        List<String> nameable = new ArrayList<String>();
        for (String word : words) {
            if (isWordShape(word)) {
                nameable.add(word);
            }
        }
        return nameable;
    }
}
