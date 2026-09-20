package i8086.asm;

import i8086.ir.Vocabulary;
import i8086.target.Target;

import java.util.List;

/**
 * Writes an instruction or an operand back out, canonically.
 *
 * <p>Canonical means: lower-case mnemonic, one space between the mnemonic and
 * its operands, {@code ", "} between operands, no space inside the brackets, and
 * numbers spelled by {@link Numbers}. A name the author chose carries the {@code $}
 * that says it is one ({@code docs/ir.md} §3.1.1) — which is also what keeps a label
 * called {@code word} or {@code es} from being read as one of the assembler's own
 * words by whoever reads this text next. Printing something parsed and parsing
 * it again gives the same module ({@code AGENTS.md}, invariant 5), and printing
 * the same module twice gives the same text (invariant 6).
 *
 * <p>This is shared by the IR printer, which writes inline blocks inside an IR
 * module, and the assembly emitter, which writes them as the program. Those two
 * want different spellings of two things, which is what {@link Dialect} is for: the
 * IR printer writes our own dialect, and the emitter writes the one NASM reads.
 *
 * <p>A prefix is written in front of the mnemonic and is the same in both dialects,
 * because the machine spells it one way and both dialects read it ({@link Prefix}).
 */
public final class InstructionPrinter {

    private InstructionPrinter() {
    }

    /**
     * One instruction: the dialect says which of the two spellings it gets, and the
     * target says which of its names are registers rather than the author's.
     *
     * <p>A {@code null} target means the text is a dump of a derived form rather
     * than the surface: the names in it are the compiler's own, so they are written
     * plainly. The module printer always has one.
     */
    public static String print(Instruction instruction, Dialect dialect, Target target) {
        if (instruction.isLabel()) {
            return name(instruction.mnemonic()) + ":";
        }
        StringBuilder text = new StringBuilder();
        if (instruction.prefix() != null) {
            text.append(instruction.prefix().spelling()).append(' ');
        }
        text.append(instruction.mnemonic());
        // A branch's operand is somewhere to go rather than a register, and asking the
        // target which mnemonics go somewhere is what says so — the same question it is
        // already asked everywhere else. It matters because a name spelled like a
        // register cannot be told from one otherwise, and the surface lets a label be
        // called 'ax' (docs/ir.md §3.1).
        boolean place = target != null && target.isBranch(instruction.mnemonic());
        List<Operand> operands = instruction.operands();
        for (int i = 0; i < operands.size(); i++) {
            text.append(i == 0 ? " " : ", ");
            text.append(print(operands.get(i), dialect, target, place));
        }
        return text.toString();
    }

    /**
     * One operand.
     *
     * <p>{@code place} is true of the operand of a branch and of nothing else: it says
     * the operand is somewhere to go rather than a register, so it is the author's name
     * whatever it is spelled like.
     */
    public static String print(Operand operand, Dialect dialect, Target target, boolean place) {
        if (operand instanceof Operand.Name) {
            String name = ((Operand.Name) operand).name();
            return place ? name(name) : name(name, target);
        }
        if (operand instanceof Operand.Virtual) {
            // Not input, so not a diagnostic: the compiler printed before it
            // decided where this value lives. Loud, because the alternative is
            // assembly naming a register the machine has never heard of.
            throw new IllegalStateException(
                    "register allocation has not run: '" + ((Operand.Virtual) operand).name()
                            + "' is still a virtual register");
        }
        if (operand instanceof Operand.LowByte) {
            // The same, for the one operand that has not been told which half to read yet.
            throw new IllegalStateException(
                    "register allocation has not run: '" + ((Operand.LowByte) operand).name()
                            + "' is still waiting for the half to read");
        }
        if (operand instanceof Operand.Number) {
            return Numbers.spelling(((Operand.Number) operand).value());
        }
        if (operand instanceof Operand.Offset) {
            // NASM has no 'offset': a bare symbol in an operand is already the
            // address there, where a bracketed one is what it points at. An address is
            // a label and never a register, so the name is the author's.
            String label = name(((Operand.Offset) operand).name());
            return dialect == Dialect.NASM ? label : "offset " + label;
        }
        if (operand instanceof Operand.Far) {
            // The same spelling in both dialects: a far pointer is two numbers and a
            // colon, and the colon is what makes it far.
            Operand.Far far = (Operand.Far) operand;
            return Numbers.spelling(far.segment()) + ":" + Numbers.spelling(far.offset());
        }
        return printMemory((Operand.Memory) operand, dialect, target);
    }

    private static String printMemory(Operand.Memory memory, Dialect dialect, Target target) {
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
            String value = atom.isNumber() ? Numbers.spelling(atom.number())
                    : name(atom.name(), target);
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

    /**
     * A name as this text writes it.
     *
     * <p>Two rules, because the assembly language has registers and the surface does
     * not: a name in a position that belongs to the author is marked ({@link
     * #name(String)}), and a name that could be either is marked unless it is spelled
     * like one of the target's registers ({@link #name(String, Target)}). The
     * difference is the whole of what {@code docs/asm.md} §3 leaves open, and it is
     * why the caller has to say which kind of position it is printing.
     */
    private static String name(String name) {
        return Vocabulary.markedWhenPrinted(name) ? "$" + name : name;
    }

    private static String name(String name, Target target) {
        if (target == null) {
            return name;
        }
        return Vocabulary.markedWhenPrinted(name, target) ? "$" + name : name;
    }
}
