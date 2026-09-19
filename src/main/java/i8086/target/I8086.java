package i8086.target;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
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
}
