package i8086.asm;

/**
 * How a number is written.
 *
 * <p>One spelling for the whole project, so that the IR printer, the assembly
 * emitter and the encoder cannot drift apart. It is deliberately not "whatever
 * the input said": a printed form has to be canonical for output to be
 * byte-identical run after run ({@code AGENTS.md}, invariant 6).
 *
 * <p>A single digit stays decimal, because that is how it is read — a DOS
 * function number is {@code mov ah, 9}, not {@code mov ah, 0x9} — and
 * everything else is hexadecimal, which is how addresses and masks are read.
 */
public final class Numbers {

    private Numbers() {
    }

    /** The canonical spelling of a value: decimal for one digit, else hexadecimal. */
    public static String spelling(long value) {
        if (value >= 0 && value <= 9) {
            return Long.toString(value);
        }
        return "0x" + Long.toHexString(value);
    }
}
