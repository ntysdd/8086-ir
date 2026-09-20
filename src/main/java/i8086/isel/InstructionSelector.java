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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * The byte each word value is a zero-extended copy of, by the word value's name.
     *
     * <p>Two bytes put together into a word is two moves when the halves are known, and knowing is
     * this: the statement that widened the byte said that everything above it is zero, so the word it
     * made is the byte in the low half and nothing in the high half. A byte value on its own does not
     * say that — a computation that happens to be small is not a computation that cannot carry — and
     * a widening is a statement, so it is read off the statements rather than guessed at
     * ({@link #combinedBytes}).
     */
    private Map<String, String> widenedBytes = new LinkedHashMap<String, String>();

    /**
     * The widened value each word value is a copy of, shifted up by eight, by the word value's name.
     *
     * <p>The same two bytes put together into a word, written as two statements instead of one:
     * {@code h = expr(w shl 8)} says the high half where {@code expr((w shl 8) | v)} says it inside
     * the tree. It is the same fact about the form either way — the high half of the word is the low
     * byte of a value some statement widened — so it is read the same way ({@link #highHalf}).
     */
    private Map<String, String> shiftedHalves = new LinkedHashMap<String, String>();

    /**
     * The widenings whose only reader is a combine, so that emitting one would be work nobody wants.
     *
     * <p>A widening is a statement ({@code w = movzx x}), so it is a value, and a combine that reads
     * it puts the byte itself in the half it belongs in. The widening then has nothing left to do:
     * {@code mov al, x; xor ah, ah} is spent on a register the {@code mov ah, x} is about to write
     * over. What makes leaving it out safe is that <b>nothing else</b> reads the word — a widening
     * whose value goes anywhere else is a value the program asked for, and it stays.
     */
    private Set<String> consumedWidenings = new LinkedHashSet<String>();

    /**
     * The shifts whose only reader is a combine, so that emitting one would be work nobody wants.
     *
     * <p>A combine that reads a half out of {@code h} reads the byte {@code h} was shifted up from,
     * so the shift is spent on a register the combine's own moves are about to fill — the same thing
     * the widening is spent on, one statement further along. All of the shape or none of it: the byte
     * is in the high half <b>because</b> the shift put it there, so a shift that stays is a shift
     * whose word the combine wants as a word, which is the arithmetic it does not build.
     */
    private Set<String> consumedShifts = new LinkedHashSet<String>();

    /**
     * The byte values whose own load a combine makes, and the access each of those is.
     *
     * <p>A widened byte that came from one plain load is the byte the combine wants in a half, so
     * the load is the combine's to do — {@code mov ah, byte [$p1]} where loading it into a register
     * first is a load and then a move. What that costs is the load happening later than it was
     * written, which is why it is only taken where nothing between the two could have written what
     * it reads ({@link #findConsumed}).
     */
    private Set<String> consumedLoads = new LinkedHashSet<String>();

    /** The access each byte value is loaded from, when its one statement is a plain load. */
    private Map<String, Value.Memory> loadedBytes = new LinkedHashMap<String, Value.Memory>();

    /** The access a combine reads a half from, for the halves whose load it took over. */
    private Map<String, Value.Memory> halfAccess = new LinkedHashMap<String, Value.Memory>();

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
        List<Integer> merges = new ArrayList<Integer>();
        List<List<Integer>> mergedOperands = new ArrayList<List<Integer>>();
        Map<Block, Integer> firstPiece = new LinkedHashMap<Block, Integer>();
        Map<Block, Integer> lastPiece = new LinkedHashMap<Block, Integer>();
        FlagLiveness flags = flagLiveness(Liveness.of(form.cfg(), names));
        widenedBytes = widenedBytes();
        shiftedHalves = shiftedHalves();
        loadedBytes = loadedBytes();
        findConsumed(flags);
        for (Block block : form.cfg().blocks()) {
            // A φ is not an item and not an instruction. What it says is that the values
            // reaching it are one value as far as a register is concerned, because there
            // is no copy at a merge (docs/ssa.md §8) — so it is a fact about the stream
            // that travels with it, and the allocator is the one that acts on it. Where it
            // happens is the entry of this block, and where it reads is the end of each
            // predecessor; both are points, and both are worked out once the walk is over.
            firstPiece.put(block, Integer.valueOf(pieces.size()));
            for (Phi phi : form.phis(block)) {
                groups.add(joined(phi));
                merges.add(Integer.valueOf(-1));
                mergedOperands.add(new ArrayList<Integer>());
            }
            for (SsaStatement statement : form.statements(block)) {
                Item item = statement.item();
                out = new ArrayList<Instruction>();
                flagsLiveHere = flags.before(item);
                select(item);
                pieces.add(new Selection.Piece(item, out));
            }
            lastPiece.put(block, Integer.valueOf(pieces.size() - 1));
        }
        List<Selection.Merge> where = merges(groups, firstPiece, lastPiece);
        Map<String, String> variables = variables();
        return new Selection(pieces, groups, where, variables, homes(variables), types());
    }

    /**
     * The byte each word value is a zero-extended copy of, for {@link #widenedBytes}.
     *
     * <p>Three things have to be true, and each of them is a fact about the form: the value is
     * written once, by an assignment whose value is a conversion, the conversion is the zero-extending
     * one, and the value it extends is a byte. A widening into something wider than a register is
     * refused before selection, so a widening that arrives here is one register wide.
     */
    private Map<String, String> widenedBytes() {
        Map<String, String> widened = new LinkedHashMap<String, String>();
        for (Block block : form.cfg().blocks()) {
            for (SsaStatement statement : form.statements(block)) {
                if (!(statement.item() instanceof Item.Assign)) {
                    continue;
                }
                Item.Assign assign = (Item.Assign) statement.item();
                if (!(assign.place() instanceof Place.Name)
                        || !(assign.value() instanceof Value.Convert)) {
                    continue;
                }
                Value.Convert convert = (Value.Convert) assign.value();
                if (convert.conversion() != Conversion.ZERO_EXTEND
                        || !(convert.operand() instanceof Value.Name)) {
                    continue;
                }
                String source = ((Value.Name) convert.operand()).name();
                Type type = form.typeOf(source);
                if (type != null && type.bytes() < Size.WORD.bytes()) {
                    widened.put(((Place.Name) assign.place()).name(), source);
                }
            }
        }
        return widened;
    }

    /**
     * The widened value each word value is a copy of, shifted up by eight, for {@link #shiftedHalves}.
     *
     * <p>Three things have to be true, and each of them is a fact about the form: the value is written
     * once, by an assignment whose value is one operation, the operation shifts its second operand up
     * by eight, and that operand is a value some statement widened a byte into. It is the shape
     * {@link #highHalf} reads out of an expression, written as a statement of its own — and it is read
     * here rather than at selection because which statements a combine reads is what decides what a
     * combine may take over.
     */
    private Map<String, String> shiftedHalves() {
        Map<String, String> shifted = new LinkedHashMap<String, String>();
        for (Block block : form.cfg().blocks()) {
            for (SsaStatement statement : form.statements(block)) {
                if (!(statement.item() instanceof Item.Assign)) {
                    continue;
                }
                Item.Assign assign = (Item.Assign) statement.item();
                if (!(assign.place() instanceof Place.Name)) {
                    continue;
                }
                String widened = shiftedUpByEight(assign.value());
                if (widened != null) {
                    shifted.put(((Place.Name) assign.place()).name(), widened);
                }
            }
        }
        return shifted;
    }

    /**
     * The widened value this value is, shifted up by eight, or null when it is not that value.
     *
     * <p>Two spellings of the same thing, because the surface has two ways to write an operation and
     * the pipeline picks between them: {@code h = eval(w shl 8)} while the flags are wanted, and
     * {@code h = expr(w shl 8)} once the pass that takes the claim away has been over it
     * ({@code i8086.pass.UnreadFlags}). Either of them is a statement that puts a byte in the high
     * half of a word, which is what a combine is looking for.
     */
    private String shiftedUpByEight(Value value) {
        if (value instanceof Value.Eval) {
            Operation operation = ((Value.Eval) value).operation();
            List<Value> operands = operation.operands();
            return operands.size() == 2 && shiftsUpByEight(operation.operator(), operands.get(1))
                    ? widenedValue(leaf(operands.get(0))) : null;
        }
        return value instanceof Value.Expr
                ? widenedValue(shiftedUp(((Value.Expr) value).expression())) : null;
    }

    /**
     * The access each byte value is loaded from, for {@link #loadedBytes}.
     *
     * <p>One shape: an assignment to a name whose value is an access. A volatile one is left out,
     * because an access the program needs to happen where it is written ({@code docs/ir.md} §3.4) is
     * not one anything may carry to another statement.
     */
    private Map<String, Value.Memory> loadedBytes() {
        Map<String, Value.Memory> loaded = new LinkedHashMap<String, Value.Memory>();
        for (Block block : form.cfg().blocks()) {
            for (SsaStatement statement : form.statements(block)) {
                if (!(statement.item() instanceof Item.Assign)) {
                    continue;
                }
                Item.Assign assign = (Item.Assign) statement.item();
                if (!(assign.place() instanceof Place.Name)
                        || !(assign.value() instanceof Value.Memory)) {
                    continue;
                }
                Value.Memory load = (Value.Memory) assign.value();
                if (!load.operand().isVolatile()) {
                    loaded.put(((Place.Name) assign.place()).name(), load);
                }
            }
        }
        return loaded;
    }

    /**
     * Where each merge is, and where each of its operands is read.
     *
     * <p>The name is defined where the block is entered, which is the piece the block starts at; an
     * operand is read at the end of the predecessor it arrives from, which is that block's last piece.
     * A φ's operands are in the order of the block's predecessors, and the ones that are not values
     * are skipped in both lists alike — an undefined value is the name for "this variable, with no
     * value", and there is nothing for it to agree with ({@code docs/ssa.md} §5). Skipping an
     * operand is what makes the two lists the same length, and it must not move the predecessor the
     * next operand is paired with: the operands are the predecessors', one for one, and an undefined
     * one still belongs to its own. Pairing them one off is not a lost read but a read attributed to
     * the wrong predecessor, which is liveness along an edge that does not exist.
     */
    private List<Selection.Merge> merges(List<List<String>> groups,
                                         Map<Block, Integer> firstPiece,
                                         Map<Block, Integer> lastPiece) {
        List<Selection.Merge> where = new ArrayList<Selection.Merge>();
        int group = 0;
        for (Block block : form.cfg().blocks()) {
            List<Block> predecessors = block.predecessors();
            for (Phi phi : form.phis(block)) {
                List<Integer> points = new ArrayList<Integer>();
                int operand = 0;
                for (String name : phi.operands()) {
                    Block from = operand < predecessors.size() ? predecessors.get(operand) : null;
                    operand++;
                    if (!form.isVersion(name)) {
                        continue;
                    }
                    Integer point = from == null ? null : lastPiece.get(from);
                    points.add(point == null ? Integer.valueOf(0) : point);
                }
                where.add(new Selection.Merge(firstPiece.get(block).intValue(), points));
                group++;
            }
        }
        return where;
    }

    /**
     * Where the flags are live in front of each item, and behind it.
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
     *
     * <p>Both directions come out of the one walk because both are read at the same moment, and
     * because they are two halves of what one substitution has to know: which instruction may be
     * replaced here, and which of the flags an instruction <em>defines</em> may be taken away.
     */
    private FlagLiveness flagLiveness(Liveness liveness) {
        FlagLiveness flags = new FlagLiveness();
        for (Block block : form.cfg().blocks()) {
            boolean alive = false;
            for (Block successor : block.successors()) {
                alive = alive || liveness.isLiveIn(successor, Names.FLAGS);
            }
            List<SsaStatement> statements = form.statements(block);
            for (int at = statements.size() - 1; at >= 0; at--) {
                Item item = statements.get(at).item();
                flags.after.put(item, Boolean.valueOf(alive));
                alive = Effects.flagsRead(item).contains(Names.FLAGS)
                        || (alive && !Effects.definedBy(item).contains(Names.FLAGS));
                flags.before.put(item, Boolean.valueOf(alive));
            }
        }
        return flags;
    }

    /** Whether the flags can still be read in front of an item, and behind it. */
    private static final class FlagLiveness {

        private final Map<Item, Boolean> before = new LinkedHashMap<Item, Boolean>();
        private final Map<Item, Boolean> after = new LinkedHashMap<Item, Boolean>();

        /** In front of it: whether an instruction here has to leave the flags alone. */
        boolean before(Item item) {
            return isLive(before.get(item));
        }

        /** Behind it: whether the flags this item defines are still read. */
        boolean after(Item item) {
            return isLive(after.get(item));
        }
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
        if (consumedWidenings.contains(destination) || consumedLoads.contains(destination)
                || consumedShifts.contains(destination)) {
            return; // this statement is work the word a combine builds has already done
        }
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
     *
     * <p>A segment register is the exception, and it is the machine's: it takes neither an immediate
     * nor a memory operand, so a value reaches one through a general register, and how is the
     * target's answer — the same one {@code movreg} gets ({@code docs/ir.md} §8.1). Writing
     * {@code mov es, 0} instead is an instruction no assembler takes, and a clause is where a
     * loader sets up the segment it is about to load into.
     *
     * <p>Those go first, whatever order they were written in. The arguments of a clause are all
     * inputs to the statement, so which of them is put in place first is the compiler's business —
     * and the register the machine moves a segment through is one of the general ones, which another
     * argument may be named after: {@code int 0x13 with ah = 2, es = 0} has to reach {@code es}
     * before {@code ah} is written, or the value that got it there is gone.
     *
     * <p>Then the arguments that are variables, and then the ones that are not. A value may have to be
     * brought in from a cell, and the register it is moved through has to still hold it when its own
     * move runs — while every other argument's move writes a register and so destroys whatever was in
     * it. {@code int 0x13 with ah = 2, ch = byte [entry + 3], dl = drive} is the shape that needs the
     * care: {@code drive} is in a cell, and {@code ah} and {@code ch} are written before it is read
     * if the clause is taken in the order it was written.
     */
    private void emitArguments(List<Item.Argument> arguments) {
        for (Item.Argument argument : arguments) {
            if (target.isSegmentRegister(argument.register())) {
                emitArgument(argument);
            }
        }
        for (Item.Argument argument : arguments) {
            if (!target.isSegmentRegister(argument.register()) && holdsAVariable(argument)) {
                emitArgument(argument);
            }
        }
        for (Item.Argument argument : arguments) {
            if (!target.isSegmentRegister(argument.register()) && !holdsAVariable(argument)) {
                emitArgument(argument);
            }
        }
    }

    /**
     * Whether an argument hands over a variable's value, which may be in memory and have to be moved
     * into a register first, as opposed to a literal, a label's address or an access.
     *
     * <p>The question is asked of the form and not of the module, because a clause's value has been
     * renamed by the time a selector sees it: it names a version of the variable rather than the
     * variable ({@code docs/ssa.md}). A label is the module's name still, because renaming is about
     * values.
     */
    private boolean holdsAVariable(Item.Argument argument) {
        Value value = argument.value();
        return value instanceof Value.Name
                && form.variableOf(((Value.Name) value).name()) != null;
    }

    /** One argument of a clause: its operand into its register, the machine's way. */
    private void emitArgument(Item.Argument argument) {
        SourcePos where = argument.position();
        Operand value = operandFor(argument.value());
        if (target.isSegmentRegister(argument.register())) {
            Expansion sequence = target.writeState(where, argument.register(), value,
                    flagsLiveHere);
            if (sequence == null) {
                throw new CompileError(where, "this target has no way to set '"
                        + argument.register() + "' (docs/ir.md §11)");
            }
            out.addAll(sequence.instructions());
            return;
        }
        out.add(new Instruction(where, "mov",
                operands(new Operand.Name(where, argument.register()), value)));
    }

    /**
     * The operand an argument hands over: a literal, a value in a register, a label's address, or an
     * access ({@code docs/ir.md} §11).
     *
     * <p>An address is what a clause is usually given — {@code bx = buffer} tells a routine where to
     * put something — and an access is the same idea the other way round: {@code ch = byte [entry +
     * 3]} is one instruction, where reading the field into a value first costs a register and the
     * move that follows it. The register states the width when the access does not ({@code byte},
     * {@code word}), which is the rule the assembly text has ({@code docs/asm.md} §5).
     */
    private Operand operandFor(Value value) {
        if (value instanceof Value.Memory) {
            return memory(((Value.Memory) value).operand());
        }
        if (isLabel(value)) {
            return new Operand.Offset(value.position(), ((Value.Name) value).name());
        }
        return operandOf(value);
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
     * and the branch after it reads those.
     *
     * <p>An operand may be an access, and the access is written where it stands: {@code cmp byte
     * [bx], 0x80} is what the surface says and one instruction is what it means
     * ({@code docs/ir.md} §5.4). Reading it into a register first is what the machine would
     * otherwise have to do — a load and the comparison — and the load is what a pass takes away when
     * the value it produced had no other reader ({@code i8086.pass.LoadFolding}).
     *
     * <p>What the machine does not take on the first side is a literal: {@code cmp 5, x} is not
     * something it can say, so a literal goes into a register first, and its width comes from the
     * other side.
     */
    private void selectCompare(Item.Compare compare) {
        List<List<Operand>> candidates = new ArrayList<List<Operand>>();
        Operand right = operandFor(compare.right());
        if (compare.left() instanceof Value.Memory) {
            requireRegisterAccess(((Value.Memory) compare.left()).operand(), "comparison");
            candidates.add(operands(operandFor(compare.left()), right));
        } else {
            String first;
            if (compare.left() instanceof Value.Number) {
                first = temp(typeOfComparison(compare));
                emitValue(compare.left(), first, true);
            } else {
                first = registerNameOf(compare.left());
            }
            candidates.add(operands(virtual(first, compare.position()), right));
        }

        Form best = null;
        List<Operand> written = null;
        for (List<Operand> candidate : candidates) {
            for (Form form : target.compareForms(compare.kind())) {
                List<Operand> fits = writtenOperands(form, candidate);
                if (fits != null && (best == null || form.bytes() < best.bytes())) {
                    best = form;
                    written = fits;
                }
            }
        }

        // A comparison with zero says what a test of the operand against itself says, when the
        // target promises the two leave the same flags (docs/ir.md §4.2) — and it says it without
        // the zero, so it is a byte shorter wherever a register form is shorter than an immediate
        // one. Which instruction it is, is the target's: the operand goes on both sides and the
        // forms are the ones the target has for a test. An access is not the operand for this: the
        // machine has no `test [x], [x]`, and `test [x], 0` asks a question whose answer is always
        // the same one (docs/ir.md §5.4).
        if (best != null && compare.kind() == Item.Compare.Kind.CMP
                && target.zeroComparisonIsATest() && isZero(compare.right())
                && !(compare.left() instanceof Value.Memory)) {
            String first = registerNameOf(compare.left());
            List<Operand> asTest = operands(virtual(first, compare.position()),
                    virtual(first, compare.position()));
            Form candidate = smallest(target.compareForms(Item.Compare.Kind.TEST), asTest);
            if (candidate != null && candidate.bytes() < best.bytes()) {
                out.add(new Instruction(compare.position(), candidate.mnemonic(), asTest));
                return;
            }
        }

        if (best == null) {
            throw noFormFor("a comparison", compare.position(), written == null
                    ? candidates.get(0) : written);
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
        Expansion combined = combinedBytes(apply, destination);
        if (combined != null) {
            out.addAll(combined.instructions());
            return;
        }

        // Two operations in a row that the machine does in a register of its own — `d * a / b` — are
        // one chain in that register: the first is asked for its answer where the second one works,
        // and the two copies between them are moves of a register into itself. Computing them one
        // after the other costs nothing but two instructions, and `a * b / c` is the shape sector
        // arithmetic is made of (docs/ir.md §6.1).
        Expansion chained = chainedSequences(apply, destination);
        if (chained != null) {
            requireFlagsMayBeLost(chained.keepsFlags(), apply.position(), operator, false);
            out.addAll(chained.instructions());
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

        // A right-hand side that is already a value in a register needs no register of its own: the
        // operation reads it where it is, which is what the single-operation route does, and a move
        // into a temporary would only undo that. On a machine with six registers that move is not one
        // instruction, it is one register — and in a boot loader's loop it is the register the answer
        // no longer fits in.
        Value right = leafOf(apply.right());
        if (right instanceof Value.Name) {
            emitExpression(apply.left(), destination);
            emitInPlace(operator, destination, right, Signedness.of(apply, form::typeOf),
                    apply.position(), false);
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

    /**
     * The widenings, shifts and loads whose only reader is a combine, so that emitting them is work
     * nobody wants.
     *
     * <p>Both halves come from statements that widen a byte, and the combine puts those bytes where
     * they belong — so the widening is not a value the program needs any more, and the sequence it
     * would be selected to is a move and a clear that the combine writes over. And where the byte
     * itself came from one plain load, that load can be the combine's own operand instead: the half
     * wants a byte in it, and {@code mov ah, byte [$p1]} is that byte, where loading it into a
     * register first is a load and a move. A half the program shifted up as a statement of its own
     * is the same story one step along: {@code h = eval(w shl 8)} is a count in a register and a
     * shift, and the combine's moves write the byte where the shift was putting it.
     *
     * <p>What makes leaving any of them out safe is that <b>nothing else</b> reads the value: the
     * count is of readers, the statement that defines a value does not count itself, and a value a φ
     * carries is a reader that is an item of no kind and is therefore refused rather than missed.
     *
     * <p>A load is only taken when the combine is in <b>the same block</b> and later, with nothing
     * between that could have written memory — a store, an interrupt or a block of assembly. That is
     * the same rule {@code i8086.target.RepeatedLoads} applies to the code the allocator produced,
     * asked here of the statements, because this one changes which statement the access belongs to.
     *
     * <p>A shift brings the flags with it: written as an {@code eval} it defines them, where the
     * idiom defines nothing ({@link #shiftsMayBeTaken}).
     */
    private void findConsumed(FlagLiveness flags) {
        Map<String, Set<Item>> readers = new LinkedHashMap<String, Set<Item>>();
        Map<String, Item> definedBy = new LinkedHashMap<String, Item>();
        Map<Item, Integer> position = new LinkedHashMap<Item, Integer>();
        Map<Item, Block> block = new LinkedHashMap<Item, Block>();
        Set<String> carried = new LinkedHashSet<String>();
        for (Block each : form.cfg().blocks()) {
            int at = 0;
            for (SsaStatement statement : form.statements(each)) {
                Item item = statement.item();
                block.put(item, each);
                position.put(item, Integer.valueOf(at++));
                String defined = Effects.writtenVariable(item);
                if (defined != null) {
                    definedBy.put(defined, item);
                }
                for (Effects.Occurrence occurrence : Effects.occurrences(item)) {
                    if (occurrence.written() || occurrence.name() == null) {
                        continue; // a definition is not a reader of the value it defines
                    }
                    Set<Item> found = readers.get(occurrence.name());
                    if (found == null) {
                        found = new LinkedHashSet<Item>();
                        readers.put(occurrence.name(), found);
                    }
                    found.add(item);
                }
            }
            for (Phi phi : form.phis(each)) {
                carried.addAll(phi.operands());
            }
        }

        for (Block each : form.cfg().blocks()) {
            for (SsaStatement statement : form.statements(each)) {
                Item combine = statement.item();
                Half[] halves = combinedHalves(combine);
                if (halves == null
                        || !shiftsMayBeTaken(halves, combine, flags, definedBy, readers, carried)) {
                    continue;
                }
                for (Half half : halves) {
                    if (half.shift != null) {
                        consumedShifts.add(half.shift);
                    }
                    // The widening is dead when the only thing that read it is going away: the
                    // combine itself, or the shift the combine is taking over instead.
                    Item readsTheWidening = half.shift == null ? combine : definedBy.get(half.shift);
                    if (!onlyReader(readers, carried, half.widened, readsTheWidening)) {
                        continue;
                    }
                    consumedWidenings.add(half.widened);
                    consumeByteLoad(half, definedBy, readers, carried, position, block, combine);
                }
            }
        }
    }

    /**
     * Whether every shift this combine reads may be taken over: only this combine reads the shift, and
     * the flags it defines are nobody's.
     *
     * <p>The flags are not a detail. A shift claims the flags its operation leaves and the idiom
     * cannot make that claim — it is moves — so the flags the shift leaves behind have to be dead:
     * asked of {@link FlagLiveness#after}, the direction that sees a definition of the flags as
     * something a later statement may be counting on.
     *
     * <p>All of the shape or none of it: the byte is in the high half <b>because</b> the shift put it
     * there, so a shift that stays is a shift whose word the combine would have to read as a word.
     */
    private static boolean shiftsMayBeTaken(Half[] halves, Item combine, FlagLiveness flags,
                                            Map<String, Item> definedBy,
                                            Map<String, Set<Item>> readers, Set<String> carried) {
        for (Half half : halves) {
            if (half.shift == null) {
                continue;
            }
            Item shift = definedBy.get(half.shift);
            if (shift == null || flags.after(shift)
                    || !onlyReader(readers, carried, half.shift, combine)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Takes the load a widened byte came from, when that load is the byte's only reader and the
     * combine can carry the access itself.
     */
    private void consumeByteLoad(Half half, Map<String, Item> definedBy,
                                 Map<String, Set<Item>> readers, Set<String> carried,
                                 Map<Item, Integer> position, Map<Item, Block> block,
                                 Item combine) {
        String value = widenedBytes.get(half.widened);
        Value.Memory load = value == null ? null : loadedBytes.get(value);
        Item widening = definedBy.get(half.widened);
        Item loading = value == null ? null : definedBy.get(value);
        if (load == null || widening == null || loading == null
                || !onlyReader(readers, carried, value, widening)
                || block.get(widening) != block.get(combine)
                || block.get(loading) != block.get(combine)
                || position.get(loading).intValue() > position.get(widening).intValue()
                || position.get(widening).intValue() > position.get(combine).intValue()) {
            return;
        }
        if (writesMemoryBetween(block.get(loading), loading, combine)) {
            return;
        }
        consumedLoads.add(value);
        halfAccess.put(half.widened, load);
    }

    /** Whether this value's one reader is this item, and nothing carries it as a φ operand. */
    private static boolean onlyReader(Map<String, Set<Item>> readers, Set<String> carried,
                                      String value, Item item) {
        Set<Item> found = readers.get(value);
        return !carried.contains(value) && found != null && found.size() == 1
                && found.contains(item);
    }

    /** Whether anything between two statements could have written memory. */
    private boolean writesMemoryBetween(Block block, Item from, Item to) {
        boolean seen = false;
        boolean wrote = false;
        for (SsaStatement statement : form.statements(block)) {
            if (statement.item() == to) {
                return wrote;
            }
            if (seen && touchesMemory(statement.item())) {
                wrote = true;
            }
            if (statement.item() == from) {
                seen = true;
            }
        }
        return true; // the combine was not found where it was said to be, so nothing is claimed
    }

    /**
     * Whether this statement may have written memory, or needs its access to happen where it is.
     *
     * <p>A store writes, an inline block and a machine statement may write anywhere, and a volatile
     * access is one the program needs to happen in its own place — so none of them is something a
     * load can be moved across.
     */
    private static boolean touchesMemory(Item item) {
        if (item instanceof Item.InlineAsm || item instanceof Item.Machine) {
            return true;
        }
        if (item instanceof Item.Assign
                && ((Item.Assign) item).place() instanceof Place.Memory) {
            return true;
        }
        for (Effects.Occurrence occurrence : Effects.occurrences(item)) {
            if (occurrence.isVolatile()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A half of a combine: the byte that goes in it, and what put it in the half it is in.
     *
     * <p>The byte is named by the word value some statement widened it into — {@link #widenedBytes}
     * says which byte that word is a copy of — and {@code shift} is the word value a statement of its
     * own shifted up, when the program wrote the high half as a statement rather than inside the tree.
     * Only {@link #highHalf} makes one with a shift, and only a shift makes a byte the high half, which
     * is what says the two halves do not overlap.
     */
    private static final class Half {

        private final String widened;
        private final String shift;

        Half(String widened, String shift) {
            this.widened = widened;
            this.shift = shift;
        }
    }

    /**
     * The two halves of the word a statement puts together, the high one first, or null when the
     * statement puts nothing together.
     *
     * <p>The shape is {@code (high shl 8) + low} — or the same with {@code * 256}, or with the two
     * operands the other way round, or joined with {@code |}, which says the same thing when the
     * fields cannot overlap. The high half may be shifted by a statement of its own:
     * {@code h = expr(w shl 8)} and {@code t = expr(h | v)} are the halves of
     * {@code t = expr((w shl 8) | v)} spelled out.
     *
     * <p>Only {@code expr}, because the idiom is moves and claims nothing about the flags where the
     * arithmetic it stands for does. An operation written as an {@code eval} still claims them, and for
     * two operands that are values it is a claim the pass that turns unread operations into
     * {@code expr} would have taken away ({@code i8086.pass.UnreadFlags}) — so an {@code eval} left
     * here is one whose flags are read.
     */
    private Half[] combinedHalves(Item item) {
        if (!(item instanceof Item.Assign)
                || !(((Item.Assign) item).value() instanceof Value.Expr)) {
            return null;
        }
        Expression expression = ((Value.Expr) ((Item.Assign) item).value()).expression();
        if (!(expression instanceof Expression.Apply)) {
            return null;
        }
        Expression.Apply apply = (Expression.Apply) expression;
        return combinedHalves(apply.operator(), apply.left(), apply.right());
    }

    /** The two halves of the word two operands make, the high one first, or null. */
    private Half[] combinedHalves(Operator operator, Expression left, Expression right) {
        if (!operator.equals(Operator.ADD) && !operator.equals(Operator.OR)) {
            return null;
        }
        Half high = highHalf(left);
        Half low = lowHalf(right);
        if (high == null || low == null) {
            high = highHalf(right);
            low = lowHalf(left);
        }
        if (high == null || low == null || high.widened.equals(low.widened)) {
            return null;
        }
        return new Half[] { high, low };
    }

    /**
     * The high half this expression puts in place, when it is a byte shifted up by eight: written
     * inside the tree as {@code (w shl 8)}, or by a statement of its own, {@code h = eval(w shl 8)}.
     * Null when it is neither.
     *
     * <p>The shift is the whole of what makes a high half: two bytes added or ORed with neither of
     * them shifted are two bytes in the low half, which is not the word a combine builds.
     */
    private Half highHalf(Expression expression) {
        Expression operand = shiftedUp(expression);
        if (operand != null) {
            String widened = widenedValue(operand);
            return widened == null ? null : new Half(widened, null);
        }
        String name = nameOf(expression);
        String widened = name == null ? null : shiftedHalves.get(name);
        return widened == null ? null : new Half(widened, name);
    }

    /**
     * The low half this expression is, when it is a byte some statement widened: that word's low
     * byte. Null when it is anything else, a shift included — a shifted byte is above the low half,
     * which makes it the other half and not this one.
     */
    private Half lowHalf(Expression expression) {
        String widened = widenedValue(expression);
        return widened == null ? null : new Half(widened, null);
    }

    /** The word value this expression names, when a statement widened a byte into it. */
    private String widenedValue(Expression expression) {
        String name = nameOf(expression);
        return name != null && widenedBytes.containsKey(name) ? name : null;
    }

    /** The name a leaf names, or null when the expression is not a name standing on its own. */
    private static String nameOf(Expression expression) {
        if (!(expression instanceof Expression.Leaf)) {
            return null;
        }
        Value value = ((Expression.Leaf) expression).value();
        return value instanceof Value.Name ? ((Value.Name) value).name() : null;
    }

    /** A value standing where a tree can stand: an operand of an {@code eval}, read as a leaf. */
    private static Expression leaf(Value value) {
        return new Expression.Leaf(value.position(), value);
    }

    /**
     * Two bytes put together into a word, when the machine has an idiom for it.
     *
     * <p>What makes this an idiom and not a re-association is that both halves are <b>known</b> to be
     * a byte with zeroes above it, which is a statement a program writes ({@code w = movzx x}) and
     * nothing else says: a value whose type is a byte is widened before it gets here — that is what
     * {@code xor ah, ah} is for — so being a byte is not the same thing as being known to be small.
     * With both halves known, the word they make is the low byte of one in the high half and the low
     * byte of the other in the low half, which on this machine is two moves.
     *
     * <p>A half that a statement of its own shifted up is only the combine's to take apart when the
     * shift is going away too, and whether it is was decided once, over the statements
     * ({@link #findConsumed}). Asking here is what keeps the two ends of that decision the same
     * decision: a shift that stays is a shift the combine reads as a word.
     *
     * <p>Only where nothing is reading the flags, because the idiom is moves and the arithmetic it
     * stands for defines them. The declaration of that is the target's ({@code keepsFlags}); the
     * promise that the flags are nobody's is the shape's, and it is made where the shape is
     * recognized — an {@code expr} has given them up by being written as one, and a shift is only ever
     * taken when the flags it leaves have no reader ({@link #findConsumed}).
     */
    private Expansion combinedBytes(Expression.Apply apply, String destination) {
        return combinedBytes(combinedHalves(apply.operator(), apply.left(), apply.right()),
                apply.operator(), apply.position(), destination);
    }

    /** The idiom for these halves, when there is one — or null when the shape is not the combine's. */
    private Expansion combinedBytes(Half[] halves, Operator operator, SourcePos where,
                                    String destination) {
        if (halves == null) {
            return null;
        }
        for (Half half : halves) {
            if (half.shift != null && !consumedShifts.contains(half.shift)) {
                return null;
            }
        }
        Expansion sequence = target.combineBytes(where, virtual(destination, where),
                halfOperand(halves[0], where), halfOperand(halves[1], where));
        if (sequence != null) {
            requireFlagsMayBeLost(sequence.keepsFlags(), where, operator, false);
        }
        return sequence;
    }

    /**
     * What a combine reads a half from: the access itself when the byte's own load is the combine's
     * to make, and the register the byte is in otherwise.
     */
    private Operand halfOperand(Half half, SourcePos where) {
        Value.Memory load = halfAccess.get(half.widened);
        return load == null
                ? virtual(widenedBytes.get(half.widened), where) : memory(load.operand());
    }

    /**
     * What this expression shifts up by eight, or null when it is not that: the high half of the word
     * a combine makes. Both spellings are accepted because the surface has both — {@code shl} says what
     * it is, and {@code * 256} is what a person types — and each of them says the amount its own way.
     */
    private static Expression shiftedUp(Expression expression) {
        if (!(expression instanceof Expression.Apply)) {
            return null;
        }
        Expression.Apply apply = (Expression.Apply) expression;
        Value by = literalOf(apply.right());
        return by != null && shiftsUpByEight(apply.operator(), by) ? apply.left() : null;
    }

    /** Whether this operator, applied to this operand, shifts a value up by eight. */
    private static boolean shiftsUpByEight(Operator operator, Value by) {
        long amount;
        if (operator.equals(Operator.SHIFT_LEFT)) {
            amount = 8;
        } else if (operator.equals(Operator.MULTIPLY)
                || operator.equals(Operator.MULTIPLY_UNSIGNED)
                || operator.equals(Operator.MULTIPLY_SIGNED)) {
            amount = 256;
        } else {
            return false;
        }
        return by instanceof Value.Number && ((Value.Number) by).value() == amount;
    }

    /** The register a leaf already names, or null when it is not one. */
    private static String sourceRegister(Expression expression) {
        return nameOf(expression);
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

    /**
     * Two operations in a row that the machine does in a register of its own, as one chain.
     *
     * <p>The shape is {@code d * a / b}: both operations want their first operand where the machine
     * keeps it and leave their answer there, so the answer of the first is asked for in the register
     * the second one works in. The copies in and out of that register are then moves of a register
     * into itself, and the allocator drops those.
     *
     * <p>What it needs is a target that says where an operator's answer goes
     * ({@link Target#answerRegister}); a target that says nothing gets the two operations one after
     * the other, which is what this compiler did before. The left side must be one of those
     * operations itself and all three operands leaves, because a chain is a chain of operands: a tree
     * anywhere in it is computed into a register of its own, which is a different question.
     */
    private Expansion chainedSequences(Expression.Apply apply, String destination) {
        Operator operator = apply.operator();
        String answer = target.answerRegister(operator);
        if (answer == null || !(apply.left() instanceof Expression.Apply)) {
            return null;
        }
        Expression.Apply inner = (Expression.Apply) apply.left();
        if (!target.forms(inner.operator()).isEmpty() || !target.forms(operator).isEmpty()) {
            return null;
        }
        Value first = leafOf(inner.left());
        Value second = leafOf(inner.right());
        Value third = leafOf(apply.right());
        if (first == null || second == null || third == null) {
            return null;
        }
        SourcePos where = apply.position();
        Operand into = new Operand.Name(where, answer);
        Boolean innerSigned = signedness(inner.operator(), Arrays.asList(first, second), destination);
        Boolean outerSigned = signedness(operator, Arrays.asList(third), destination);
        Expansion one = declaredSequence(inner.operator(), into, operandOf(first), operandOf(second),
                innerSigned, where);
        Expansion two = declaredSequence(operator, virtual(destination, where), into, operandOf(third),
                outerSigned, where);
        if (one == null || two == null) {
            return null;
        }
        List<Instruction> instructions = new ArrayList<Instruction>(one.instructions());
        instructions.addAll(two.instructions());
        return new Expansion(instructions, one.keepsFlags() && two.keepsFlags());
    }

    /**
     * Asks the target whether a divisor written out makes the division cheaper, and passes on what it
     * says — nothing when the divisor is a value, or when the target has no trick for it.
     *
     * <p>{@code signed} is the answer to a different question that has to be settled first: an
     * arithmetic shift and the machine's division disagree about negative numbers, so the target is
     * told which one this is ({@code docs/ir.md} §6.2).
     */
    private Expansion divideByConstant(Operator operator, Operand destination, Operand source,
                                       Operand divisor, boolean signed, SourcePos where) {
        if (!operator.divides() || !(divisor instanceof Operand.Number)) {
            return null;
        }
        return target.divideByConstant(where, destination, source,
                ((Operand.Number) divisor).value(), signed, operator == Operator.REMAINDER);
    }

    /**
     * One operation the machine does in a register of its own, as the target declares it.
     *
     * <p>The destination is an operand rather than a name, because in a chain it is a register the
     * selector wrote by hand rather than a value: what comes out of the first operation goes into the
     * second one, and neither the middle nor the end of a chain is a name the program wrote.
     */
    private Expansion declaredSequence(Operator operator, Operand destination, Operand left,
                                       Operand right, Boolean signed, SourcePos where) {
        if (operator == Operator.MULTIPLY || operator == Operator.MULTIPLY_UNSIGNED
                || operator == Operator.MULTIPLY_SIGNED) {
            return target.multiply(where, destination, left, right, Boolean.TRUE.equals(signed));
        }
        return target.divide(where, destination, left, right, Boolean.TRUE.equals(signed),
                operator == Operator.REMAINDER);
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

        // A divisor the program wrote out is one the compiler can look at, and a power of two of one
        // is a shift or a mask rather than a division: fewer bytes, and none of the registers the
        // division insists on (docs/ir.md §6.2).
        Expansion byConstant = divideByConstant(operator, target0, left, right, signed, where);
        if (byConstant != null) {
            return byConstant;
        }

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

        // The same question the two-operand path asks, for the case where the left side is already in
        // the destination: a constant divisor may be cheaper than the division (docs/ir.md §6.2).
        Expansion byConstant = divideByConstant(operator, inPlace, inPlace, right, isSigned, where);
        if (byConstant != null) {
            return byConstant;
        }

        if (right instanceof Operand.Number) {
            // The machine takes a register, so the literal needs one; the sequence says which, and it
            // names it as a register everywhere it is used. Writing the value there instead would be
            // an operand the allocator is free to place in another register than the copy wrote —
            // which is a division by whatever happened to be in that one.
            List<Instruction> withLiteral = new ArrayList<Instruction>();
            withLiteral.add(new Instruction(where, "mov",
                    operands(new Operand.Name(where, LITERAL_SCRATCH), right)));
            Expansion rest = multiplies
                    ? target.multiply(where, inPlace, inPlace,
                    new Operand.Name(where, LITERAL_SCRATCH), isSigned)
                    : target.divide(where, inPlace, inPlace,
                    new Operand.Name(where, LITERAL_SCRATCH), isSigned,
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
