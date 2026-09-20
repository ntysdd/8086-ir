package i8086.asm;

import java.util.Locale;

/**
 * An instruction prefix: {@code rep movsb}, {@code lock add [p], 1}.
 *
 * <p>A prefix is part of the instruction and not of its operands, which is why it is a field of its
 * own rather than a word glued onto the front of the mnemonic: {@code rep movsb} does one thing the
 * machine does, and whoever encodes it has to put a byte in front of the opcode rather than look up
 * a mnemonic with a space in it ({@code AGENTS.md}, "strings are not data structures").
 *
 * <p>It is one prefix and not a list, because that is what this machine has room for in the shape
 * this surface can write: an instruction that wants two of them, like {@code rep} on a {@code lock}ed
 * instruction, is not something a boot sector has any use for, and nothing here guesses.
 *
 * <p>The spellings are the machine's, and the synonyms are the ones every assembler accepts:
 * {@code repe} and {@code repz} are one prefix, and so are {@code repne} and {@code repnz}
 * ({@code docs/asm.md} §3).
 */
public enum Prefix {

    REP("rep"),
    REPE("repe"),
    REPNE("repne"),
    LOCK("lock");

    private final String spelling;

    Prefix(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling the printer writes, which is the one the parser reads back. */
    public String spelling() {
        return spelling;
    }

    /** The prefix named by the given word, or null if it names none. */
    public static Prefix named(String word) {
        String name = word.toLowerCase(Locale.ROOT);
        for (Prefix prefix : values()) {
            if (prefix.spelling.equals(name)) {
                return prefix;
            }
        }
        if (name.equals("repz")) {
            return REPE;
        }
        if (name.equals("repnz")) {
            return REPNE;
        }
        return null;
    }

    @Override
    public String toString() {
        return spelling;
    }
}
