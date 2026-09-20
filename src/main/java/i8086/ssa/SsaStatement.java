package i8086.ssa;

import i8086.ir.Item;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One item in SSA form, and the flag versions it defines.
 *
 * <p>The item is the one from the module with every variable renamed: what it
 * writes carries the version it defines, so a statement says what it defines just
 * by being read ({@code x#3 = 0}). The one thing an item cannot say is that it
 * defined a flag, because the surface never mentions them
 * ({@code docs/ir.md} §4.1), so that is recorded alongside it — one version per
 * flag, because a statement may define the arithmetic flags, the direction flag,
 * both, or neither.
 */
public final class SsaStatement {

    private final Item item;
    private final Map<String, String> definedFlags;

    public SsaStatement(Item item, Map<String, String> definedFlags) {
        this.item = item;
        this.definedFlags = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(definedFlags));
    }

    /** The version this statement defines for one flag, or null when it defines none. */
    public SsaStatement(Item item, String flag, String version) {
        this(item, version == null
                ? Collections.<String, String>emptyMap()
                : Collections.singletonMap(flag, version));
    }

    /** The item, with its variables renamed. */
    public Item item() {
        return item;
    }

    /** The flag versions this statement defines, by flag name. */
    public Map<String, String> definedFlags() {
        return definedFlags;
    }

    /** The version this statement defines for this flag, or null when it defines none. */
    public String definedFlag(String flag) {
        return definedFlags.get(flag);
    }

    @Override
    public String toString() {
        return String.valueOf(item);
    }
}
