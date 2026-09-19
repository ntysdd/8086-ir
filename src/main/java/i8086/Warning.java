package i8086;

/**
 * Something worth saying about a program that is not a reason to refuse it.
 *
 * <p>A warning is for the place where the program is correct and the compiler will do
 * what it says, but what the program asked for is not what it is going to get — or, in
 * the one case there is so far, a value it saves may not stay where it put it. That is
 * the opposite of an error, which is the compiler saying it will not compile this at
 * all; a warning is the compiler compiling it and saying what it did.
 *
 * <p>It carries a position for the same reason an error does: what makes the message
 * useful is that it points at the line which has to be read ({@code AGENTS.md},
 * invariant 7). {@link #format()} is the single-line form diagnostics are printed in,
 * and it says which of the two kinds this is, because a stream can carry both.
 */
public final class Warning {

    private final SourcePos position;
    private final String message;

    public Warning(SourcePos position, String message) {
        if (position == null) {
            throw new NullPointerException("position");
        }
        this.position = position;
        this.message = message;
    }

    public SourcePos position() {
        return position;
    }

    public String message() {
        return message;
    }

    /** {@code file:line:column: warning: message} — the form diagnostics are printed in. */
    public String format() {
        return position + ": warning: " + message;
    }

    @Override
    public String toString() {
        return format();
    }
}
