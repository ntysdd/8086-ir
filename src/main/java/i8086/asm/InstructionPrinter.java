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
 * module, and the assembly emitter, which writes them as the program.
 */
public final class InstructionPrinter {

    private InstructionPrinter() {
    }

    public static String print(Instruction instruction) {
        StringBuilder text = new StringBuilder();
        text.append(instruction.mnemonic());
        List<Operand> operands = instruction.operands();
        for (int i = 0; i < operands.size(); i++) {
            text.append(i == 0 ? " " : ", ");
            text.append(print(operands.get(i)));
        }
        return text.toString();
    }

    public static String print(Operand operand) {
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
            return "offset " + ((Operand.Offset) operand).name();
        }
        return printMemory((Operand.Memory) operand);
    }

    private static String printMemory(Operand.Memory memory) {
        StringBuilder text = new StringBuilder();
        if (memory.size() != null) {
            text.append(memory.size().spelling()).append(' ');
        }
        if (memory.segment() != null) {
            text.append(memory.segment()).append(':');
        }
        text.append('[');
        List<Operand.Memory.Atom> atoms = memory.atoms();
        for (int i = 0; i < atoms.size(); i++) {
            Operand.Memory.Atom atom = atoms.get(i);
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
