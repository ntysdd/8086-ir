package i8086.ir;

import i8086.CompileError;
import i8086.SourcePos;
import i8086.Warnings;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.asm.Size;
import i8086.target.Target;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    /** The width of a label used as a value: a near pointer, so two bytes (§3.3). */
    private static final int POINTER_BYTES = 2;

    /** The width of the machine's segmentation state: one word (§8.1). */
    private static final int SEGMENT_BYTES = 2;

    private final Module module;
    private final Target target;
    private final Names names;

    /** Every label named inside a block, so that two of them cannot collide. */
    private final Set<String> blockLabels = new LinkedHashSet<String>();

    /** The item each label names, so that a home can be asked what bytes it holds. */
    private final Map<String, Item> labelled;

    /** Where anything this module is worth saying but is not refused for is recorded. */
    private final Warnings warnings;

    private IrVerifier(Module module, Target target, Warnings warnings) {
        this.module = module;
        this.target = target;
        this.warnings = warnings;
        this.names = Names.of(module);
        this.labelled = labelledItems(module);
    }

    /**
     * The item each label names, in module order.
     *
     * <p>Collected for the whole module before anything is checked, because a home and
     * the bytes it names may be declared in either order: the variable may be declared
     * before the data it lives in ({@code docs/ir.md} §3.1.2, §10.2).
     */
    private static Map<String, Item> labelledItems(Module module) {
        Map<String, Item> labelled = new LinkedHashMap<String, Item>();
        for (Item item : module.items()) {
            if (Item.labelOf(item) != null) {
                labelled.put(Item.labelOf(item), item);
            }
        }
        return labelled;
    }

    /**
     * Checks a module with nobody listening, for a caller that is not compiling it for an
     * author: a test, or the verification of a form that was already reported on.
     */
    public static void verify(Module module, Target target) {
        verify(module, target, new Warnings());
    }

    /**
     * Checks a module, recording whatever is worth saying about it but not worth refusing
     * it for ({@code docs/ir.md} §3.1.2).
     *
     * <p>Errors throw, so a module that is refused leaves the warnings it had found
     * unread. That is the right way round: until the program compiles there is nothing
     * useful to say about what it would have done.
     */
    public static void verify(Module module, Target target, Warnings warnings) {
        new IrVerifier(module, target, warnings).run();
    }

    private void run() {
        checkEntry();
        checkItems();
        checkHomes();
    }

    // --- names -------------------------------------------------------------

    private void checkEntry() {
        require(names.isLabel(module.entry()), module.entryPosition(),
                "the entry label '" + module.entry() + "' is never defined");
    }

    // --- items -------------------------------------------------------------

    private void checkItems() {
        boolean flagsDefined = false;
        for (Item item : module.items()) {
            flagsDefined = checkItem(item, flagsDefined);
            if (namesSomething(item)) {
                flagsDefined = false;
            }
        }
    }

    /**
     * Checks one item, and answers with whether the flags are defined after it.
     *
     * <p>Which is the whole of the flags rule for now: what defines them, what
     * throws them away, and what leaves them alone. A move leaves them alone
     * (§4.2), {@code eval} defines them, {@code expr} gives them up (§5.2), and a
     * conversion is assumed to disturb them (§3.5).
     */
    private boolean checkItem(Item item, boolean flagsDefined) {
        if (item instanceof Item.Assign) {
            Item.Assign assign = (Item.Assign) item;
            checkAssign(assign);
            return flagsAfterValue(assign.value(), flagsDefined);
        }
        if (item instanceof Item.Eval) {
            Operation operation = ((Item.Eval) item).operation();
            widthOfOperation(operation, null);
            requireCarryIsDefined(operation.readsFlags(), flagsDefined, item.position());
            return true;
        }
        if (item instanceof Item.Compare) {
            Item.Compare compare = (Item.Compare) item;
            checkCompare(compare);
            requireCarryIsDefined(
                    expressionReadsFlagsOf(compare.left()) || expressionReadsFlagsOf(compare.right()),
                    flagsDefined, item.position());
            return true;
        }
        if (item instanceof Item.InlineAsm) {
            Item.InlineAsm block = (Item.InlineAsm) item;
            checkInlineAsm(block);
            return block.clobbers().contains(Names.FLAGS) ? false : flagsDefined;
        }
        if (item instanceof Item.Pad) {
            checkPad(item);
            return flagsDefined;
        }
        if (item instanceof Item.MovReg) {
            checkMovReg((Item.MovReg) item);
            return flagsDefined;
        }
        if (item instanceof Item.FarJump) {
            Item.FarJump far = (Item.FarJump) item;
            require(far.segment() <= 0xFFFF, item.position(),
                    "a segment is one word wide, so it reaches 0xFFFF at most");
            require(far.offset() <= 0xFFFF, item.position(),
                    "an offset is one word wide, so it reaches 0xFFFF at most");
            // Nothing after this runs on the way out of the image, and the scan goes on
            // to the next item regardless.
            return false;
        }
        if (item instanceof Item.Machine) {
            Item.Machine machine = (Item.Machine) item;
            require(target.machineStatements().containsKey(machine.mnemonic()),
                    item.position(),
                    "'" + machine.mnemonic() + "' is not a statement this target provides");
            for (String destroyed : machine.clobbers()) {
                require(target.isRegister(destroyed) || destroyed.equals(Names.FLAGS),
                        item.position(),
                        "'" + destroyed + "' is neither a register nor '" + Names.FLAGS + "'");
            }
            // The flags are the one thing a machine statement may leave standing: 'cli'
            // does not touch the arithmetic flags, and a comparison may be read after it.
            return !machine.clobbers().contains(Names.FLAGS);
        }
        if (item instanceof Item.Data) {
            checkData((Item.Data) item);
            return flagsDefined;
        }
        if (item instanceof Item.Jump) {
            checkLabelTarget(((Item.Jump) item).target(), item.position());
            return flagsDefined;
        }
        if (item instanceof Item.Branch) {
            Item.Branch branch = (Item.Branch) item;
            checkLabelTarget(branch.target(), item.position());
            require(flagsDefined, branch.position(),
                    "this branch reads the flags, but nothing on the way here defines them; "
                            + "only 'cmp' and 'test' do so far, and a label clears them, "
                            + "because another path may arrive there (docs/ir.md §4.3)");
        }
        return flagsDefined;
    }

    /** Whether an operation needs the flags to be defined before it runs. */
    private void requireCarryIsDefined(boolean readsFlags, boolean flagsDefined, SourcePos where) {
        require(!readsFlags || flagsDefined, where,
                "this operation reads the carry flag, so something has to define the flags "
                        + "before it; only 'cmp' and 'test' do so far (docs/ir.md §4.3)");
    }

    /** Whether the flags are defined after a value has been computed. */
    private boolean flagsAfterValue(Value value, boolean flagsDefined) {
        if (value instanceof Value.Eval) {
            Operation operation = ((Value.Eval) value).operation();
            requireCarryIsDefined(operation.readsFlags(), flagsDefined, value.position());
            return true;
        }
        if (value instanceof Value.Expr) {
            return false;
        }
        if (value instanceof Value.Convert) {
            // Assume the worst: on this machine widening is an instruction that
            // touches the flags. Which it is, is the target's to say (§4.2), and
            // until it does, the safe answer is the one that refuses more.
            return false;
        }
        return flagsDefined;
    }

    private static boolean expressionReadsFlagsOf(Value value) {
        if (value instanceof Value.Eval) {
            return ((Value.Eval) value).operation().readsFlags();
        }
        return false;
    }

    private static boolean expressionReadsFlags(Expression expression) {
        if (expression instanceof Expression.Apply) {
            Expression.Apply apply = (Expression.Apply) expression;
            return apply.operator().readsFlags()
                    || expressionReadsFlags(apply.left())
                    || expressionReadsFlags(apply.right());
        }
        if (expression instanceof Expression.Unary) {
            return expressionReadsFlags(((Expression.Unary) expression).operand());
        }
        return expressionReadsFlagsOf(((Expression.Leaf) expression).value());
    }

    /**
     * A label in a data list is its address, and an address has to be a label's.
     *
     * <p>Which is a question about the module and not about the text: a label may be
     * defined later than the data that names it, so this is checked here rather than
     * while reading ({@code docs/ir.md} §10.2). The width is the parser's business —
     * a name is one word, so it belongs in a {@code dw} list.
     */
    private void checkData(Item.Data data) {
        for (Item.Data.Atom atom : data.atoms()) {
            if (!atom.isName()) {
                continue;
            }
            require(!names.isVariable(atom.name()), data.position(),
                    "'" + atom.name() + "' is a variable, and a variable is a register rather"
                            + " than an address; a data item can name a label");
            require(names.isLabel(atom.name()), data.position(),
                    "'" + atom.name() + "' is not a label, and a data item's value is an address"
                            + " (docs/ir.md §10.2)");
        }
    }

    private Item checkPad(Item item) {
        Item.Pad pad = (Item.Pad) item;
        require(pad.amount() >= 0, pad.position(),
                "a piece of padding cannot be a negative number of bytes");
        require(pad.amount() <= 0xFFFF, pad.position(),
                "the image is one segment, so nothing in it reaches past 0xFFFF");
        require(pad.fill() >= 0 && pad.fill() <= 0xFF, pad.position(),
                "the fill of a piece of padding is one byte: '" + pad.fill() + "' does not fit"
                        + " (docs/ir.md §10.2)");
        return item;
    }

    /** Whether an item leads with a name, and so can be branched to. */
    private static boolean namesSomething(Item item) {
        return Item.labelOf(item) != null;
    }

    // --- homes (§3.1.2) ----------------------------------------------------

    /**
     * What §3.1.2 requires of a home: it names bytes, they are wide enough for the
     * variable, and a cell one variable keeps current belongs to that variable alone.
     *
     * <p>All three are static questions, which is why they are asked here and not while
     * reading: the item a home names may be declared after the code that names it, so the
     * answer is about the module rather than about the text ({@code docs/ir.md} §10.2).
     * Whether a home is <em>used</em> is not a static question at all — it is the
     * allocator's, and it may find that the value fits in a register and never touch
     * those bytes.
     *
     * <p>Two variables naming one cell is not itself refused: a scratch cell is exactly
     * that, and the allocator hands it out the way it hands out a register. The refusal is
     * for the mode that tells a reader what the bytes hold.
     */
    private void checkHomes() {
        Map<String, List<Item.Var>> cells = new LinkedHashMap<String, List<Item.Var>>();
        for (Item item : module.items()) {
            if (!(item instanceof Item.Var)) {
                continue;
            }
            Item.Var variable = (Item.Var) item;
            if (variable.home() != null) {
                requireHomeNamesBytes(variable);
                requireExclusiveHome(cells, variable);
            }
        }
        warnAboutSavesIntoSharedCells(cells);
        requireWritethroughIsBuilt();
    }

    /**
     * The home names bytes, and they are at least as wide as the variable.
     *
     * <p>Each way of not naming bytes is refused for its own reason, because they are
     * different mistakes: a code label is a place and not storage, a {@code pad to} has a
     * length only the assembler knows ({@code docs/ir.md} §10.3), and a narrow home is a
     * program that would read the byte after it.
     */
    private void requireHomeNamesBytes(Item.Var variable) {
        String home = variable.home();
        Item bytes = labelled.get(home);
        if (bytes == null) {
            require(!names.isVariable(home), variable.position(),
                    "'" + home + "' is a variable, so it names a register rather than bytes; a "
                            + "home is bytes in the image (docs/ir.md §3.1.2)");
            throw new CompileError(variable.position(),
                    "the home '" + home + "' names nothing: no item in this module puts bytes "
                            + "there (docs/ir.md §3.1.2)");
        }
        if (bytes instanceof Item.Label) {
            throw new CompileError(variable.position(),
                    "the home '" + home + "' names a place in the code rather than bytes; a home "
                            + "is data, so it is a 'db'/'dw'/'dd' list or a 'pad' "
                            + "(docs/ir.md §3.1.2)");
        }
        if (bytes instanceof Item.Pad && !((Item.Pad) bytes).isResolvable()) {
            throw new CompileError(variable.position(),
                    "the home '" + home + "' is a 'pad to', whose length only the assembler "
                            + "knows, so the front end cannot tell whether it is wide enough "
                            + "(docs/ir.md §3.1.2, §10.3)");
        }
        long available = bytesOf(bytes);
        require(available >= variable.type().bytes(), variable.position(),
                "the home '" + home + "' is " + available + " byte(s) and '" + variable.name()
                        + "' is " + variable.type().bytes() + "; a value does not fit in less "
                        + "room than its width (docs/ir.md §3.1.2)");
    }

    /**
     * How many bytes of the image a named item holds. The kinds that are not bytes have
     * been refused by the caller: a label is a place, and a {@code pad to} names no length
     * the front end knows.
     */
    private static long bytesOf(Item item) {
        if (item instanceof Item.Pad) {
            return ((Item.Pad) item).amount();
        }
        return ((Item.Data) item).byteCount();
    }

    /**
     * A cell one variable keeps current is that variable's alone.
     *
     * <p>It is the one sharing rule the allocator does not get to decide: two variables
     * may take turns in a cell, but not when one of them has told a reader outside the
     * module that the cell <em>is</em> its current value. A reader could not then tell
     * whose value it was looking at, and one variable's write would break the other's
     * promise.
     *
     * <p>The refusal lands on whichever declaration comes second and names the first, so
     * that the two lines can be read together. Refusing the second one either way is what
     * keeps the rule independent of the order the declarations are written in.
     */
    private void requireExclusiveHome(Map<String, List<Item.Var>> cells, Item.Var variable) {
        List<Item.Var> declared = cells.get(variable.home());
        if (declared == null) {
            declared = new ArrayList<Item.Var>();
            cells.put(variable.home(), declared);
        } else {
            for (Item.Var other : declared) {
                require(!other.writethrough() && !variable.writethrough(), variable.position(),
                        "the bytes '" + variable.home() + "' are the home of '" + other.name()
                                + "' as well, and one of the two is kept current: a 'writethrough' "
                                + "home belongs to the variable that asked for it, because a reader "
                                + "of those bytes has to know whose value it is looking at "
                                + "(docs/ir.md §3.1.2)");
            }
        }
        declared.add(variable);
    }

    /**
     * The warning §3.1.2 asks for: a store into bytes that more than one variable calls its
     * home is not promised to stay there.
     *
     * <p>The store itself happens — nothing removes it, duplicates it or moves anything
     * across it — so this is not a refusal. What is missing is the promise that the bytes
     * still hold what the program wrote afterwards, because the allocator may put one of
     * those variables' values into the same cell. That is the case an author cannot see
     * coming: the other declaration is somewhere else in the file, and one more
     * simultaneously live value is the kind of change that puts its value there instead.
     *
     * <p>It is said at every store into such a cell, and not once per cell, because the
     * warning points at the line that has to be read.
     */
    private void warnAboutSavesIntoSharedCells(Map<String, List<Item.Var>> cells) {
        for (Item item : module.items()) {
            if (!(item instanceof Item.Assign)) {
                continue;
            }
            Place place = ((Item.Assign) item).place();
            if (!(place instanceof Place.Memory)) {
                continue;
            }
            String cell = ((Place.Memory) place).operand().addressedLabel();
            List<Item.Var> declared = cell == null ? null : cells.get(cell);
            if (declared != null && declared.size() > 1) {
                warnings.add(item.position(),
                        "'" + cell + "' is the home of " + spelled(declared) + ", so what this "
                                + "store leaves there is not promised to stay: the allocator may "
                                + "put the value of one of them there instead. Save into a cell no "
                                + "variable declares, or let the variable that wants those bytes "
                                + "keep them with 'writethrough' (docs/ir.md §3.1.2)");
            }
        }
    }

    /** The variables that declared a cell, in declaration order, as the warning names them. */
    private static String spelled(List<Item.Var> variables) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < variables.size(); i++) {
            if (i > 0) {
                names.append(i == variables.size() - 1 ? " and " : ", ");
            }
            names.append('\'').append(variables.get(i).name()).append('\'');
        }
        return names.toString();
    }

    /**
     * The one thing §3.1.2 asks for that is not built yet, and it is refused rather than
     * quietly ignored.
     *
     * <p>A home in the default mode costs nothing while the allocator does not use it, so
     * accepting one is honest: the program is compiled exactly as it was before homes
     * existed, and a value the registers cannot hold is still refused instead of being put
     * somewhere the compiler chose. {@code writethrough} is not like that, because it
     * promises a reader outside the module that every definition writes the cell — and
     * compiling it as if the promise had never been made is a silent wrong answer, which
     * is the one thing this compiler does not produce ({@code AGENTS.md}, invariant 7).
     * The refusal goes away when the allocator honours a home.
     */
    private void requireWritethroughIsBuilt() {
        for (Item item : module.items()) {
            if (item instanceof Item.Var && ((Item.Var) item).writethrough()) {
                throw new CompileError(item.position(),
                        "not implemented yet: 'writethrough' writes the home on every definition, "
                                + "and nothing honours a home yet, so this program would compile "
                                + "as if those bytes were never written (docs/ir.md §3.1.2)");
            }
        }
    }

    /**
     * {@code movreg ds, 0}: the state written, and what is put into it ({@code docs/ir.md} §8.1).
     *
     * <p>Two kinds of source, and they are checked differently. A segment register is a name the
     * machine has and the parser has already decided means that, so the check here is that the
     * target really has it — a hand-built module could say anything. A value is a value, and it has
     * to be the width of the register it goes into: this machine's segmentation state is sixteen
     * bits, so a byte or a double word does not fit.
     */
    private void checkMovReg(Item.MovReg movreg) {
        if (movreg.source() != null) {
            // Which names may be written, and which may be copied from, is a fixed fact about the
            // target and is refused where the statement is read; what is left here is the part that
            // depends on the whole module.
            return;
        }
        Value value = movreg.value();
        if (value instanceof Value.Name && !names.isVariable(((Value.Name) value).name())) {
            String name = ((Value.Name) value).name();
            require(!names.isLabel(name), value.position(),
                    "'" + name + "' is a label, which is an address; only a value or a segment "
                            + "register can go into segmentation state (docs/ir.md §8.1)");
        }
        Integer bytes = widthOf(value, Integer.valueOf(SEGMENT_BYTES));
        require(bytes == null || bytes.intValue() == SEGMENT_BYTES, value.position(),
                "a " + bytes + "-byte value does not fit in '" + movreg.name() + "', which is one "
                        + "word wide (docs/ir.md §8.1)");
    }

    private void checkCompare(Item.Compare compare) {
        Integer left = widthOf(compare.left(), null);
        Integer right = widthOf(compare.right(), left);
        if (left == null) {
            left = widthOf(compare.left(), right);
        }
        if (left == null) {
            throw new CompileError(compare.position(),
                    "cannot tell how wide this comparison is; a comparison of two literals has "
                            + "no width to take");
        }
        require(right == null || left.equals(right), compare.right().position(),
                "a " + left + "-byte value cannot be compared with a " + right
                        + "-byte one; both sides of a comparison have one width");
    }

    private void checkLabelTarget(String target, SourcePos where) {
        if (names.isLabel(target)) {
            return;
        }
        require(!names.isVariable(target), where,
                "'" + target + "' is a variable, so it names no place to branch to");
        throw new CompileError(where,
                "the branch target '" + target + "' is never defined as a label");
    }

    private void checkAssign(Item.Assign assign) {
        Integer placeBytes = widthOf(assign.place());
        Integer valueBytes = widthOf(assign.value(), placeBytes);
        if (placeBytes == null) {
            placeBytes = valueBytes;
        }
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
            require(target.isRegister(clobber) || clobber.equals(Names.FLAGS), block.position(),
                    "'" + clobber + "' is neither a register nor '" + Names.FLAGS + "'");
        }
        for (Instruction instruction : block.body()) {
            if (instruction.isLabel()) {
                String name = instruction.mnemonic();
                // Block-local in what it can be read by, but a name in the image all the
                // same: the emitted text is one flat assembly file, so two of anything
                // cannot share a name (docs/asm.md §3).
                require(!names.isVariable(name), instruction.position(),
                        "'" + name + "' is a variable, so it cannot label a place in a block");
                require(!names.isLabel(name), instruction.position(),
                        "'" + name + "' is already a label, and a label inside a block shares the "
                                + "image's names (docs/ir.md §9)");
                require(blockLabels.add(name), instruction.position(),
                        "'" + name + "' is already a label inside a block; block labels are local in "
                                + "what can read them, not in what they are called (docs/ir.md §9)");
                continue;
            }
            // A branch inside a block reaches the block's own labels and nothing else.
            //
            // Not for the block's sake — it is opaque either way — but for the graph: the
            // edges a block has are the ones the module can see, and a jump out of one is
            // an edge nothing here would draw. A value alive across that edge would look
            // dead on the other side of it. Writing the jump as a statement is the same
            // jump and an edge the module has (docs/ir.md §9).
            if (!target.isBranch(instruction.mnemonic())) {
                continue;
            }
            for (Operand operand : instruction.operands()) {
                if (operand instanceof Operand.Name) {
                    String name = ((Operand.Name) operand).name();
                    require(!names.isLabel(name), operand.position(),
                            "'" + name + "' is a label of the module, and a block's branches "
                                    + "reach the block's own labels: write the jump as a statement "
                                    + "after the block, where the graph has the edge "
                                    + "(docs/ir.md §9)");
                }
            }
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

    /**
     * How a value's width is worked out, and what the two forms promise.
     *
     * <p>The difference is the whole point of having two of them: {@code eval}
     * may touch memory and may read the flags, {@code expr} may do neither, and
     * that is what buys it the freedom to be reassociated and shared
     * ({@code docs/ir.md} §5).
     */
    private enum ExpressionForm {
        EVAL,
        EXPR
    }

    /**
     * The width of an expression, or null when nothing has said yet.
     *
     * <p>Every operand in one expression has the same width, so the first one
     * that states a width settles it for the rest: a literal and an anonymous
     * memory operand take that width, and a value that states a different one is
     * refused. This is where "no implicit promotion" is actually enforced
     * (§3.2), and where the two forms' rules about memory and flags are checked.
     */
    private Integer widthOfExpression(Expression expression, Integer implied, ExpressionForm form) {
        if (expression instanceof Expression.Leaf) {
            requireNoVolatile(((Expression.Leaf) expression).value());
            return widthOfLeaf(((Expression.Leaf) expression).value(), implied, form);
        }
        if (expression instanceof Expression.Unary) {
            return widthOfExpression(((Expression.Unary) expression).operand(), implied, form);
        }
        Expression.Apply apply = (Expression.Apply) expression;
        checkExpressionOperator(apply);
        Integer left = widthOfExpression(apply.left(), implied, form);
        Integer right = widthOfExpression(apply.right(), left == null ? implied : left, form);
        if (left == null) {
            left = widthOfExpression(apply.left(), right, form);
        }
        if (left != null && right != null) {
            require(left.equals(right), apply.right().position(),
                    "a " + left + "-byte operand cannot meet a " + right + "-byte one in one "
                            + "expression; every operand in an expression has the same width "
                            + "(docs/ir.md §3.2)");
        }
        return left != null ? left : right;
    }

    /**
     * What signedness an expression speaks for, or null when nothing says.
     *
     * <p>A variable's type says; a literal, a load and a label do not, because
     * bits are bits until an operation reads them a particular way (§3.2). A
     * mnemonic that states the signedness settles it for everything above it,
     * which is what the mnemonic forms are for (§5.5).
     */
    private Boolean signednessOf(Value value) {
        return Signedness.of(value, names::typeOf);
    }

    private Boolean signednessOf(Expression expression) {
        return Signedness.of(expression, names::typeOf);
    }

    /**
     * What an operator may do, wherever it appears: whether it reads the flags is
     * the caller's question, because that is what tells the two forms apart.
     */
    private void checkOperator(Operator operator, SourcePos where,
                               Boolean leftSigned, Boolean rightSigned) {
        if (!operator.statesSignedness()) {
            require(leftSigned == null || rightSigned == null || leftSigned.equals(rightSigned),
                    where,
                    "'" + operator.spelling() + "' has one operand signed and one unsigned, and "
                            + "written as a symbol it does not say which it means; use the "
                            + "mnemonic form, or let one value live in a variable of the other "
                            + "type, which costs nothing (docs/ir.md §5.5)");
        }
    }

    /**
     * What an operator may do in an expression, which is stricter: {@code expr}
     * reads no flags at all, so an operator that does belongs in {@code eval}.
     */
    private void checkExpressionOperator(Expression.Apply apply) {
        Operator operator = apply.operator();
        if (operator.readsFlags()) {
            require(false, apply.position(),
                    "'" + operator.spelling() + "' reads the carry flag, so it belongs in eval, "
                            + "which is one operation done as written, and not in expr, which "
                            + "reads no flags at all (docs/ir.md §5.5)");
        }
        checkOperator(operator, apply.position(), signednessOf(apply.left()),
                signednessOf(apply.right()));
        if (operator.divides()) {
            Integer width = widthOfExpression(apply.left(), null, ExpressionForm.EXPR);
            if (width == null) {
                width = widthOfExpression(apply.right(), null, ExpressionForm.EXPR);
            }
            require(width == null || width.intValue() == 2, apply.position(),
                    "division is 16 bits in v1, and this is " + width + " bytes; a wider division "
                            + "is written out by hand (docs/ir.md §6.2)");
        }
    }

    /** A leaf of an expression: what it may be, and how wide it is. */
    private Integer widthOfLeaf(Value value, Integer implied, ExpressionForm form) {
        if (form == ExpressionForm.EXPR && value instanceof Value.Memory) {
            throw new CompileError(value.position(),
                    "expr works on values that are already in registers, so it cannot contain a "
                            + "load; put the load in a variable first (docs/ir.md §5.2)");
        }
        if (form == ExpressionForm.EXPR && value instanceof Value.Name
                && !names.isVariable(((Value.Name) value).name())) {
            require(!names.isLabel(((Value.Name) value).name()), value.position(),
                    "expr works on variables, and '" + ((Value.Name) value).name()
                            + "' is a label, which is an address; put it in a variable first "
                            + "(docs/ir.md §5.2)");
        }
        return widthOf(value, implied);
    }

    /**
     * The width of a conversion, which is the width of whatever it is going into
     * unless the conversion names one of its own ({@code docs/ir.md} §3.5).
     */
    private Integer widthOfConversion(Value.Convert convert, Integer implied) {
        Conversion conversion = convert.conversion();
        Integer source = widthOf(convert.operand(), null);
        require(source != null, convert.operand().position(),
                "the value being converted has to say how wide it is; give the memory operand a "
                        + "prefix, or convert a variable");

        if (conversion.direction() == Conversion.Direction.WIDEN) {
            require(implied != null, convert.position(),
                    "'" + conversion.spelling() + "' widens, so something has to say how wide the "
                            + "result is: put it somewhere of the width you want");
            require(implied.intValue() > source.intValue(), convert.position(),
                    "'" + conversion.spelling() + "' widens, and turning a " + source
                            + "-byte value into a " + implied + "-byte one is not widening");
            return implied;
        }

        int result = conversion.resultBytes();
        if (implied != null) {
            require(result == implied.intValue(), convert.position(),
                    "'" + conversion.spelling() + "' gives a " + result + "-byte value, which "
                            + "does not fit where it is going; narrowing and then widening needs "
                            + "two conversions, and the surface writes them one at a time");
        }
        require(source.intValue() > result, convert.position(),
                "'" + conversion.spelling() + "' narrows, and the value is already " + source
                        + " bytes");
        return Integer.valueOf(result);
    }

    /**
     * How wide a value is, or null when nothing has said yet.
     *
     * <p>{@code implied} is the width the context asks for, which is what a
     * literal and an anonymous memory operand take: {@code x: u16 = [p]} is a
     * two-byte load because {@code x} is two bytes, not because anything else
     * said so ({@code docs/ir.md} §3.4).
     */
    private Integer widthOf(Value value, Integer implied) {
        if (value instanceof Value.Number) {
            return implied;
        }
        if (value instanceof Value.Name) {
            Value.Name named = (Value.Name) value;
            if (names.isVariable(named.name())) {
                return Integer.valueOf(names.typeOf(named.name()).bytes());
            }
            require(names.isLabel(named.name()), named.position(),
                    "unknown name '" + named.name() + "': no variable or label has that name");
            return Integer.valueOf(POINTER_BYTES);
        }
        if (value instanceof Value.Memory) {
            MemoryOperand operand = ((Value.Memory) value).operand();
            checkAddress(operand);
            return operand.size() == null ? implied : Integer.valueOf(operand.size().bytes());
        }
        if (value instanceof Value.Convert) {
            return widthOfConversion((Value.Convert) value, implied);
        }
        if (value instanceof Value.Eval) {
            return widthOfOperation(((Value.Eval) value).operation(), implied);
        }
        return widthOfExpression(((Value.Expr) value).expression(), implied, ExpressionForm.EXPR);
    }

    /**
     * The width of one operation, and the rules its operator answers to.
     *
     * <p>Because {@code eval} is one operation, there is no evaluation order to
     * work out and the width is simply the one width its operands share
     * ({@code docs/ir.md} §5.1).
     */
    private Integer widthOfOperation(Operation operation, Integer implied) {
        Operator operator = operation.operator();
        Integer width = null;
        for (Value operand : operation.operands()) {
            requireNoVolatile(operand);
            Integer own = widthOf(operand, width == null ? implied : width);
            if (width == null) {
                width = own;
            } else {
                require(own == null || width.equals(own), operand.position(),
                        "a " + width + "-byte operand cannot meet a " + own + "-byte one in one "
                                + "operation; every operand has the same width "
                                + "(docs/ir.md §3.2)");
            }
        }
        checkOperator(operator, operation.position(),
                signednessOf(operation.operands().get(0)),
                operator.arity() == 1 ? null : signednessOf(operation.operands().get(1)));
        if (operator.divides()) {
            require(width == null || width.intValue() == 2, operation.position(),
                    "division is 16 bits in v1, and this is " + width + " bytes; a wider division "
                            + "is written out by hand (docs/ir.md §6.2)");
        }
        return width;
    }

    /**
     * Refuses a volatile access written where an expression put it.
     *
     * <p>{@code docs/ir.md} §3.4 keeps volatile accesses out of {@code eval} and
     * {@code expr}: an expression is something the optimiser may reorder, share and
     * duplicate, and an access that must happen exactly once cannot be any of those
     * things. Keeping them apart is what makes "volatile is never removed,
     * duplicated or reordered" a rule about statements rather than a rule every
     * optimisation has to remember.
     *
     * <p>A comparison is not an expression in that sense — {@code cmp} is one
     * operation written on its own line — so a volatile load may be compared
     * against something. What it may not be is an operand of something the compiler
     * is free to take apart.
     */
    private void requireNoVolatile(Value value) {
        if (value instanceof Value.Memory && ((Value.Memory) value).operand().isVolatile()) {
            throw new CompileError(value.position(),
                    "a volatile access is a statement of its own: it must happen exactly once, "
                            + "and an expression is something the compiler may take apart "
                            + "(docs/ir.md §3.4)");
        }
    }

    private void checkAddress(MemoryOperand operand) {
        String segment = operand.segment();
        if (segment != null) {
            require(target.isSegmentRegister(segment), operand.position(),
                    "'" + segment + "' is not a segment register on this target");
        }
        String base = operand.base();
        if (base == null || names.isLabel(base)) {
            return;
        }
        Type based = names.typeOf(base);
        if (based == null) {
            throw new CompileError(operand.position(),
                    "unknown name '" + base + "': no variable or label has that name");
        }
        require(based.bytes() == POINTER_BYTES, operand.position(),
                "an address is a near pointer, so it is " + POINTER_BYTES + " bytes wide; '" + base
                        + "' is " + based.bytes() + " (docs/ir.md §3.3)");
    }

    /** The type of a variable, complaining usefully when the name is something else. */
    private Type variable(String name, SourcePos where) {
        Type type = names.typeOf(name);
        if (type != null) {
            return type;
        }
        if (names.isLabel(name)) {
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
