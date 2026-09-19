package i8086.ir;

import i8086.CompileError;
import i8086.SourcePos;
import i8086.asm.Size;
import i8086.target.Target;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Checks that a parsed module means something, before anything acts on it.
 *
 * <p>Two kinds of question are asked here, and they are different questions:
 *
 * <ul>
 *   <li><b>What is a name?</b> A name is a variable, a label, or nothing.
 *       A variable is a virtual register; a label is an address. Nothing in the
 *       syntax says which, so this is where it is decided, once, for every use.
 *   <li><b>How wide is it?</b> The two sides of an assignment must have the same
 *       width, and a width has to be knowable: {@code [p] = [q]} states none, so
 *       it is refused rather than guessed ({@code docs/ir.md} §3.4, §3.5).
 * </ul>
 *
 * <p>And it checks the flags ({@code docs/ir.md} §4.3), as far as anything can
 * yet: a branch that reads a flag value nothing has defined is refused, because
 * reading an undefined flag must be a hard error and not a jump in an arbitrary
 * direction. Two rules make that decidable without a control flow graph, and
 * both are conservative on purpose:
 *
 * <ul>
 *   <li>Only what the surface can do so far defines the flags — {@code cmp} and
 *       {@code test} — plus an inline block that does not say it clobbers them.
 *   <li>A label clears them, because another path may arrive there, and nothing
 *       here knows which one did.
 * </ul>
 *
 * <p>The one thing still not checked anywhere is the other half of §9: a block
 * has to declare the flags it <em>reads</em>, and the surface has no syntax for
 * that yet, so a block that reads a flag it does not declare cannot be caught.
 *
 * <p>Every message names the position it was found at ({@code AGENTS.md},
 * invariant 7), and the target is passed in rather than looked up so that this
 * class never names a machine.
 */
public final class IrVerifier {

    /** The flag set: the one name a module uses without declaring it (§4.1). */
    private static final String FLAGS = "flags";

    /** The width of a label used as a value: a near pointer, so two bytes (§3.3). */
    private static final int POINTER_BYTES = 2;

    private final Module module;
    private final Target target;
    private final Map<String, Type> variables = new LinkedHashMap<String, Type>();
    private final Set<String> labels = new LinkedHashSet<String>();

    private IrVerifier(Module module, Target target) {
        this.module = module;
        this.target = target;
    }

    public static void verify(Module module, Target target) {
        new IrVerifier(module, target).run();
    }

    private void run() {
        collectNames();
        checkEntry();
        checkItems();
    }

    // --- names -------------------------------------------------------------

    private void collectNames() {
        for (Item item : module.items()) {
            if (item instanceof Item.Label) {
                declareLabel(((Item.Label) item).name(), item.position());
            } else if (item instanceof Item.Data) {
                String label = ((Item.Data) item).label();
                if (label != null) {
                    declareLabel(label, item.position());
                }
            } else if (item instanceof Item.Var) {
                declareVariable((Item.Var) item);
            }
        }
    }

    private void declareLabel(String name, SourcePos where) {
        require(!labels.contains(name), where, "the label '" + name + "' is already defined");
        require(!variables.containsKey(name), where,
                "'" + name + "' is already a variable, so it cannot also be a label");
        labels.add(name);
    }

    private void declareVariable(Item.Var variable) {
        require(!variable.name().equals(FLAGS), variable.position(),
                "'" + FLAGS + "' is the flag set, which every module already has, so it cannot "
                        + "be declared");
        require(!variables.containsKey(variable.name()), variable.position(),
                "the variable '" + variable.name() + "' is already declared");
        require(!labels.contains(variable.name()), variable.position(),
                "'" + variable.name() + "' is already a label, so it cannot also be a variable");
        variables.put(variable.name(), variable.type());
    }

    private void checkEntry() {
        require(labels.contains(module.entry()), module.entryPosition(),
                "the entry label '" + module.entry() + "' is never defined");
    }

    // --- items -------------------------------------------------------------

    private void checkItems() {
        boolean flagsDefined = false;
        for (Item item : module.items()) {
            if (item instanceof Item.Assign) {
                // A move does not touch the flags, so they survive it (§4.2).
                checkAssign((Item.Assign) item);
            } else if (item instanceof Item.Compare) {
                checkCompare((Item.Compare) item);
                flagsDefined = true;
            } else if (item instanceof Item.InlineAsm) {
                checkInlineAsm((Item.InlineAsm) item);
                if (((Item.InlineAsm) item).clobbers().contains(FLAGS)) {
                    flagsDefined = false;
                }
            } else if (item instanceof Item.Jump) {
                checkLabelTarget(((Item.Jump) item).target(), item.position());
            } else if (item instanceof Item.Branch) {
                Item.Branch branch = (Item.Branch) item;
                checkLabelTarget(branch.target(), item.position());
                require(flagsDefined, branch.position(),
                        "this branch reads the flags, but nothing on the way here defines them; "
                                + "only 'cmp' and 'test' do so far, and a label clears them, "
                                + "because another path may arrive there (docs/ir.md §4.3)");
            }
            if (namesSomething(item)) {
                flagsDefined = false;
            }
        }
    }

