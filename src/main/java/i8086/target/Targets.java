package i8086.target;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The targets this compiler knows, by the name a module writes.
 *
 * <p>Registration is explicit: there is no scanning, no reflection and no
 * annotation, so the set of targets is one short list a person can read
 * ({@code AGENTS.md}, "no reflection"). The map is immutable, so it is a
 * constant rather than state.
 */
public final class Targets {

    private static final Map<String, Target> BY_NAME;

    static {
        Map<String, Target> targets = new LinkedHashMap<String, Target>();
        Target i8086 = new I8086();
        targets.put(i8086.name(), i8086);
        BY_NAME = Collections.unmodifiableMap(targets);
    }

    private Targets() {
    }

    /** The target with that name, or null when no such target is known. */
    public static Target byName(String name) {
        return BY_NAME.get(name);
    }

    /** Whether any known target has that name. */
    public static boolean isKnown(String name) {
        return BY_NAME.containsKey(name);
    }

    /** The names of all known targets, for a diagnostic. */
    public static String knownNames() {
        StringBuilder names = new StringBuilder();
        for (String name : BY_NAME.keySet()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(name);
        }
        return names.toString();
    }
}
