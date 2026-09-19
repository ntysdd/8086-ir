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
 * <p>They are one list in one place because two questions need the same list and
 * used to answer them from two hand-written sets that drifted apart: a word the
 * surface knows sat in one and not the other, which is how {@code add} came to be
 * usable as a variable name while {@code shl} — the same kind of word, from the
 * same table — was not.
 *
 * <p>The two questions are deliberately not the same question:
 *
 * <ul>
 *   <li>{@link #reserved} — may a plain name be this word? This is what the parser
 *       asks before it accepts a declaration, and it is the <em>narrow</em> list:
 *       a word is reserved when the surface would otherwise have to guess what it
 *       means. It only ever grows, never shrinks, because a program that compiles
 *       today has to compile tomorrow ({@code AGENTS.md}).
 *   <li>{@link #markedWhenPrinted} — does the printer have to write {@code $} in
 *       front of it? This is the <em>wide</em> list: any name that looks like a
 *       word of the language is written with the marker, because the printer
 *       writes the canonical form and a reader should never have to work out
 *       whether {@code eval} here is a variable or the surface's word. The same
 *       split as {@code docs/ir.md} §4.4, where thirty spellings are accepted and
 *       one is written back.
 * </ul>
 *
 * <p>Either list is escapable the same way: {@code $name} is the author's name
 * whatever it looks like ({@code docs/ir.md} §3.1).
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
     * Whether a plain name may not be this word, which is the narrow list: a word
     * whose meaning the surface cannot tell from a name where it stands.
     */
    public static boolean reserved(String name, Target target) {
        return !isWordShape(name)
                || Size.named(name) != null
                || Size.fromDirective(name) != null
                || Operator.named(name) != null
                || Conversion.named(name) != null
                || target.condition(name) != null
                || STRUCTURE.contains(name);
    }

    /**
     * Whether the printer writes {@code $} in front of this name, which is the wide
     * list: everything {@link #reserved} holds, plus the words that are only
     * meaningful in a position a name is not written in — a type after {@code :}, a
     * mnemonic that begins a statement, a register inside an inline block, and
     * {@link #ELSEWHERE}. Marking those costs nothing and saves the reader the
     * question.
     */
    public static boolean markedWhenPrinted(String name, Target target) {
        return reserved(name, target)
                || Type.named(name) != null
                || target.statementOperator(name) != null
                || target.isRegister(name)
                || target.isSegmentRegister(name)
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
