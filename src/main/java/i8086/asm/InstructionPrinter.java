package i8086.asm;

import java.util.List;

/**
 * Writes an instruction or an operand back out, canonically.
 *
 * <p>Canonical means: lower-case mnemonic, one space between the mnemonic and
 * its operands, {@code ", "} between operands, no space inside the brackets,
 * and numbers spelled by {@link Numbers}. Printing something parsed and parsing
 * it again gives the same module ({@code AGENTS.md}, invariant 5), and printing
 * the same module twice gives the same text (invariant 6).
 *
 * <p>This is shared by the IR printer, which writes inline blocks inside an IR
 * module, and the assembly emitter, which writes them as the program. Those two
 * want different spellings of two things, which is what {@link Dialect} is for: the
 * IR printer writes our own dialect, and the emitter writes the one NASM reads.
 */
public final class InstructionPrinter {

    private InstructionPrinter() {
    }

    public static String print(Instruction instruction) {
        return print(instruction, Dialect.CANONICAL);
    }

    public static String print(Instruction instruction, Dialect dialect) {
        StringBuilder text = new StringBuilder();
        text.append(instruction.mnemonic());
        List<Operand> operands = instruction.operands();
        for (int i = 0; i < operands.size(); i++) {
            text.append(i == 0 ? " " : ", ");
            text.append(print(operands.get(i), dialect));
        }
        return text.toString();
    }

    public static String print(Operand operand) {
        return print(operand, Dialect.CANONICAL);
    }

    public static String print(Operand operand, Dialect dialect) {
        if (operand instanceof Operand.Name) {
            return ((Operand.Name) operand).name();
        }
        if (operand instanceof Operand.Virtual) {
            // Not input, so not a diagnostic: the compiler printed before it
            // decided where this value lives. Loud, because the alternative is
            // assembly naming a register the machine has never heard of.
            throw new IllegalStateException(
                    "register allocation has not run: '" + ((Operand.Virtual) operand).name()
                            + "' is still a virtual register");
        }
        if (operand instanceof Operand.Number) {
            return Numbers.spelling(((Operand.Number) operand).value());
        }
        if (operand instanceof Operand.Offset) {
            // NASM has no 'offset': a bare symbol in an operand is already the
            // address there, where a bracketed one is what it points at.
            String name = ((Operand.Offset) operand).name();
            return dialect == Dialect.NASM ? name : "offset " + name;
        }
        return printMemory((Operand.Memory) operand, dialect);
    }

    private static String printMemory(Operand.Memory memory, Dialect dialect) {
        StringBuilder text = new StringBuilder();
        if (memory.size() != null) {
            text.append(memory.size().spelling()).append(' ');
        }
        // The segment override is the one thing whose position differs: NASM puts it
        // inside the brackets, our dialect in front of them (docs/asm.md §4).
        if (memory.segment() != null && dialect == Dialect.CANONICAL) {
            text.append(memory.segment()).append(':');
        }
        text.append('[');
        if (memory.segment() != null && dialect == Dialect.NASM) {
            text.append(memory.segment()).append(':');
        }
        List<Operand.Memory.Atom> atoms = memory.atoms();
        for (int i = 0; i < atoms.size(); i++) {
            Operand.Memory.Atom atom = atoms.get(i);
            if (atom.isVirtual()) {
                // An address waiting for a register is as unprintable as an operand
                // that is one, and for the same reason: an instruction is printable
                // exactly when every register in it has been decided.
                throw new IllegalStateException("register allocation has not run: '"
                        + atom.name() + "' is still a virtual address");
            }
            String value = atom.isNumber() ? Numbers.spelling(atom.number()) : atom.name();
            if (i == 0) {
                if (atom.isSubtracted()) {
                    text.append('-');
                }
            } else {
                text.append(atom.isSubtracted() ? '-' : '+');
            }
            text.append(value);
        }
        return text.append(']').toString();
    }
}
