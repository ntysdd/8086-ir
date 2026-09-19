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
     * statement or a directive. Nothing is reserved, so a word here can also be a
     * name, and {@link #words} walks them to check that.
     */
    private static final Set<String> STRUCTURE = new LinkedHashSet<String>(Arrays.asList(
            "var", "ret", "asm", "jmp", "cmp", "test", "movreg",
            "target", "org", "entry",
            ".if", ".elseif", ".else", ".endif", ".while", ".endw"));

    /**
     * The words that are only ever meaningful where a name is not written: after
     * {@code =} nothing makes {@code eval} an operation, {@code to} means something
     * only inside {@code pad}, {@code in} and {@code writethrough} only after the type of a
     * declaration and the home it applies to ({@code docs/ir.md} §3.1.2), and {@code with}
     * only in front of the register list of a statement that is an interface (§11).
     * A plain name may be one of these — that is the liberal input side — and
     * {@link #words} walks them for the same reason it walks {@link #STRUCTURE}: a
     * word the surface knows is exactly what a name may be.
     */
    private static final Set<String> ELSEWHERE = new LinkedHashSet<String>(Arrays.asList(
            "eval", "expr", "volatile", "clobbers", "pad", "to", "in", "writethrough",
            "with"));

    private Vocabulary() {
    }

    /**
     * Whether canonical IR text marks this name.
     *
     * <p>Always, unless the compiler made the name up itself
     * ({@code ..@}, {@code docs/ir.md} §3.1.1). Every position the IR printer writes a
     * name in belongs to the author: a register is a word of the machine, and the
     * three places the machine's words appear — a segment, a clobber list, and the
     * body of an inline block — print themselves and never come through here. So the
     * surface's own example holds: {@code var ax: i16} is a variable, and it is
     * written {@code $ax} like any other name.
     */
    public static boolean markedWhenPrinted(String name) {
        return !generated(name);
    }

    /**
     * Whether assembly text marks this name.
     *
     * <p>Unless it is spelled like one of the target's registers, because in that
     * language a register name <em>is</em> the register: {@code mov ax, 1} is the
     * machine's {@code ax}, and a symbol of that name has to say so (§3.1.1 gives
     * {@code $ax}). The IR printer does not have that exception because its positions
     * do, and that is the whole of the difference between the two rules.
     *
     * <p>What neither rule asks about is the vocabulary. Marking only the names that
     * could be read as a word of the surface made the canonical form of a program
     * depend on the compiler's word list, so that adding a word silently rewrote the
     * text of every program which had used it as a name — which is what happened the
     * day {@code in} became a word. A total rule has no such moment, and it is also
     * what a reader wants: there is never a name to be told apart from a word.
     */
    public static boolean markedWhenPrinted(String name, Target target) {
        return !generated(name) && !target.isRegister(name);
    }

    /**
     * Whether a spelling could be a name at all: a symbol like {@code -} is an
     * operator, and a name beginning with a dot is the sugar's ({@link #theSugarsDot}),
     * so neither is something the audit can try as a name.
     */
    private static boolean isWordShape(String name) {
        if (name.isEmpty()) {
            return false;
        }
        char first = name.charAt(0);
        return first == '_' || Character.isLetter(first);
    }

    /**
     * Whether this name begins with the dot that only the sugar is spelled with
     * ({@code docs/ir.md} §3.1).
     *
     * <p>{@code .if}, {@code .elseif}, {@code .else}, {@code .endif}, {@code .while} and
     * {@code .endw} are the surface's own words, and a dot in front of them is what says
     * so. No name the author writes may begin that way, which is what makes a dot word
     * mean the sugar wherever it stands, and what keeps the assembly text free of the
     * local labels an assembler reads a leading dot as ({@code docs/asm.md} §3). The
     * compiler's own {@code ..@} namespace is not one of these ({@link #generated}).
     */
    public static boolean theSugarsDot(String name) {
        return name.startsWith(".") && !generated(name);
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
