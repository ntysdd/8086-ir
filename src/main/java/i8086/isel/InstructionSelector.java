package i8086.isel;

import i8086.CompileError;
import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Numbers;
import i8086.asm.Operand;
import i8086.asm.Size;
import i8086.ir.Conversion;
import i8086.ir.Expression;
import i8086.ir.Item;
import i8086.ir.MemoryOperand;
import i8086.ir.Names;
import i8086.ir.Operation;
import i8086.ir.Operator;
import i8086.ir.Place;
import i8086.ir.Signedness;
import i8086.ir.Type;
import i8086.ir.Value;
import i8086.ssa.Block;
import i8086.ssa.Effects;
import i8086.ssa.Liveness;
import i8086.ssa.Phi;
import i8086.ssa.SsaForm;
import i8086.ssa.SsaStatement;
import i8086.target.Expansion;
import i8086.target.Form;
import i8086.target.Shape;
import i8086.target.Target;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the IR into instructions, choosing the form of each operation.
 *
 * <p>What it is given is the SSA form, and what it emits keeps the form's names: a
 * value is written as the version it is, so the instructions say which definition
 * each use reads. That is what the allocator needs to see, and it is why this asks
 * the form about a name — whether it is a value, what variable it is a version of,
 * and what type that variable has — rather than the module's own names, which are
 * not renamed ({@code docs/ssa.md}).
 *
 * <p>What it does <em>not</em> do is decide where a value lives: every operand
 * that stands for a variable becomes an {@link Operand.Virtual}, and register
 * allocation turns those into registers afterwards. That split is what lets one
 * selection feed any allocator, and it is why an instruction chosen here cannot
 * be printed yet.
 *
 * <p>It asks the target for everything machine-shaped ({@code AGENTS.md},
 * invariant 2). It has no mnemonic of its own: it says "an addition, a register
 * and a literal", takes the smallest form that fits, and takes one that does not
 * keep the flags only when nothing can read them. That last question is answered
 * by the surface itself: an operation written with {@code expr} has already
 * given the flags up — {@code docs/ir.md} §5.2 — so a smaller instruction that
 * leaves different flags is available there and not in {@code eval}.
 *
 * <p>Memory is where the shapes stop being uniform. An address is not a value: what
 * may stand inside the brackets is three registers on this machine, so a value used
 * as one has fewer places to live than a value that is only computed with, and the
 * operand shapes a form states are what say which is which. A load or a store is
 * therefore not "an operator with forms" but its own shape, and the forms for it
 * come from the target like everything else.
 *
 * <p>What is not built yet is refused, with a position and a reason: conversions,
 * an access narrower or wider than a register, a store of something that has to be
 * computed first, and multiplications this machine has no shift trick for.
 */
public final class InstructionSelector {

    /** Temps are named so that no name a person can write can collide with one. */
    private static final String TEMP_PREFIX = "$t";

    private final Target target;
    /**
     * The module's names, for the one question the form cannot answer.
     *
     * <p>A label is not renamed — renaming is about values, and a place has no
     * version — so the labels are still the module's. Everything about a value comes
     * from the form instead, whose names are versions ({@code docs/ssa.md}).
     */
    private final Names names;
    private final SsaForm form;
    private int temps;
    private List<Instruction> out;

    /**
     * The types of the temporaries this selector made, by name.
     *
     * <p>A temporary is the selector's own invention and the form has never heard of it, but the
     * allocator has to know how wide it is: a byte value and a register's worth of value live in
     * different registers and are named differently in an instruction, so a temporary without a
     * width is a byte instruction written with a word register — which is not an instruction
     * ({@code docs/ir.md} §3.2). The width travels with the name, so it reaches
     * {@link Selection#typeOf} the way the form's own values do.
     */
    private final Map<String, Type> tempTypes = new LinkedHashMap<String, Type>();

    /**
     * Whether the flags can still be read in front of the item being selected.
     *
     * <p>What decides whether an instruction that writes them may be used where the surface asked
     * for one that leaves them alone — building a zero with {@code xor r, r} rather than
     * {@code mov r, 0}, which is a byte shorter on a word ({@code docs/ir.md} §4.2). Live is the
     * safe answer, and it is the answer wherever the question has not been worked out.
     */
    private boolean flagsLiveHere = true;

    public InstructionSelector(SsaForm form, Target target) {
        this.target = target;
        this.form = form;
        this.names = Names.of(form.module());
    }

    public static Selection select(SsaForm form, Target target) {
        return new InstructionSelector(form, target).run();
    }

    /**
     * Every item of the form, in the order it was written, and the instructions each
     * became.
     *
     * <p>The walk is the form's rather than the module's because the items are the
     * form's: a block holds the items between its label and the next, in source order,
     * so walking the blocks in order and their statements in order gives the module's
     * items back. What is skipped is the φ's: a φ is not an item and not an
     * instruction, and what it says — that the values reaching it share a register —
     * is a constraint on the allocator rather than something to emit.
     */
    private Selection run() {
        List<Selection.Piece> pieces = new ArrayList<Selection.Piece>();
        List<List<String>> groups = new ArrayList<List<String>>();
        Map<Item, Boolean> flagsLive = flagsLiveBefore(Liveness.of(form.cfg(), names));
        for (Block block : form.cfg().blocks()) {
            // A φ is not an item and not an instruction. What it says is that the values
            // reaching it are one value as far as a register is concerned, because there
            // is no copy at a merge (docs/ssa.md §8) — so it is a fact about the stream
            // that travels with it, and the allocator is the one that acts on it.
            for (Phi phi : form.phis(block)) {
                groups.add(joined(phi));
            }
            for (SsaStatement statement : form.statements(block)) {
                Item item = statement.item();
                out = new ArrayList<Instruction>();
                flagsLiveHere = isLive(flagsLive.get(item));
                select(item);
                pieces.add(new Selection.Piece(item, out));
            }
        }
        Map<String, String> variables = variables();
        return new Selection(pieces, groups, variables, homes(variables), types());
    }

