package i8086.ir;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a machine statement does with the flags: the ones it makes its own, and the ones it reads
 * ({@code docs/ir.md} §4.2, §11).
 *
 * <p>Two sets and not a state per flag, because these are the two answers the *target* has: which
 * flags a statement leaves a value of its own in, and which ones decide what it does. The third
 * thing that can happen to a flag — destruction — is not here, because the surface already has a
 * place for it and it is not the target's: a clobber list names what a statement destroys, and
 * naming a flag there takes it away.
 *
 * <p>A statement that says nothing about a flag is taken to leave it exactly as it was, which is the
 * direction whose mistake is a definition that stays rather than a read that sees nothing
 * ({@link i8086.ssa.Effects}).
 */
public final class FlagUse {

    /** A statement that neither makes a flag its own nor reads one. */
    private static final FlagUse NONE = new FlagUse(
            Collections.<String>emptySet(), Collections.<String>emptySet());

    private final Set<String> defined;
    private final Set<String> read;

    private FlagUse(Set<String> defined, Set<String> read) {
        this.defined = Collections.unmodifiableSet(new LinkedHashSet<String>(defined));
        this.read = Collections.unmodifiableSet(new LinkedHashSet<String>(read));
    }

    /** Neither. */
    public static FlagUse none() {
        return NONE;
    }

    /** The flags it makes its own. */
    public static FlagUse defined(String... flags) {
        return new FlagUse(names(flags), Collections.<String>emptySet());
    }

    /** The flags it reads. */
    public static FlagUse reads(String... flags) {
        return new FlagUse(Collections.<String>emptySet(), names(flags));
    }

    /** The flags it makes its own *and* the ones it reads: a repeated compare does both. */
    public static FlagUse of(Set<String> defined, Set<String> read) {
        return defined.isEmpty() && read.isEmpty() ? NONE : new FlagUse(defined, read);
    }

    /** The flags whose value after it is its own. */
    public Set<String> defined() {
        return defined;
    }

    /** The flags whose value decides what it does. */
    public Set<String> read() {
        return read;
    }

    private static Set<String> names(String[] flags) {
        Set<String> names = new LinkedHashSet<String>();
        for (String flag : flags) {
            names.add(flag);
        }
        return names;
    }

    @Override
    public String toString() {
        return "defines " + defined + ", reads " + read;
    }
}
