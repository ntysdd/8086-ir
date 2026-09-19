package i8086.target;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Intel 8086.
 *
 * <p>Everything here is a fact about this processor, and it is here rather than
 * in a pass or a parser so that there is exactly one place to look for it and
 * one place to change it ({@code AGENTS.md} invariant 2).
 */
public final class I8086 implements Target {

    /**
     * The register names, in the order a person writes them: eight-bit first,
     * then sixteen-bit, then the segment registers.
     */
    private static final Set<String> REGISTERS = names(
            "al", "cl", "dl", "bl", "ah", "ch", "dh", "bh",
            "ax", "cx", "dx", "bx", "sp", "bp", "si", "di",
            "es", "cs", "ss", "ds");

    private static final Set<String> SEGMENT_REGISTERS = names("es", "cs", "ss", "ds");

    /**
     * Every branch word, mapped to the condition it names.
     *
     * <p>The 8086 has sixteen conditions and thirty spellings of them: {@code jb},
     * {@code jc} and {@code jnae} are the same test of the carry flag, written
     * for three different readers, and {@code je} and {@code jz} are the same
     * test of the zero flag. The first argument is the spelling the compiler
     * treats as canonical, which is the one the IR printer writes back, and the
     * rest are accepted and normalised onto it.
     *
     * <p>A condition is not a register: {@code jcxz} tests a register and not a
     * flag, so it is not here ({@code docs/ir.md} §11 leaves it open).
     */
    private static final Map<String, String> CONDITIONS = conditionWords();

    private static Map<String, String> conditionWords() {
        Map<String, String> words = new LinkedHashMap<String, String>();
        condition(words, "jo");
        condition(words, "jno");
        condition(words, "js");
        condition(words, "jns");
        condition(words, "jz", "je");
        condition(words, "jnz", "jne");
        condition(words, "jp", "jpe");
        condition(words, "jnp", "jpo");
        condition(words, "jc", "jb", "jnae");
        condition(words, "jnc", "jae", "jnb");
        condition(words, "ja", "jnbe");
        condition(words, "jbe", "jna");
        condition(words, "jg", "jnle");
        condition(words, "jge", "jnl");
        condition(words, "jl", "jnge");
        condition(words, "jle", "jng");
        return Collections.unmodifiableMap(words);
    }

    private static void condition(Map<String, String> words, String canonical, String... aliases) {
        words.put(canonical, canonical);
        for (String alias : aliases) {
            words.put(alias, canonical);
        }
    }

    private static Set<String> names(String... names) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(names)));
    }

    @Override
    public String name() {
        return "8086";
    }

    @Override
    public boolean isRegister(String name) {
        return REGISTERS.contains(name);
    }

    @Override
    public boolean isSegmentRegister(String name) {
        return SEGMENT_REGISTERS.contains(name);
    }

    @Override
    public String condition(String word) {
        return CONDITIONS.get(word);
    }

    @Override
    public List<String> conditions() {
        // The canonical spellings in the order they were declared: the map holds
        // each of them, and a canonical word is the one that maps to itself.
        List<String> canonical = new ArrayList<String>();
        for (Map.Entry<String, String> entry : CONDITIONS.entrySet()) {
            if (entry.getKey().equals(entry.getValue())) {
                canonical.add(entry.getKey());
            }
        }
        return Collections.unmodifiableList(canonical);
    }
}