    /**
     * Whether the flags can still be read in front of each item, by item.
     *
     * <p>The ordinary backward question about one variable, and the variable is {@code flags}
     * ({@code docs/ir.md} §4.1): they are live where something reads them before writing them
     * again. What a block hands its successors is where those blocks are live coming in, which is
     * the liveness SSA construction already asks about φ's — asked of the same {@code flags} name,
     * because that is what the name is.
     *
     * <p>Live is the answer that costs a byte and never costs correctness, so nothing here has to
     * be precise: a block nothing reaches, an item that reads the flags without defining them, and
     * an item missing from the answer all end up saying the flags are live.
     *
     * <p>The items are the form's rather than the module's, which is what the statement a selector
     * is given holds: a renamed item is a different object from the one the block was built with,
     * and the question "is this the item in front of me" has to have an answer that survives that.
     */
    private Map<Item, Boolean> flagsLiveBefore(Liveness liveness) {
        Map<Item, Boolean> live = new LinkedHashMap<Item, Boolean>();
        for (Block block : form.cfg().blocks()) {
            boolean alive = false;
            for (Block successor : block.successors()) {
                alive = alive || liveness.isLiveIn(successor, Names.FLAGS);
            }
            List<SsaStatement> statements = form.statements(block);
            for (int at = statements.size() - 1; at >= 0; at--) {
                Item item = statements.get(at).item();
                alive = Effects.readsFlags(item)
                        || (alive && !Effects.definedBy(item).contains(Names.FLAGS));
                live.put(item, Boolean.valueOf(alive));
            }
        }
        return live;
    }

    /** Whether the answer that was worked out for an item was that the flags are live. */
    private static boolean isLive(Boolean answer) {
        return answer == null || answer.booleanValue();
    }

    /**
     * The names one φ says have to share a register: its own, and the values it merges.
     *
     * <p>An undefined value is left out. It is the name for "this variable, with no
     * value" ({@code docs/ssa.md} §5), so there is nothing for it to agree with — and
     * the program that reads it reads whatever happens to be there, which is what the
     * surface says reading a variable before writing it does ({@code docs/ir.md} §3.1).
     */
    private List<String> joined(Phi phi) {
        List<String> names = new ArrayList<String>();
        names.add(phi.name());
        for (String operand : phi.operands()) {
            if (form.isVersion(operand)) {
                names.add(operand);
            }
        }
        return names;
    }

    /**
     * The version table, so that a refusal can name the variable instead of the version
     * ({@link Selection#variableOf}).
     */
    private Map<String, String> variables() {
        Map<String, String> variables = new LinkedHashMap<String, String>();
        for (String name : form.versions()) {
            variables.put(name, form.variableOf(name));
        }
        for (String name : form.undefinedValues()) {
            variables.put(name, form.variableOf(name));
        }
        return variables;
    }

    /**
     * The type of every value, so that the allocator can tell a byte from a register's worth
     * of value when it decides where one may live.
     *
     * <p>The selector's own temporaries are in here too, with the width their context gave them: a
     * temporary is a name in an instruction like any other, and a name the allocator has no width
     * for is one it would place as a whole register ({@code docs/ir.md} §3.2).
     */
    private Map<String, Type> types() {
        Map<String, Type> types = new LinkedHashMap<String, Type>();
        for (String name : form.versions()) {
            types.put(name, form.typeOf(name));
        }
        for (String name : form.undefinedValues()) {
            types.put(name, form.typeOf(name));
        }
        types.putAll(tempTypes);
        return types;
    }