    /** Whether an item leads with a name, and so can be branched to. */
    private static boolean namesSomething(Item item) {
        return item instanceof Item.Label
                || (item instanceof Item.Data && ((Item.Data) item).label() != null);
    }

    private void checkCompare(Item.Compare compare) {
        checkValue(compare.left());
        checkValue(compare.right());
        Integer left = widthOf(compare.left());
        Integer right = widthOf(compare.right());
        if (left != null && right != null) {
            require(left.equals(right), compare.right().position(),
                    "a " + left + "-byte value cannot be compared with a " + right
                            + "-byte one; both sides of a comparison have one width");
        }
    }

    private void checkLabelTarget(String target, SourcePos where) {
        if (labels.contains(target)) {
            return;
        }
        require(!variables.containsKey(target), where,
                "'" + target + "' is a variable, so it names no place to branch to");
        throw new CompileError(where,
                "the branch target '" + target + "' is never defined as a label");
    }

    /** Checks a name that is read, whether it is a variable or a label. */
    private void checkValue(Value value) {
        widthOf(value);
    }

    private void checkAssign(Item.Assign assign) {
        Integer placeBytes = widthOf(assign.place());
        Integer valueBytes = widthOf(assign.value());

        if (placeBytes != null && valueBytes != null) {
            require(placeBytes.equals(valueBytes), assign.value().position(),
                    "a " + placeBytes + "-byte place cannot take a " + valueBytes
                            + "-byte value; the two sides must have the same width, and a change "
                            + "of width is written as a conversion (docs/ir.md §3.5)");
        }

        Integer width = placeBytes != null ? placeBytes : valueBytes;
        if (width == null) {
            throw new CompileError(assign.position(),
                    "cannot tell how wide this is; give the memory operand a 'byte', 'word' or "
                            + "'dword' prefix");
        }
        if (assign.value() instanceof Value.Number) {
            Value.Number literal = (Value.Number) assign.value();
            require(fits(width.intValue(), literal.value()), literal.position(),
                    "the value " + literal.spelling() + " does not fit in "
                            + width + " byte(s)");
        }
    }

    private void checkInlineAsm(Item.InlineAsm block) {
        for (String clobber : block.clobbers()) {
            require(target.isRegister(clobber) || clobber.equals(FLAGS), block.position(),
                    "'" + clobber + "' is neither a register nor '" + FLAGS + "'");
        }
    }

    // --- widths ------------------------------------------------------------

    /** How many bytes a place occupies, or null when the text does not say. */
    private Integer widthOf(Place place) {
        if (place instanceof Place.Name) {
            Place.Name named = (Place.Name) place;
            return Integer.valueOf(variable(named.name(), named.position()).bytes());
        }
        MemoryOperand operand = ((Place.Memory) place).operand();
        checkAddress(operand);
        return operand.size() == null ? null : Integer.valueOf(operand.size().bytes());
    }

    /** How many bytes a value occupies, or null when the text does not say. */
    private Integer widthOf(Value value) {
        if (value instanceof Value.Number) {
            return null; // a literal takes its width from wherever it goes
        }
        if (value instanceof Value.Name) {
            Value.Name named = (Value.Name) value;
            if (variables.containsKey(named.name())) {
                return Integer.valueOf(variables.get(named.name()).bytes());
            }
            require(labels.contains(named.name()), named.position(),
                    "unknown name '" + named.name() + "': no variable or label has that name");
            return Integer.valueOf(POINTER_BYTES);
        }
        MemoryOperand operand = ((Value.Memory) value).operand();
        checkAddress(operand);
        return operand.size() == null ? null : Integer.valueOf(operand.size().bytes());
    }

    private void checkAddress(MemoryOperand operand) {
        String segment = operand.segment();
        if (segment != null) {
            require(target.isSegmentRegister(segment), operand.position(),
                    "'" + segment + "' is not a segment register on this target");
        }
        String base = operand.base();
        if (base == null || variables.containsKey(base) || labels.contains(base)) {
            return;
        }
        throw new CompileError(operand.position(),
                "unknown name '" + base + "': no variable or label has that name");
    }

    /** The type of a variable, complaining usefully when the name is something else. */
    private Type variable(String name, SourcePos where) {
        Type type = variables.get(name);
        if (type != null) {
            return type;
        }
        if (labels.contains(name)) {
            throw new CompileError(where,
                    "'" + name + "' is a label, and a label is an address, so it cannot be "
                            + "assigned to");
        }
        throw new CompileError(where,
                "unknown name '" + name + "': no variable or label has that name");
    }

    private static boolean fits(int bytes, long value) {
        return value >= 0 && value < (1L << (bytes * 8));
    }

    private void require(boolean condition, SourcePos where, String message) {
        if (!condition) {
            throw new CompileError(where, message);
        }
    }
}
