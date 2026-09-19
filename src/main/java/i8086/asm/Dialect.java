package i8086.asm;

/**
 * Which spelling of the assembly text is being written.
 *
 * <p>There are two, and only one of them is ours ({@code docs/asm.md}): the
 * dialect an inline block is written in, which is also what an assembler of our own
 * would read one day. The other is NASM's, for the text that leaves this program —
 * because the pieces of a flat binary are the assembler's business, and NASM is
 * the assembler ({@code README.md}).
 *
 * <p>The differences are small on purpose: we chose a NASM-family syntax from the
 * start, so the translation is four substitutions and not a second language.
 * Keeping them as a <em>dialect</em> rather than editing our own spelling is what
 * lets the two stay apart: the input grammar does not change to suit a tool, and
 * the tool does not have to read a grammar nobody else speaks.
 */
public enum Dialect {

    /**
     * The surface of {@code docs/asm.md}: what an inline assembly block contains,
     * and what the IR printer writes back.
     */
    CANONICAL,

    /**
     * NASM's spelling, for {@code nasm -f bin}.
     *
     * <p>Four things differ, and each has a reason rather than a preference:
     *
     * <ul>
     *   <li>a label used as an address is written plainly — NASM has no
     *       {@code offset}, and a bare symbol already means the address there;
     *   <li>a segment override goes inside the brackets — {@code [es:bx]} rather
     *       than {@code es:[bx]};
     *   <li>{@code pad 32, 0x90} becomes {@code times 32 db 0x90};
     *   <li>{@code pad to 510} becomes {@code times 510-($-$$) db 0}, because only
     *       the assembler knows how long the code before it is — which is the whole
     *       reason that form exists ({@code docs/ir.md} §10.3).
     * </ul>
     */
    NASM
}
