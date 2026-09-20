package i8086.ssa;

import i8086.ir.Item;
import i8086.ir.IrPrinter;
import i8086.ir.Names;

import java.util.List;

/**
 * Writes an SSA form out for a person to read.
 *
 * <p>This is a dump and not a surface: what it prints cannot be parsed back,
 * because a version name is spelled with a character no identifier may contain,
 * and because a φ is a construct the IR surface does not have. That is the point
 * of printing it at all — a form that can only be inspected through the code that
 * built it is a form nobody can check, and this project's first rule is that SSA
 * is checked rather than assumed ({@code AGENTS.md}, invariant 1).
 *
 * <p>What each line says, and why:
 *
 * <ul>
 *   <li>the block's number, the label it starts at, and which blocks arrive, so
 *       that a φ's operands can be read against them;
 *   <li>{@code x#3 = 0}: the version a statement defines is on its left, because
 *       the place an item writes is renamed like everything else;
 *   <li>{@code flags#4 = cmp x#3, n#2}: a statement whose only result is the
 *       flags says so, since the surface never writes the flags down and this is
 *       where they become visible ({@code docs/ir.md} §4.1). A statement that also
 *       defines a value does not repeat itself: the flags it leaves are the ones a
 *       later branch reads, and there is only ever one of those at a time.
 * </ul>
 */
public final class SsaPrinter {

    private static final String INDENT = "    ";

    private SsaPrinter() {
    }

    public static String print(SsaForm form) {
        StringBuilder out = new StringBuilder();
        out.append("; SSA form of target ").append(form.module().target())
                .append(", entry ").append(form.module().entry()).append('\n');
        for (Block block : form.cfg().blocks()) {
            out.append('\n');
            printHeader(out, form, block);
            for (Phi phi : form.phis(block)) {
                printPhi(out, block, phi);
            }
            for (SsaStatement statement : form.statements(block)) {
                printStatement(out, statement);
            }
        }
        return out.toString();
    }

    private static void printHeader(StringBuilder out, SsaForm form, Block block) {
        out.append(block);
        String label = block.label();
        if (label != null) {
            out.append(" (").append(label).append(')');
        }
        List<Block> predecessors = block.predecessors();
        if (!predecessors.isEmpty()) {
            out.append(" <-");
            for (Block predecessor : predecessors) {
                out.append(' ').append(predecessor);
            }
        }
        if (!form.cfg().isReachable(block)) {
            out.append("   ; nothing reaches this");
        }
        out.append(":\n");
    }

    private static void printPhi(StringBuilder out, Block block, Phi phi) {
        out.append(INDENT).append(phi.name()).append(" = phi(");
        for (int i = 0; i < phi.operands().size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(block.predecessors().get(i)).append(": ").append(phi.operand(i));
        }
        out.append(")\n");
    }

    private static void printStatement(StringBuilder out, SsaStatement statement) {
        if (statement.item() instanceof Item.Label) {
            // The label is the block's, and the header already says it.
            return;
        }
        String text = IrPrinter.print(statement.item());
        if (!statement.definedFlags().isEmpty() && writesNothing(statement.item())) {
            out.append(INDENT).append(versions(statement)).append(" = ")
                    .append(body(text));
        } else {
            out.append(text);
        }
    }

    /**
     * The flag versions a statement defines, in the order the flags are asked in.
     *
     * <p>One version is the common case and reads as it always has, {@code flags#4 = cmp i#3, n#2}.
     * Two are two names on the line, which is what a statement that restores every flag at once
     * gets ({@code docs/ssa.md} §4).
     */
    private static String versions(SsaStatement statement) {
        StringBuilder out = new StringBuilder();
        for (String flag : Names.flagNames()) {
            String version = statement.definedFlag(flag);
            if (version != null) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(version);
            }
        }
        return out.toString();
    }

    /** Whether an item defines no value, so that its only result is the flags. */
    private static boolean writesNothing(Item item) {
        return Effects.writtenVariable(item) == null;
    }

    /** The item's text without the indent the surface printer gives it. */
    private static String body(String text) {
        return text.startsWith(INDENT) ? text.substring(INDENT.length()) : text;
    }
}
