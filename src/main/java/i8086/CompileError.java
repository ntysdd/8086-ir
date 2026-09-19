package i8086;

/**
 * An error in the input, reported with the position it was found at.
 *
 * <p>Unsupported input is a hard error: there is no silent fallback and no
 * best-effort downgrade, so every failure a user can cause travels as one of
 * these. {@link #format()} is the single-line form diagnostics are printed in.
 */
public class CompileError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // Not serializable. Diagnostics are never serialized, and making every value
    // type Serializable to satisfy this lint would commit a value type to a
    // serialized form nobody needs, so the lint is suppressed here and nowhere
    // else. See build.bat for the rule this follows.
    @SuppressWarnings("serial")
    private final SourcePos position;

    public CompileError(SourcePos position, String message) {
        super(message);
        if (position == null) {
            throw new NullPointerException("position");
        }
        this.position = position;
    }

    public SourcePos position() {
        return position;
    }

    /** {@code file:line:column: message} — the form every diagnostic is printed in. */
    public String format() {
        return position + ": " + getMessage();
    }
}