    /**
     * The home of every value whose variable declared one ({@code docs/ir.md} §3.1.2).
     *
     * <p>The declaration is the module's, and a value is the form's, so this is the one
     * place the two are put beside each other: a home is declared once, on a variable, and
     * every version of that variable lives in the same bytes. It is the allocator's answer
     * to use — whether the value ends up there is not decided here.
     */
    private Map<String, String> homes(Map<String, String> variables) {
        Map<String, String> declared = new LinkedHashMap<String, String>();
        for (Item item : form.module().items()) {
            if (item instanceof Item.Var && ((Item.Var) item).home() != null) {
                declared.put(((Item.Var) item).name(), ((Item.Var) item).home());
            }
        }
        Map<String, String> homes = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String home = declared.get(entry.getValue());
            if (home != null) {
                homes.put(entry.getKey(), home);
            }
        }
        return homes;
    }

    // --- items -------------------------------------------------------------

    private void select(Item item) {
        if (item instanceof Item.Var || item instanceof Item.Label || item instanceof Item.Data
                || item instanceof Item.Pad) {
            return; // a declaration is not code, and a label is the emitter's to write
        }
        if (item instanceof Item.Return) {
            out.add(new Instruction(item.position(), "ret", new ArrayList<Operand>()));
            return;
        }
        if (item instanceof Item.InlineAsm) {
            // Already instructions, and already decided: an inline block names
            // its registers itself, which is the whole point of it. What its clause gives
            // it is put in place first.
            emitArguments(((Item.InlineAsm) item).arguments());
            out.addAll(((Item.InlineAsm) item).body());
            return;
        }
        if (item instanceof Item.Assign) {
            selectAssign((Item.Assign) item);
            return;
        }
        if (item instanceof Item.Compare) {
            selectCompare((Item.Compare) item);
            return;
        }
        if (item instanceof Item.Jump) {
            out.add(new Instruction(item.position(), target.jumpMnemonic(), operands(
                    new Operand.Name(item.position(), ((Item.Jump) item).target()))));
            return;
        }
        if (item instanceof Item.Machine) {
            // One instruction, with what it is given: an interrupt vector is a number,
            // and a number needs no deciding (docs/ir.md §11). What its clause gives it
            // goes into the registers first, in the same item.
            Item.Machine machine = (Item.Machine) item;
            emitArguments(machine.arguments());
            List<Operand> given = new ArrayList<Operand>();
            for (long operand : machine.operands()) {
                given.add(new Operand.Number(item.position(), operand,
                        Numbers.spelling(operand)));
            }
            out.add(new Instruction(item.position(), machine.mnemonic(), given));
            return;
        }
        if (item instanceof Item.MovReg) {
            emitMovReg((Item.MovReg) item);
            return;
        }
        if (item instanceof Item.MovRegRead) {
            emitMovRegRead((Item.MovRegRead) item);
            return;
        }
        if (item instanceof Item.FarJump) {
            // One instruction, and the operand shape is what makes it far: the machine
            // has the immediate far pointer for exactly this (docs/ir.md §7.1). What the
            // clause hands over goes into the registers first, which is how a loader gives
            // the next stage its state.
            Item.FarJump far = (Item.FarJump) item;
            emitArguments(far.arguments());
            out.add(new Instruction(item.position(), target.jumpMnemonic(), operands(
                    new Operand.Far(item.position(), far.segment(), far.offset()))));
            return;
        }
        if (item instanceof Item.Branch) {
            Item.Branch branch = (Item.Branch) item;
            out.add(new Instruction(item.position(), branch.condition(), operands(
                    new Operand.Name(item.position(), branch.target()))));
            return;
        }
        if (item instanceof Item.Eval) {
            // The value is thrown away, so it needs somewhere to go that is not
            // anybody's variable; the flags are why the statement was written, so
            // they are wanted here even though the value is not.
            Operation operation = ((Item.Eval) item).operation();
            emitOperation(operation, temp(typeOfOperation(operation)), true);
            return;
        }
        throw notYet(item, "this statement");
    }

    private void selectAssign(Item.Assign assign) {
        if (assign.place() instanceof Place.Memory) {
            selectStore(assign);
            return;
        }
        String destination = ((Place.Name) assign.place()).name();
        if (isLabel(assign.value())) {
            emitLabelAddress(destination, (Value.Name) assign.value());
            return;
        }
        emitValue(assign.value(), destination, flagsMayBeRead(assign.value()));
    }

    /**
     * Writes a value into memory.
     *
     * <p>What can go there is what the machine can write in one instruction: a
     * register or a literal. Anything else is a value that has to be computed first,
     * and the surface has a way to say that — put it in a variable — so a store of a
     * computation is refused rather than given a temporary nobody wrote.
     */
    private void selectStore(Item.Assign assign) {
        Place.Memory place = (Place.Memory) assign.place();
        Value value = assign.value();
        boolean literal = value instanceof Value.Number;
        if (!literal && !(value instanceof Value.Name)) {
            throw new CompileError(assign.position(),
                    "a store of a value that has to be computed first is not something this "
                            + "compiler can emit yet: put the value in a variable and store that "
                            + "(docs/ir.md §5.3)");
        }
        requireRegisterAccess(place.operand(), "store");

        List<Operand> operands = operands(memory(place.operand()), operandOf(value));
        Form form = smallest(literal ? target.storeLiteralForms() : target.storeForms(),
                operands);
        if (form == null) {
            throw noFormFor("a store", assign.position(), operands);
        }
        out.add(new Instruction(assign.position(), form.mnemonic(), operands));
    }

    /**
     * Reads a value out of memory.
     *
     * <p>The load is where the width of the access has to be said out loud: the
     * register it goes into says it for a word, and the surface says it with a
     * prefix when the access is narrower than the register. A byte load into a
     * sixteen-bit register would need the low half of one, and nothing here knows
     * how to name half a register yet — so it is refused with that as the reason.
     */
    private void emitLoad(Value.Memory load, String destination) {
        requireRegisterAccess(load.operand(), "load");
        List<Operand> operands = operands(virtual(destination, load.position()),
                memory(load.operand()));
        Form form = smallest(target.loadForms(), operands);
        if (form == null) {
            throw noFormFor("a load", load.position(), operands);
        }
        out.add(new Instruction(load.position(), form.mnemonic(), operands));
    }

    /**
     * Refuses an access wider than a register.
     *
     * <p>A byte is fine: a byte value lives in the low half of a register that has a name for that
     * half, and the allocator writes that half's name into the instruction ({@code docs/ir.md}
     * §3.2). Wider than a register is a different matter — it is two registers at once, which
     * nothing here can name.
     */
    private static void requireRegisterAccess(MemoryOperand operand, String what) {
        if (operand.size() != null && operand.size().bytes() > Size.WORD.bytes()) {
            throw new CompileError(operand.position(),
                    "a " + operand.size().spelling() + " " + what + " is not something this "
                            + "compiler can emit yet: it is two registers at once, and nothing "
                            + "here can name a pair (docs/ir.md §3.4)");
        }
    }

    /**
     * {@code movreg ds, 0}: the machine's segmentation state, which the target turns into a
     * sequence ({@code docs/ir.md} §8.1).
     *
     * <p>A copied segment register reaches the target as a register the selector wrote by hand —
     * the same way a sequence names {@code ax} or {@code cl} — because that is all it is: a
     * register, inside a sequence the target declared.
     */
    private void emitMovReg(Item.MovReg movreg) {
        // The verifier has already said that the name is one this target has and that the source is
        // a value or a segment register; what is left is the target's own answer about how (or
        // whether) it can set it. The flags are part of that answer: a segment register takes no
        // immediate, so a zero goes through a scratch register, and building one there is cheaper
        // than moving it wherever they can still be read (docs/ir.md §4.2).
        Operand value = movreg.source() != null
                ? new Operand.Name(movreg.position(), movreg.source())
                : operandOf(movreg.value());
        Expansion sequence = target.writeState(movreg.position(), movreg.name(), value,
                flagsLiveHere);
        if (sequence == null) {
            throw new CompileError(movreg.position(),
                    "this target has no way to set '" + movreg.name() + "' (docs/ir.md §8.1)");
        }
        out.addAll(sequence.instructions());
    }

    /**
     * {@code movreg drive, dl}: one of the machine's own registers, read into a value
     * ({@code docs/ir.md} §8.1).
     *
     * <p>What the instruction is, is the target's answer, the same way the write direction's is: on
     * this machine a register moves into a value in one {@code mov}, and a machine where it takes a
     * sequence says so itself. What the value is read into is a virtual register like every other
     * destination, so where it ends up is still the allocator's to decide — and the allocator is
     * the one that has to keep other values out of the register being read.
     */
    private void emitMovRegRead(Item.MovRegRead read) {
        Expansion sequence = target.readState(read.position(),
                virtual(read.variable(), read.position()), read.register());
        if (sequence == null) {
            throw new CompileError(read.position(),
                    "this target has no way to read '" + read.register() + "' (docs/ir.md §8.1)");
        }
        out.addAll(sequence.instructions());
    }

    /**
     * A conversion: narrowing is nearly free, widening is a sequence the target declares.
     *
     * <p>{@code y = byte x} asks for the low byte of {@code x}, and the machine already has it
     * there — the value lives in the low half of a register, so the narrowing is a move that says
     * which half is meant, and nothing at all when the destination is that same register
     * ({@code docs/ir.md} §3.5). Widening is the other direction and is not free on this machine: no
     * {@code MOVZX} and no {@code MOVSX} before the 386, so it is a sequence the target declares and
     * this is the lookup.
     */
    private void emitConversion(Value.Convert convert, String destination) {
        String source = sourceRegister(convert.operand());
        if (source == null) {
            throw notYet(convert.position(), "a conversion of a value that is not a variable: a "
                    + "conversion reads a register's half, and which register a load lands in is not "
                    + "decided here — put the value in a variable first (docs/ir.md §3.5)");
        }
        if (convert.conversion().direction() == Conversion.Direction.WIDEN) {
            emitWidening(convert, destination, source);
            return;
        }
        if (convert.conversion() == Conversion.LOW_WORD || source.equals(destination)) {
            // The low word of a value is the register it lives in, since a value wider than a
            // register is not something this back end can do yet; and the low half is already where
            // the result goes when the two are the same name. Either way it is a copy, and the
            // allocator drops it when the two values end up in one register.
            emitMove(destination, source, convert.position());
            return;
        }
        out.add(new Instruction(convert.position(), "mov", operands(
                virtual(destination, convert.position()),
                new Operand.LowByte(convert.position(), source))));
    }

    /**
     * {@code x = movzx y} and {@code x = movsx y}: the extension this machine has no instruction
     * for, so the target declares the sequence that does it ({@code docs/ir.md} §3.5).
     *
     * <p>A widening into a double word is refused rather than done halfway: the result is two
     * registers, and this back end can name one.
     */
    private void emitWidening(Value.Convert convert, String destination, String source) {
        Type widened = form.typeOf(destination);
        if (widened != null && widened.bytes() > Size.WORD.bytes()) {
            throw notYet(convert.position(), "a widening to a " + widened.spelling() + ": the "
                    + "result is two registers and nothing here can name a pair (docs/ir.md §3.5)");
        }
        Expansion sequence = target.widen(convert.position(),
                virtual(destination, convert.position()), virtual(source, convert.position()),
                convert.conversion() == Conversion.SIGN_EXTEND);
        if (sequence == null) {
            throw notYet(convert.position(), "a widening: this target declares no sequence for it "
                    + "(docs/ir.md §3.5)");
        }
        out.addAll(sequence.instructions());
    }

    /**
     * The {@code with} clause of a statement: its operands into its registers, in that order
     * ({@code docs/ir.md} §11).
     *
     * <p>Each one is a move, which is the whole of what a clause means on this machine: nothing
     * here pins a value to a register, it copies one into place and then the statement runs. A
     * label is an address like anywhere else, so it goes in as an offset — {@code bx = buffer} is
     * how a call is told where to put something.
     */
    private void emitArguments(List<Item.Argument> arguments) {
        for (Item.Argument argument : arguments) {
            SourcePos where = argument.position();
            Operand value = isLabel(argument.value())
                    ? new Operand.Offset(where, ((Value.Name) argument.value()).name())
                    : operandOf(argument.value());
            out.add(new Instruction(where, "mov",
                    operands(new Operand.Name(where, argument.register()), value)));
        }
    }

    /** {@code p = msg}: the address of a label, as an immediate. */
    private void emitLabelAddress(String destination, Value.Name label) {
        out.add(new Instruction(label.position(), "mov",
                operands(virtual(destination, label.position()),
                        new Operand.Offset(label.position(), label.name()))));
    }

    /** The memory operand an instruction carries, from the one the IR wrote. */
    private Operand memory(MemoryOperand operand) {
        List<Operand.Memory.Atom> atoms = new ArrayList<Operand.Memory.Atom>();
        if (operand.base() != null) {
            // A base that is a variable is a value waiting for a register; a base
            // that is a label is a name the assembler resolves. The syntax cannot
            // tell them apart, and this is where the answer is known.
            atoms.add(form.isValue(operand.base())
                    ? Operand.Memory.Atom.ofVirtual(operand.base())
                    : Operand.Memory.Atom.ofName(operand.base()));
        }
        if (operand.base() == null || operand.displacement() != 0) {
            atoms.add(Operand.Memory.Atom.ofNumber(operand.displacement()));
        }
        return new Operand.Memory(operand.position(), operand.size(), operand.segment(), atoms);
    }

    /** The smallest form whose operand shapes fit, or null when none does. */
    private static Form smallest(List<Form> forms, List<Operand> operands) {
        Form best = null;
        for (Form form : forms) {
            if (writtenOperands(form, operands) == null) {
                continue;
            }
            if (best == null || form.bytes() < best.bytes()) {
                best = form;
            }
        }
        return best;
    }

    private static CompileError noFormFor(String what, SourcePos where, List<Operand> operands) {
        return new CompileError(where,
                "this target has no form for " + what + " with these operands: " + operands);
    }

    private boolean isLabel(Value value) {
        return value instanceof Value.Name && names.isLabel(((Value.Name) value).name());
    }

    /**
     * {@code cmp} or {@code test}: two operands and no value.
     *
     * <p>It is an operation in the same sense {@code eval} is — one operation,
     * done as written — so the flags it leaves are the ones the writer asked for
     * and the branch after it reads those. The machine's forms take a register
     * first, so a literal on the left is put into one: {@code cmp 5, x} is not
     * something this machine can say, and saying it another way is cheap.
     */
    private void selectCompare(Item.Compare compare) {
        String first;
        if (compare.left() instanceof Value.Number) {
            first = temp(typeOfComparison(compare));
            emitValue(compare.left(), first, true);
        } else {
            first = registerNameOf(compare.left());
        }

        List<Operand> operands = new ArrayList<Operand>();
        operands.add(virtual(first, compare.position()));
        operands.add(operandOf(compare.right()));

        Form best = null;
        List<Operand> written = null;
        for (Form form : target.compareForms(compare.kind())) {
            List<Operand> candidate = writtenOperands(form, operands);
            if (candidate != null && (best == null || form.bytes() < best.bytes())) {
                best = form;
                written = candidate;
            }
        }

        // A comparison with zero says what a test of the operand against itself says, when the
        // target promises the two leave the same flags (docs/ir.md §4.2) — and it says it without
        // the zero, so it is a byte shorter wherever a register form is shorter than an immediate
        // one. Which instruction it is, is the target's: the operand goes on both sides and the
        // forms are the ones the target has for a test.
        if (best != null && compare.kind() == Item.Compare.Kind.CMP
                && target.zeroComparisonIsATest() && isZero(compare.right())) {
            List<Operand> asTest = operands(virtual(first, compare.position()),
                    virtual(first, compare.position()));
            Form candidate = smallest(target.compareForms(Item.Compare.Kind.TEST), asTest);
            if (candidate != null && candidate.bytes() < best.bytes()) {
                out.add(new Instruction(compare.position(), candidate.mnemonic(), asTest));
                return;
            }
        }

        if (best == null) {
            throw new CompileError(compare.position(),
                    "no way to compare these operands is available yet: a memory operand is not "
                            + "handled yet");
        }
        out.add(new Instruction(compare.position(), best.mnemonic(), written));
    }

    /** Whether a value is the literal zero, which says nothing a test of an operand does not. */
    private static boolean isZero(Value value) {
        return value instanceof Value.Number && ((Value.Number) value).value() == 0;
    }

    /** Whether the flags this value leaves can be read by anything afterwards. */
    private static boolean flagsMayBeRead(Value value) {
        return value instanceof Value.Eval;
    }

    // --- values ------------------------------------------------------------

    /** Computes a value into {@code destination}, which names a variable. */
    private void emitValue(Value value, String destination, boolean flagsMayBeRead) {
        if (value instanceof Value.Name) {
            emitMove(destination, ((Value.Name) value).name(), value.position());
            return;
        }
        if (value instanceof Value.Number) {
            Value.Number literal = (Value.Number) value;
            if (literal.value() == 0) {
                // The one place a zero is known to be a zero, so it is the one place a shorter
                // instruction can build it instead of moving it (docs/ir.md §4.2).
                out.add(target.zero(literal.position(), virtual(destination, literal.position()),
                        sizeOf(destination), flagsLiveHere));
                return;
            }
            out.add(new Instruction(literal.position(), "mov", operands(
                    virtual(destination, literal.position()),
                    new Operand.Number(literal.position(), literal.value(), literal.spelling()))));
            return;
        }
        if (value instanceof Value.Eval) {
            emitOperation(((Value.Eval) value).operation(), destination, true);
            return;
        }
        if (value instanceof Value.Expr) {
            emitExpression(((Value.Expr) value).expression(), destination);
            return;
        }
        if (value instanceof Value.Memory) {
            emitLoad((Value.Memory) value, destination);
            return;
        }
        if (value instanceof Value.Convert) {
            emitConversion((Value.Convert) value, destination);
            return;
        }
        throw notYet(value.position(), "a load");
    }

    /** An expression tree, which is what {@code expr} is for. */
    private void emitExpression(Expression expression, String destination) {
        if (expression instanceof Expression.Leaf) {
            // Inside expr the flags are already given up, so nothing below this
            // point may re-introduce a claim about them.
            emitValue(((Expression.Leaf) expression).value(), destination, false);
            return;
        }
        if (expression instanceof Expression.Unary) {
            Expression.Unary unary = (Expression.Unary) expression;
            emitExpression(unary.operand(), destination);
            emitInPlace(unary.operator(), destination, null, null, expression.position(),
                    false);
            return;
        }

        Expression.Apply apply = (Expression.Apply) expression;
        Operator operator = apply.operator();
        Expansion expansion = expansionFor(operator, literalOf(apply.right()),
                sourceRegister(apply.left()), destination, apply.position());
        if (expansion != null) {
            requireFlagsMayBeLost(expansion.keepsFlags(), apply.position(), operator, false);
            out.addAll(expansion.instructions());
            return;
        }

        // Multiply and divide are done in registers the machine names itself, which
        // means a sequence rather than an instruction — and the sequence wants its
        // operands as operands, so it is asked before either side is moved anywhere.
        Value leftLeaf = leafOf(apply.left());
        Value rightLeaf = leafOf(apply.right());
        if (leftLeaf != null && rightLeaf != null) {
            Expansion sequence = implicitSequence(operator, Arrays.asList(leftLeaf, rightLeaf),
                    destination);
            if (sequence != null) {
                requireFlagsMayBeLost(sequence.keepsFlags(), apply.position(), operator, false);
                out.addAll(sequence.instructions());
                return;
            }
        }

        if (isLiteral(apply.right())) {
            emitExpression(apply.left(), destination);
            emitInPlace(operator, destination, ((Expression.Leaf) apply.right()).value(),
                    Signedness.of(apply, form::typeOf), apply.position(), false);
            return;
        }

        // The right-hand side needs a register of its own, so it is computed
        // first, into a temp, and the left goes straight into the destination. The temp is as wide
        // as what the expression computes, which is as wide as where the answer is going: every
        // operand of one expression has the same width (docs/ir.md §3.2).
        String scratch = temp(typeOfName(destination));
        emitExpression(apply.right(), scratch);
        emitExpression(apply.left(), destination);
        emitInPlace(operator, destination, new Value.Name(apply.right().position(), scratch),
                Signedness.of(apply, form::typeOf), apply.position(), false);
    }

    /**
     * A trick the machine has for this node, or null when it has none.
     *
     * <p>Both forms of an operation come through here, {@code eval} included:
     * when the trick exists but its flags are not the ones the operation would
     * leave, the complaint the writer needs is about the flags and not about the
     * missing instruction, and it is this path that can say so.
     */
    private Expansion expansionFor(Operator operator, Value right, String source,
                                   String destination, SourcePos where) {
        if (source == null || !(right instanceof Value.Number)) {
            return null;
        }
        long constant = ((Value.Number) right).value();
        Operand target0 = virtual(destination, where);
        Operand source0 = virtual(source, where);
        if (operator == Operator.MULTIPLY || operator == Operator.MULTIPLY_SIGNED
                || operator == Operator.MULTIPLY_UNSIGNED) {
            return target.multiplyByConstant(where, target0, source0, constant);
        }
        if (operator == Operator.SHIFT_LEFT || operator == Operator.SHIFT_RIGHT
                || operator == Operator.SHIFT_ARITHMETIC) {
            String shift = onlyFormMnemonic(operator);
            return shift == null ? null
                    : target.shiftByConstant(where, shift, target0, source0, constant);
        }
        return null;
    }

    /** The register a leaf already names, or null when it is not one. */
    private static String sourceRegister(Expression expression) {
        if (!(expression instanceof Expression.Leaf)) {
            return null;
        }
        Value value = ((Expression.Leaf) expression).value();
        return value instanceof Value.Name ? ((Value.Name) value).name() : null;
    }

    /** The register an operand already names, or null when it is a literal. */
    private static String sourceRegister(Value value) {
        return value instanceof Value.Name ? ((Value.Name) value).name() : null;
    }

    private static boolean isLiteral(Expression expression) {
        return expression instanceof Expression.Leaf
                && ((Expression.Leaf) expression).value() instanceof Value.Number;
    }

    // --- one operation -----------------------------------------------------

    /**
     * One operation, into {@code destination}.
     *
     * <p>The first operand goes into the destination — that is the move that can
     * be elided when the destination is already where the first operand lives —
     * and the operation then happens in place, with the second operand as it
     * stands.
     */
    private void emitOperation(Operation operation, String destination, boolean flagsMayBeRead) {
        List<Value> operands = operation.operands();
        Operator operator = operation.operator();
        Value second = operands.size() == 1 ? null : operands.get(1);

        Expansion expansion = expansionFor(operator, second,
                sourceRegister(operands.get(0)), destination, operation.position());
        if (expansion == null) {
            expansion = implicitSequence(operator, operands, destination);
        }
        if (expansion != null) {
            requireFlagsMayBeLost(expansion.keepsFlags(), operation.position(), operator,
                    flagsMayBeRead);
            out.addAll(expansion.instructions());
            return;
        }

        emitValue(operands.get(0), destination, flagsMayBeRead);
        emitInPlace(operator, destination, second, signedness(operator, operands, destination),
                operation.position(), flagsMayBeRead);
    }

    /** The value an expression is, when it is one, or null when it is a tree. */
    private static Value leafOf(Expression expression) {
        return expression instanceof Expression.Leaf
                ? ((Expression.Leaf) expression).value() : null;
    }

    /**
     * Multiplication and division done on their own, where the operands are still
     * operands.
     *
     * <p>This is worth doing before either side is moved anywhere, and the reason is
     * what the allocator sees. A sequence that copies into the destination and back
     * out of it makes the destination live across the whole thing, which costs
     * registers; one that reads its operands where they already are gives the
     * destination its first mention at the end, when the work is done. The in-place
     * route is still there for the cases this cannot take: an operand that is itself a
     * tree, and a division in the middle of one.
     */
    private Expansion implicitSequence(Operator operator, List<Value> operands,
                                       String destination) {
        boolean multiplies = operator == Operator.MULTIPLY
                || operator == Operator.MULTIPLY_UNSIGNED
                || operator == Operator.MULTIPLY_SIGNED;
        if (operands.size() < 2 || (!multiplies && !operator.divides())) {
            return null;
        }
        for (Value operand : operands) {
            if (!(operand instanceof Value.Name) && !(operand instanceof Value.Number)) {
                return null; // a load in an operand: not something this back end can hand on
            }
            requireWordWide(operator, operand.position(), typeOf(operand));
        }

        SourcePos where = operands.get(0).position();
        boolean signed = Boolean.TRUE.equals(signedness(operator, operands, destination));
        Operand left = operandOf(operands.get(0));
        Operand right = operandOf(operands.get(1));
        Operand target0 = virtual(destination, where);
        if (multiplies) {
            // A literal is fine on either side: multiplication does not care, and the
            // target moves one it finds on the right.
            return target.multiply(where, target0, left, right, signed);
        }
        if (right instanceof Operand.Number) {
            // A division cannot swap its sides, so a literal divisor needs a register —
            // and a register it is, not a value: the selector is the one saying where
            // this goes, and the allocator is told by the ordinary rule that a register
            // written by hand is destroyed.
            List<Instruction> instructions = new ArrayList<Instruction>();
            instructions.add(new Instruction(where, "mov",
                    operands(new Operand.Name(where, LITERAL_SCRATCH), right)));
            Expansion rest = target.divide(where, target0, left,
                    new Operand.Name(where, LITERAL_SCRATCH), signed,
                    operator == Operator.REMAINDER);
            if (rest == null) {
                return null;
            }
            instructions.addAll(rest.instructions());
            return new Expansion(instructions, rest.keepsFlags());
        }
        return target.divide(where, target0, left, right, signed,
                operator == Operator.REMAINDER);
    }

    /** The type of an operand that names one, or null when it is a literal. */
    private Type typeOf(Value value) {
        return value instanceof Value.Name ? typeOfName(((Value.Name) value).name()) : null;
    }

    /**
     * The type of a name the selector is working with: a value of the form's, or its own temporary.
     *
     * <p>Both are names in an instruction and both have to be placed, so the one question the
     * allocator asks about a name — how wide is this — has one answer for the two of them.
     */
    private Type typeOfName(String name) {
        Type found = form.typeOf(name);
        return found == null ? tempTypes.get(name) : found;
    }

    /**
     * How wide a value is, for the target's one question about width.
     *
     * <p>A word when nothing says otherwise: a width nobody stated is not one to guess a half
     * register from, and a whole register is what the mode bits of an instruction are chosen from
     * anyway ({@code docs/ir.md} §3.4).
     */
    private Size sizeOf(String name) {
        Type type = typeOfName(name);
        return type == null ? Size.WORD : Size.ofBytes(type.bytes());
    }

    /**
     * The width an operation is, from the first operand that states one.
     *
     * <p>Every operand of one operation has the same width ({@code docs/ir.md} §3.2), so the first
     * one that states a width states all of them, and a literal states none. An operation of
     * nothing but literals states none either, and a register's worth is what such a computation is
     * read as — there is no narrower reading of {@code eval(0xFFFF + 1)} that keeps its carry.
     */
    private Type typeOfOperation(Operation operation) {
        for (Value operand : operation.operands()) {
            Type type = typeOf(operand);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    /**
     * The width of a comparison whose left side is a literal.
     *
     * <p>A literal takes the width of where it goes ({@code docs/ir.md} §3.2), and where it goes here
     * is a register the machine insists on for the first operand. The other side says how wide: a
     * variable states its own type. Anything else is refused rather than given a width guessed at,
     * because a byte value compared through a word register is an instruction no assembler will
     * take — and a wrong answer here is worse than a refusal.
     */
    private Type typeOfComparison(Item.Compare compare) {
        Value other = compare.right();
        Type type = other instanceof Value.Name ? typeOfName(((Value.Name) other).name()) : null;
        if (type == null) {
            throw notYet(compare.position(), "a comparison against a literal with nothing on the "
                    + "other side that says how wide it is: put that value in a variable first "
                    + "(docs/ir.md §3.2)");
        }
        return type;
    }

    /**
     * Refuses a multiplication or division on a value narrower than a register.
     *
     * <p>The machine does these in registers it names itself — {@code ax} and {@code dx} — and the
     * sequences the target declares are written for that sixteen-bit pair. A byte form exists
     * ({@code mul r8} leaves its answer in {@code ax}), and this back end has not asked the target
     * for it: doing a byte multiply with the word sequence would read whatever is in the top half of
     * the register the value lives in, which is an answer nobody computed
     * ({@code docs/ir.md} §6.1).
     */
    private void requireWordWide(Operator operator, SourcePos where, Type type) {
        if (type != null && type.bytes() < Size.WORD.bytes()) {
            throw new CompileError(where, "a " + type.spelling() + " cannot be used with '"
                    + operator.spelling() + "' yet: this machine does it in a fixed register and "
                    + "the sequence is written for a word (docs/ir.md §6.1)");
        }
    }

    /**
     * Whether a multiply or a divide is the signed one, or null when nothing says.
     *
     * <p>Two of the four ways to write one say so in the mnemonic; the other two
     * follow the operands ({@code docs/ir.md} §6.1), which means looking at what the
     * operands are. A literal has no signedness of its own, so the other side
     * decides, and if neither says, the place the answer is going has the same
     * signedness as what it is computed from.
     */
    private Boolean signedness(Operator operator, List<Value> operands, String destination) {
        switch (operator) {
            case MULTIPLY_SIGNED:
            case DIVIDE_SIGNED:
                return Boolean.TRUE;
            case MULTIPLY_UNSIGNED:
            case DIVIDE_UNSIGNED:
                return Boolean.FALSE;
            default:
                break;
        }
        for (Value operand : operands) {
            Boolean signed = Signedness.of(operand, form::typeOf);
            if (signed != null) {
                return signed;
            }
        }
        Type type = form.typeOf(destination);
        return type == null ? null : Boolean.valueOf(type.isSigned());
    }

    /**
     * Multiplication and division, which the machine does in registers it names
     * itself.
     *
     * <p>{@code mul r} multiplies whatever is in {@code ax} and leaves the low half
     * there; {@code div r} divides {@code dx:ax}. So the sequence copies the operand
     * that is already in the destination into {@code ax}, does the operation, and
     * copies the answer back — and those copies name {@code ax} and {@code dx} as
     * operands the selector wrote, which is how the allocator knows to keep other
     * values out of them while this runs.
     *
     * <p>The divisor or multiplier being a literal is no obstacle: the machine takes
     * a register or a memory operand, so the literal is put in one. That register is
     * clobbered, and the allocator is told so by the same rule.
     */
    private Expansion sequenceFor(Operator operator, String destination, Value second,
                                  Boolean signed, SourcePos where) {
        boolean multiplies = operator == Operator.MULTIPLY
                || operator == Operator.MULTIPLY_UNSIGNED
                || operator == Operator.MULTIPLY_SIGNED;
        if (second == null || (!multiplies && !operator.divides())) {
            return null;
        }
        requireWordWide(operator, where, form.typeOf(destination));
        requireWordWide(operator, second.position(), typeOf(second));
        Operand inPlace = virtual(destination, where);
        Operand right = operandOf(second);
        boolean isSigned = signed != null && signed.booleanValue();
        if (right instanceof Operand.Number) {
            // The machine takes a register, so the literal needs one; the sequence
            // says which and the allocator treats it as destroyed.
            List<Instruction> withLiteral = new ArrayList<Instruction>();
            withLiteral.add(new Instruction(where, "mov",
                    operands(new Operand.Name(where, LITERAL_SCRATCH), right)));
            Operand scratch = new Operand.Virtual(where, LITERAL_SCRATCH);
            Expansion rest = multiplies
                    ? target.multiply(where, inPlace, inPlace, scratch, isSigned)
                    : target.divide(where, inPlace, inPlace, scratch, isSigned,
                    operator == Operator.REMAINDER);
            if (rest == null) {
                return null;
            }
            withLiteral.addAll(rest.instructions());
            return new Expansion(withLiteral, rest.keepsFlags());
        }
        if (multiplies) {
            return target.multiply(where, inPlace, inPlace, right, isSigned);
        }
        return target.divide(where, inPlace, inPlace, right, isSigned,
                operator == Operator.REMAINDER);
    }

    /** The literal a value is, or null when it is not one. */
    private static Value literalOf(Expression expression) {
        return isLiteral(expression) ? ((Expression.Leaf) expression).value() : null;
    }

    /**
     * The operation itself, on a value that is already in {@code destination}.
     *
     * <p>Most operations are one instruction, and the target's forms say which. An
     * operation the machine has no ordinary form for — multiplication and division,
     * which it does in registers it names itself — gets a sequence from the target
     * instead ({@code AGENTS.md}, "Expansion"). By the time either is asked, the first
     * operand is already in the destination, so a sequence copies out of it and back
     * into it; a copy that turns out to be a copy of a register into itself is
     * dropped by the allocator, which is the only part of this that knows where
     * anything lives.
     */
    private void emitInPlace(Operator operator, String destination, Value second, Boolean signed,
                             SourcePos where, boolean flagsMayBeRead) {
        List<Form> forms = target.forms(operator);
        if (forms.isEmpty()) {
            Expansion sequence = sequenceFor(operator, destination, second, signed, where);
            if (sequence == null) {
                throw new CompileError(where,
                        "no form for '" + operator.spelling() + "' is available yet: the "
                                + "instruction this machine has for it keeps an operand in a "
                                + "fixed register, and this back end cannot express that");
            }
            requireFlagsMayBeLost(sequence.keepsFlags(), where, operator, flagsMayBeRead);
            out.addAll(sequence.instructions());
            return;
        }

        List<Operand> operands = new ArrayList<Operand>();
        operands.add(virtual(destination, where));
        if (second != null) {
            operands.add(operandOf(second));
        }

        Form best = null;
        List<Operand> written = null;
        Form refused = null;
        for (Form form : forms) {
            List<Operand> candidate = writtenOperands(form, operands);
            if (candidate == null) {
                continue;
            }
            if (!form.keepsFlags() && flagsMayBeRead) {
                // Smaller because it does less, and here the less it does is
                // something the program can still look at.
                if (refused == null || form.bytes() < refused.bytes()) {
                    refused = form;
                }
                continue;
            }
            if (best == null || form.bytes() < best.bytes()) {
                best = form;
                written = candidate;
            }
        }
        if (best == null) {
            throw noForm(operator, where, operands, refused);
        }
        out.add(new Instruction(where, best.mnemonic(), written));
    }

    /** Why there is nothing to emit, saying which of the two reasons it is. */
    private static CompileError noForm(Operator operator, SourcePos where, List<Operand> operands,
                                       Form refused) {
        if (refused != null) {
            return new CompileError(where,
                    "the form this machine has for '" + operator.spelling() + "' here is '"
                            + refused.mnemonic() + "', and it leaves different flags than the "
                            + "operation does; these flags can still be read, so write the value "
                            + "with expr(...), which gives them up (docs/ir.md §5.2)");
        }
        return new CompileError(where,
                "no form this target has fits '" + operator.spelling() + "' with these "
                        + "operands: a memory operand is not handled yet, and neither is a "
                        + "shift by a count that is not a small constant");
    }

    /**
     * The operands the instruction writes, or null when this form does not fit.
     *
     * <p>This is what makes {@code inc} reachable for an addition. It does the
     * work of one operand and a literal, so a form may take one operand fewer
     * than the operation has, provided it says which literal it absorbs
     * ({@link Form#literalOnly()}) and that literal is the one standing there.
     */
    private static List<Operand> writtenOperands(Form form, List<Operand> operands) {
        int count = form.operands().size();
        List<Operand> written;
        if (count == operands.size()) {
            written = operands;
        } else if (count + 1 == operands.size()
                && form.literalOnly() != null
                && operands.get(count) instanceof Operand.Number
                && ((Operand.Number) operands.get(count)).value()
                        == form.literalOnly().longValue()) {
            written = new ArrayList<Operand>(operands.subList(0, count));
        } else {
            return null;
        }

        if (form.literalOnly() != null && !mentionsLiteral(operands, form.literalOnly().longValue())) {
            return null;
        }
        for (int i = 0; i < written.size(); i++) {
            if (Shape.of(written.get(i)) != form.operands().get(i)) {
                return null;
            }
        }
        return written;
    }

    private static boolean mentionsLiteral(List<Operand> operands, long value) {
        for (Operand operand : operands) {
            if (operand instanceof Operand.Number && ((Operand.Number) operand).value() == value) {
                return true;
            }
        }
        return false;
    }

    private static Operand operandOf(Value value) {
        if (value instanceof Value.Number) {
            Value.Number literal = (Value.Number) value;
            return new Operand.Number(literal.position(), literal.value(), literal.spelling());
        }
        return virtual(registerNameOf(value), value.position());
    }

    /**
     * A register a literal goes into when the machine will not take it directly.
     *
     * <p>Multiplying and dividing take a register or a memory operand, so a literal
     * has to be put somewhere. {@code bx} is the choice because it is a value register
     * and not an address register: a value that is only computed with is more common
     * than one that is addressed through, so this is the one that gets in the way of
     * the fewest programs. That it is destroyed is stated by the ordinary rule —
     * operand zero is a register the selector wrote — and the allocator keeps other
     * values out of it around here.
     */
    private static final String LITERAL_SCRATCH = "bx";

    /** Refuses a form that changes the flags where they are still wanted. */
    private static void requireFlagsMayBeLost(boolean keepsFlags, SourcePos where,
                                              Operator operator, boolean flagsMayBeRead) {
        if (!keepsFlags && flagsMayBeRead) {
            throw new CompileError(where,
                    "the way this machine does '" + operator.spelling() + "' leaves different "
                            + "flags from the operation, and here they can still be read; write the "
                            + "value with expr(...), which gives them up (docs/ir.md §5.2), or say "
                            + "which instruction you mean (docs/ir.md §5.5)");
        }
    }

    private String onlyFormMnemonic(Operator operator) {
        List<Form> forms = target.forms(operator);
        return forms.isEmpty() ? null : forms.get(0).mnemonic();
    }

    // --- small pieces ------------------------------------------------------

    private void emitMove(String destination, String source, SourcePos where) {
        if (destination.equals(source)) {
            return; // a value assigned to itself is not an instruction
        }
        out.add(new Instruction(where, "mov",
                operands(virtual(destination, where), virtual(source, where))));
    }

    private static String registerNameOf(Value value) {
        if (value instanceof Value.Name) {
            return ((Value.Name) value).name();
        }
        throw notYet(value.position(), "this operand, which is not a register");
    }

    private static Operand virtual(String name, SourcePos where) {
        return new Operand.Virtual(where, name);
    }

    private static List<Operand> operands(Operand... operands) {
        List<Operand> all = new ArrayList<Operand>(operands.length);
        for (Operand operand : operands) {
            all.add(operand);
        }
        return all;
    }

    private String temp(Type type) {
        String name = TEMP_PREFIX + temps++;
        if (type != null) {
            tempTypes.put(name, type);
        }
        return name;
    }

    private static CompileError notYet(Item item, String description) {
        return notYet(item.position(), description);
    }

    private static CompileError notYet(SourcePos where, String description) {
        return new CompileError(where, "instruction selection cannot emit " + description + " yet");
    }
}
