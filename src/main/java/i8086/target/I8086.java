package i8086.target;

import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Numbers;
import i8086.asm.Operand;
import i8086.asm.Size;
import i8086.ir.Comparison;
import i8086.ir.Item;
import i8086.ir.Operator;
import i8086.isel.Selection;
import i8086.ssa.SsaForm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Intel 8086.
 *
 * <p>Everything here is a fact about this processor, and it is here rather than
 * in a pass or a parser so that there is exactly one place to look for it and
 * one place to change it ({@code AGENTS.md} invariant 2).
 */
public final class I8086 implements Target {

    /**
     * The register names, in the order a person writes them: eight-bit first,
     * then sixteen-bit, then the segment registers.
     */
    private static final Set<String> REGISTERS = names(
            "al", "cl", "dl", "bl", "ah", "ch", "dh", "bh",
            "ax", "cx", "dx", "bx", "sp", "bp", "si", "di",
            "es", "cs", "ss", "ds");

    private static final Set<String> SEGMENT_REGISTERS = names("es", "cs", "ss", "ds");

    /**
     * Every branch word, mapped to the condition it names.
     *
     * <p>The 8086 has sixteen conditions and thirty spellings of them: {@code jb},
     * {@code jc} and {@code jnae} are the same test of the carry flag, written
     * for three different readers, and {@code je} and {@code jz} are the same
     * test of the zero flag. The first argument is the spelling the compiler
     * treats as canonical, which is the one the IR printer writes back, and the
     * rest are accepted and normalised onto it.
     *
     * <p>A condition is not a register: {@code jcxz} tests a register and not a
     * flag, so it is not here ({@code docs/ir.md} §11 leaves it open).
     */
    private static final Map<String, String> CONDITIONS = conditionWords();

    private static Map<String, String> conditionWords() {
        Map<String, String> words = new LinkedHashMap<String, String>();
        condition(words, "jo");
        condition(words, "jno");
        condition(words, "js");
        condition(words, "jns");
        condition(words, "jz", "je");
        condition(words, "jnz", "jne");
        condition(words, "jp", "jpe");
        condition(words, "jnp", "jpo");
        condition(words, "jc", "jb", "jnae");
        condition(words, "jnc", "jae", "jnb");
        condition(words, "ja", "jnbe");
        condition(words, "jbe", "jna");
        condition(words, "jg", "jnle");
        condition(words, "jge", "jnl");
        condition(words, "jl", "jnge");
        condition(words, "jle", "jng");
        return Collections.unmodifiableMap(words);
    }

    private static void condition(Map<String, String> words, String canonical, String... aliases) {
        words.put(canonical, canonical);
        for (String alias : aliases) {
            words.put(alias, canonical);
        }
    }

    /**
     * The opposite of each condition, in both directions: the pairs are what the
     * flag tests come in.
     */
    private static final Map<String, String> OPPOSITES = opposites();

    private static Map<String, String> opposites() {
        Map<String, String> pairs = new LinkedHashMap<String, String>();
        opposite(pairs, "jo", "jno");
        opposite(pairs, "js", "jns");
        opposite(pairs, "jz", "jnz");
        opposite(pairs, "jp", "jnp");
        opposite(pairs, "jc", "jnc");
        opposite(pairs, "ja", "jbe");
        opposite(pairs, "jg", "jle");
        opposite(pairs, "jge", "jl");
        return Collections.unmodifiableMap(pairs);
    }

    private static void opposite(Map<String, String> pairs, String one, String other) {
        pairs.put(one, other);
        pairs.put(other, one);
    }

    /**
     * The forms that set the flags from two values.
     *
     * <p>{@code cmp} subtracts without keeping the result and {@code test} ANDs
     * without keeping it; both are two bytes when both operands are registers. Either side may be
     * an access instead — {@code cmp byte [bx], 0x80} is one instruction here, and reading the byte
     * into a register first costs more than the comparison does ({@code docs/ir.md} §5.4) — but not
     * both at once: this machine has no instruction whose two operands are both in memory, which is
     * a shape a program can write and the target simply does not answer.
     */
    private static final List<Form> COMPARE_FORMS = Collections.unmodifiableList(
            Arrays.asList(new Form("cmp", shapes(Shape.REGISTER, Shape.REGISTER), 2),
                    new Form("cmp", shapes(Shape.REGISTER, Shape.IMMEDIATE), 3),
                    new Form("cmp", shapes(Shape.REGISTER, Shape.MEMORY), 2),
                    new Form("cmp", shapes(Shape.MEMORY, Shape.REGISTER), 2),
                    new Form("cmp", shapes(Shape.MEMORY, Shape.IMMEDIATE), 3)));

    private static final List<Form> TEST_FORMS = Collections.unmodifiableList(
            Arrays.asList(new Form("test", shapes(Shape.REGISTER, Shape.REGISTER), 2),
                    new Form("test", shapes(Shape.REGISTER, Shape.IMMEDIATE), 3),
                    new Form("test", shapes(Shape.REGISTER, Shape.MEMORY), 2),
                    new Form("test", shapes(Shape.MEMORY, Shape.REGISTER), 2),
                    new Form("test", shapes(Shape.MEMORY, Shape.IMMEDIATE), 3)));

    /**
     * Reading a value out of memory, and writing one back.
     *
     * <p>One instruction, {@code mov}, and the shape of the operand is what decides
     * which addressing mode it is: an address with no register in it is a direct
     * reference the assembler fills in, and one with a register is what the register
     * holds. The bytes are the smallest each can be — {@code mov ax, [bx]} is two —
     * and which encoding is used, displacement and all, is the assembler's decision
     * ({@code README.md}, step 9).
     */
    private static final List<Form> LOAD_FORMS = Collections.unmodifiableList(
            Arrays.asList(new Form("mov", shapes(Shape.REGISTER, Shape.MEMORY), 2)));

    private static final List<Form> STORE_FORMS = Collections.unmodifiableList(
            Arrays.asList(new Form("mov", shapes(Shape.MEMORY, Shape.REGISTER), 2)));

    private static final List<Form> STORE_LITERAL_FORMS = Collections.unmodifiableList(
            Arrays.asList(new Form("mov", shapes(Shape.MEMORY, Shape.IMMEDIATE), 4)));

    /**
     * What this machine takes away behind the allocator's back.
     *
     * <p>{@code mul} and {@code div} leave part of their answer in {@code DX} whether
     * anybody asked or not, and an instruction whose first operand is a register the
     * selector wrote by hand — {@code mov cl, 4} — writes a register no value was
     * given. Both are clobbers, and both are facts about this processor.
     */
    @Override
    public Set<String> clobbers(Instruction instruction) {
        Set<String> destroyed = new LinkedHashSet<String>();
        Set<String> implicit = IMPLICIT_CLOBBERS.get(instruction.mnemonic());
        if (implicit != null) {
            destroyed.addAll(implicit);
        }
        if (instruction.operands().isEmpty() || !writesFirstOperand(instruction.mnemonic())) {
            return destroyed;
        }
        Operand first = instruction.operands().get(0);
        if (first instanceof Operand.Name) {
            // A name the selector wrote itself: a register, and one the allocator
            // never handed out. A label is a name too, and the register that holds
            // it is none, which is what the null says.
            String register = valueRegisterOf(((Operand.Name) first).name());
            if (register != null) {
                destroyed.add(register);
            }
        }
        return destroyed;
    }

    /**
     * Whether the first operand is where the answer goes.
     *
     * <p>On this machine that is the rule and the exceptions are the interesting
     * part: a comparison answers with the flags and writes nothing, a jump and a
     * {@code ret} answer with where they went, and {@code push} reads its operand
     * rather than writing it.
     */
    private static boolean writesFirstOperand(String mnemonic) {
        return !READS_FIRST_OPERAND.contains(mnemonic) && !BRANCHES.contains(mnemonic);
    }

    /**
     * The sixteen-bit register an eight-bit name is half of, and a register of its own otherwise.
     *
     * <p>Nothing here can name half a register, so writing {@code cl} destroys {@code cx} as far as
     * anything outside this class is concerned, and so does reading it: a value in {@code cx} is
     * part of the {@code cl} a read of it is about. A name that is not a register at all — a label —
     * has no register holding it, and neither do the registers no value is ever given: the segment
     * registers, the stack pointer and {@code bp}.
     */
    @Override
    public String valueRegisterOf(String name) {
        if (VALUE_REGISTERS.contains(name)) {
            return name;
        }
        return HALVES.get(name);
    }

    /**
     * Instructions that write no register at all, or only read the operand written first.
     *
     * <p>{@code mul}, {@code imul}, {@code div} and {@code idiv} read their operand: what they write
     * is the pair the machine keeps its answer in, and that is stated as an implicit clobber instead.
     * Saying otherwise costs registers — a value is kept out of a register nothing was going to touch
     * — and it is not what the instruction does.
     */
    private static final Set<String> READS_FIRST_OPERAND = names(
            "cmp", "test", "push", "mul", "imul", "div", "idiv");

    /** Instructions that go somewhere, and so write nothing. */
    private static final Set<String> BRANCHES = names("jmp", "ret", "hlt", "nop", "cli", "sti",
            "iret", "int", "into");

    /**
     * What an instruction destroys with no operand saying so.
     *
     * <p>{@code mul} and {@code div} are the reason this table exists: the product
     * or the quotient arrives in {@code AX}, the high half or the remainder in
     * {@code DX}, and neither is an operand of the instruction the writer wrote.
     */
    private static final Map<String, Set<String>> IMPLICIT_CLOBBERS = implicitClobbers();

    private static Map<String, Set<String>> implicitClobbers() {
        Map<String, Set<String>> table = new LinkedHashMap<String, Set<String>>();
        table.put("mul", names("ax", "dx"));
        table.put("imul", names("ax", "dx"));
        table.put("div", names("ax", "dx"));
        table.put("idiv", names("ax", "dx"));
        // The string operations move the index registers as they go.
        table.put("lodsb", names("ax", "si"));
        table.put("lodsw", names("ax", "si"));
        table.put("stosb", names("di"));
        table.put("stosw", names("di"));
        table.put("movsb", names("si", "di"));
        table.put("movsw", names("si", "di"));
        // The two conversions this machine has: both work on {@code ax} and nowhere else.
        table.put("cbw", names("ax"));
        table.put("cwd", names("ax", "dx"));
        return Collections.unmodifiableMap(table);
    }

    /**
     * Which sixteen-bit register holds each eight-bit name.
     *
     * <p>The mapping is one-way on purpose: a value lives in a whole register here,
     * and the only thing worth knowing about {@code cl} is that {@code cx} holds it.
     */
    private static final Map<String, String> HALVES = halves();

    private static Map<String, String> halves() {
        Map<String, String> table = new LinkedHashMap<String, String>();
        halve(table, "ax", "al", "ah");
        halve(table, "cx", "cl", "ch");
        halve(table, "dx", "dl", "dh");
        halve(table, "bx", "bl", "bh");
        return Collections.unmodifiableMap(table);
    }

    private static void halve(Map<String, String> table, String register, String low, String high) {
        table.put(low, register);
        table.put(high, register);
    }

    /**
     * How many single shifts are worth replacing with {@code mov cl, n; shl r, cl}.
     *
     * <p>Four bytes buys any count: two for the count and two for the shift. Three
     * single shifts cost six, so three is where the trade starts to pay. What it
     * costs beyond bytes is that {@code cx} is destroyed, which the allocator may
     * have to work around — a cost this cannot see, and the reason the threshold is
     * where the bytes alone decide it.
     */
    private static final int COUNT_FROM_REGISTER = 3;

    private static Set<String> names(String... names) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(names)));
    }

    /**
     * The mnemonics that may begin a statement, and the operation each one names
     * ({@code docs/ir.md} §7.3).
     *
     * <p>Every word here is a second spelling of an operation the surface already
     * has, and the test is not whether the machine's instruction matches the
     * surface's operation perfectly — {@code not} and {@code rol} do not — but
     * whether the two spellings mean the same thing. {@code not d} and
     * {@code d = eval(~d)} are one statement; {@code inc d} and {@code d = eval(d + 1)}
     * are two different programs, and that is why {@code inc} is in
     * {@link #STATEMENT_PROBLEMS} instead.
     *
     * <p>The words that are already operators — {@code adc}, {@code shl}, {@code mul}
     * and the rest — are listed here too rather than looked up, so that this table is
     * the whole answer to "what may begin a statement" and there is no second place to
     * look.
     */
    private static final Map<String, Operator> STATEMENT_WORDS = statementWordTable();

    private static Map<String, Operator> statementWordTable() {
        Map<String, Operator> words = new LinkedHashMap<String, Operator>();
        // The words the surface spells with a symbol, then the ones it already spells as
        // words: 'adc' and 'shl' are operators with a single spelling, and 'add' and
        // 'and' are second spellings of '+' and '&'.
        statement(words, Operator.ADD, "add");
        statement(words, Operator.SUBTRACT, "sub");
        statement(words, Operator.AND, "and");
        statement(words, Operator.OR, "or");
        statement(words, Operator.XOR, "xor");
        statement(words, Operator.COMPLEMENT, "not");
        statement(words, Operator.NEGATE, "neg");
        statement(words, Operator.ADD_WITH_CARRY, "adc");
        statement(words, Operator.SUBTRACT_WITH_BORROW, "sbb");
        statement(words, Operator.SHIFT_LEFT, "shl");
        statement(words, Operator.SHIFT_RIGHT, "shr");
        statement(words, Operator.SHIFT_ARITHMETIC, "sar");
        statement(words, Operator.ROTATE_LEFT, "rol");
        statement(words, Operator.ROTATE_RIGHT, "ror");
        statement(words, Operator.ROTATE_LEFT_THROUGH_CARRY, "rcl");
        statement(words, Operator.ROTATE_RIGHT_THROUGH_CARRY, "rcr");
        statement(words, Operator.MULTIPLY_UNSIGNED, "mul");
        statement(words, Operator.MULTIPLY_SIGNED, "imul");
        statement(words, Operator.DIVIDE_UNSIGNED, "div");
        statement(words, Operator.DIVIDE_SIGNED, "idiv");
        return Collections.unmodifiableMap(words);
    }

    private static void statement(Map<String, Operator> words, Operator operator, String word) {
        words.put(word, operator);
    }

    /**
     * The mnemonics that look like statements and are not, with the reason, so that the
     * refusal can say why instead of listing what is allowed ({@code docs/ir.md} §7.3).
     *
     * <p>{@code inc} is the one worth reading: it is one byte where {@code add} is
     * three, and it is shorter precisely because it does not touch the carry — which is
     * the same fact the form table records in {@link Form#keepsFlags()}, and the same
     * reason it cannot be a spelling of {@code d = eval(d + 1)}.
     */
    private static final Map<String, String> STATEMENT_PROBLEMS = statementProblems();

    /**
     * The words that are a statement at one operand count and not at another.
     *
     * <p>{@code mul d, s} is the surface's operation and {@code mul r} is the machine's
     * one-operand form, which reads and writes {@code ax} and {@code dx} behind the
     * writer's back. So the count is part of the question and not part of the answer:
     * it is what the caller passes in ({@link Target#statementProblem(String, int)}).
     */
    private static final Map<String, String> ONE_OPERAND_PROBLEMS = oneOperandProblems();

    private static Map<String, String> statementProblems() {
        Map<String, String> problems = new LinkedHashMap<String, String>();
        for (String word : Arrays.asList("inc", "dec")) {
            problems.put(word, "'" + word + "' leaves CF alone where 'd = eval(d + 1)' defines "
                    + "it, so the two are different programs and the difference is one a reader "
                    + "would not see: write the 'eval' form, and where the flags turn out to "
                    + "matter to nobody, the compiler reaches the one-byte " + word + " by itself "
                    + "(docs/ir.md §7.3)");
        }
        for (String word : Arrays.asList("cwd", "cbw")) {
            problems.put(word, "'" + word + "' works on ax (and dx) with no operand saying so, and "
                    + "the surface has no operation for it (docs/ir.md §7.3)");
        }
        problems.put("xchg", "'xchg' does two writes at once, which is not one operation: write "
                + "the two assignments, or keep it in an inline block (docs/ir.md §7.3, §9)");
        problems.put("lea", "'lea' is an address and an addressing mode, not an operation: a "
                + "label's address is written 'p = msg', and a computed one has no surface form "
                + "yet (docs/ir.md §5.3, §12 item 12)");
        for (String word : Arrays.asList("push", "pop", "in", "out", "int", "into", "iret",
                "hlt", "cli", "sti", "lahf", "sahf", "pushf", "popf", "loop", "jcxz",
                "movsb", "movsw", "stosb", "stosw", "lodsb", "lodsw")) {
            problems.put(word, "'" + word + "' is a target operation the surface has no spelling "
                    + "for yet, so it is written in an inline block (docs/ir.md §11, §9)");
        }
        return Collections.unmodifiableMap(problems);
    }

    private static Map<String, String> oneOperandProblems() {
        Map<String, String> problems = new LinkedHashMap<String, String>();
        for (String word : Arrays.asList("mul", "imul", "div", "idiv")) {
            problems.put(word, "the one-operand '" + word + "' "
                    + "multiplies or divides what is in ax and leaves part of its answer in dx, "
                    + "which is not the operation '" + word + "' names here: that one takes two "
                    + "operands and names its destination (docs/ir.md §7.3, §12 item 12)");
        }
        return Collections.unmodifiableMap(problems);
    }

    @Override
    public Operator statementOperator(String word) {
        return STATEMENT_WORDS.get(word);
    }

    @Override
    public List<String> statementWords() {
        return Collections.unmodifiableList(new ArrayList<String>(STATEMENT_WORDS.keySet()));
    }
    @Override
    public String statementProblem(String word, int operands) {
        String problem = STATEMENT_PROBLEMS.get(word);
        if (problem != null) {
            return problem;
        }
        return operands == 1 ? ONE_OPERAND_PROBLEMS.get(word) : null;
    }

    /**
     * The machine's operations that are statements of their own, and what each
     * destroys when the author does not say ({@code docs/ir.md} §11).
     *
     * <p>{@code int} takes a vector and destroys everything the allocator hands out:
     * a handler is code this module has never seen. {@code iret} restores the flags
     * from the stack, so the surface cannot say what they are afterwards. The other
     * four touch neither the general registers nor the arithmetic flags.
     */
    private static final Map<String, Integer> MACHINE_STATEMENTS = machineStatementTable();

    private static Map<String, Integer> machineStatementTable() {
        Map<String, Integer> statements = new LinkedHashMap<String, Integer>();
        statements.put("int", Integer.valueOf(1));
        statements.put("hlt", Integer.valueOf(0));
        statements.put("cli", Integer.valueOf(0));
        statements.put("sti", Integer.valueOf(0));
        statements.put("nop", Integer.valueOf(0));
        statements.put("iret", Integer.valueOf(0));
        return Collections.unmodifiableMap(statements);
    }

    @Override
    public Map<String, Integer> machineStatements() {
        return MACHINE_STATEMENTS;
    }

    @Override
    public List<String> machineClobbers(String mnemonic) {
        if (mnemonic.equals("int")) {
            List<String> everything = new ArrayList<String>(valueRegisters());
            everything.add("flags");
            return Collections.unmodifiableList(everything);
        }
        if (mnemonic.equals("iret")) {
            return Collections.singletonList("flags");
        }
        return Collections.emptyList();
    }

    /**
     * The registers a value may live in, in the order the allocator should use
     * them up.
     *
     * <p>{@code sp} is the stack. {@code bp} is left out because it is where a
     * frame pointer will go, and because taking it now would mean giving it back
     * later. The rest are free, in an order that puts the ones with fewest other
     * duties first: {@code ax} and {@code dx} are where multiplication and
     * division insist on their operands, and {@code bx}, {@code si} and
     * {@code di} are the only registers that can address memory.
     */
    private static final List<String> VALUE_REGISTERS = Collections.unmodifiableList(
            Arrays.asList("ax", "cx", "dx", "bx", "si", "di"));

    /**
     * The registers a {@code movreg} statement may write, and the only ones it may write: the
     * machine state a value cannot live in ({@code docs/ir.md} §8.1).
     *
     * <p>The rule is what the list means, not the list itself: a standalone write to a register is
     * safe exactly when no value can be in that register, because otherwise the write would have to
     * survive until some later statement that reads it — which is pinning, and a different
     * question ({@code docs/ir.md} §12 item 12). So these are the machine's registers that the
     * allocator never hands out: the three segment registers this machine can write, the stack
     * pointer, and {@code bp}, which is left out of the allocator's classes on purpose.
     *
     * <p>{@code cs} is not here: it says where the program is running, so changing it is a jump
     * rather than a move. The registers a <em>value</em> can live in are not here either — writing
     * one is the {@code with} clause of the statement that uses it, which does the write and the
     * read inside one item and needs nothing pinned.
     */
    private static final List<String> STATE_REGISTERS = Collections.unmodifiableList(
            Arrays.asList("ds", "es", "ss", "sp", "bp"));

    /**
     * The low half of each register that has one, which is where a byte value lives
     * ({@code docs/ir.md} §3.2).
     *
     * <p>Four registers and not six: {@code si}, {@code di} and {@code bp} have no byte half on
     * this machine, so a byte value cannot live there at all. The high halves — {@code ah} and its
     * neighbours — are a third place a byte can be, and they are deliberately not on this list:
     * they are not where a value lives, they are the register a machine statement is given (§11).
     */
    private static final Map<String, String> LOW_HALVES = lowHalves();

    private static Map<String, String> lowHalves() {
        Map<String, String> table = new LinkedHashMap<String, String>();
        lowHalf(table, "ax", "al");
        lowHalf(table, "cx", "cl");
        lowHalf(table, "dx", "dl");
        lowHalf(table, "bx", "bl");
        return Collections.unmodifiableMap(table);
    }

    private static void lowHalf(Map<String, String> table, String register, String low) {
        table.put(register, low);
    }

    /** The register a segment register is loaded through, since it takes no immediate. */
    private static final String SEGMENT_SCRATCH = "ax";

    /**
     * Where this machine does its multiplying and dividing, and where the answer arrives.
     *
     * <p>Named here rather than written out a third time in the sequences below, because
     * {@link #answerRegister} has to tell the truth about the same register the sequences use.
     */
    private static final String MULTIPLY_ANSWER = "ax";

    /** Where the other half of that answer arrives: the remainder, and the high half of a product. */
    private static final String REMAINDER_ANSWER = "dx";

    /**
     * The registers that can be inside the brackets.
     *
     * <p>These three and no others: {@code [ax]}, {@code [cx]} and {@code [dx]} are
     * not things this machine can say. {@code bp} can address memory too, but it
     * reads through {@code SS} rather than {@code DS}, so using it as a general
     * address register would quietly change which segment a program touches.
     *
     * <p>What this means for the allocator is that a value used as an address has a
     * smaller set of registers to live in than a value that is only ever computed
     * with, and a value used as both has to be one of these three.
     */
    private static final List<String> ADDRESS_REGISTERS = Collections.unmodifiableList(
            Arrays.asList("bx", "si", "di"));

    /**
     * What the machine has for each operator, and what each one costs.
     *
     * <p>Smaller is better, so the small ones are the ones that do less:
     * {@code inc} is one byte where {@code add} is three, and it is smaller
     * because it leaves the carry alone. It is also only {@code add 1}, which the
     * form says rather than selection having to know it.
     *
     * <p>The byte counts are the smallest encoding each form can have. Which
     * encoding is used is the assembler's decision ({@code README.md}, step 9),
     * so these are what selection needs to choose between forms and no more.
     */
    private static final Map<Operator, List<Form>> FORMS = forms();

    private static Map<Operator, List<Form>> forms() {
        Map<Operator, List<Form>> table = new LinkedHashMap<Operator, List<Form>>();
        binary(table, Operator.ADD, "add");
        binary(table, Operator.SUBTRACT, "sub");
        binary(table, Operator.AND, "and");
        binary(table, Operator.OR, "or");
        binary(table, Operator.XOR, "xor");

        // add 1 and subtract 1 have shorter forms, and shorter because they do
        // not touch the carry.
        table.get(Operator.ADD).add(new Form("inc", registers(1), Long.valueOf(1), false, 1));
        table.get(Operator.SUBTRACT).add(new Form("dec", registers(1), Long.valueOf(1), false, 1));

        table.put(Operator.COMPLEMENT, one(new Form("not", registers(1), 2)));

        // NEG is subtraction from zero, and it is two bytes where building the zero
        // and subtracting it is seven. Its flags are the subtraction's, so nothing
        // is given up by preferring it (docs/ir.md §5.5).
        table.put(Operator.NEGATE, one(new Form("neg", registers(1), 2)));
        shifts(table, Operator.SHIFT_LEFT, "shl");
        shifts(table, Operator.SHIFT_RIGHT, "shr");
        shifts(table, Operator.SHIFT_ARITHMETIC, "sar");
        return Collections.unmodifiableMap(table);
    }

    private static void binary(Map<Operator, List<Form>> table, Operator operator, String mnemonic) {
        List<Form> forms = new ArrayList<Form>();
        forms.add(new Form(mnemonic, shapes(Shape.REGISTER, Shape.REGISTER), 2));
        forms.add(new Form(mnemonic, shapes(Shape.REGISTER, Shape.IMMEDIATE), 3));
        table.put(operator, forms);
    }

    /**
     * A shift by one is one instruction and keeps the flags. Anything else has to
     * be repeated, because the 8086 has no shift by an immediate that is not one
     * ({@code docs/ir.md} §5.6), and repeating it does not leave the same flags.
     * A count in a register needs {@code cl}, which is the implicit-operand work
     * that is not done yet.
     */
    private static void shifts(Map<Operator, List<Form>> table, Operator operator, String mnemonic) {
        List<Form> forms = new ArrayList<Form>();
        forms.add(new Form(mnemonic, shapes(Shape.REGISTER, Shape.IMMEDIATE), Long.valueOf(1),
                true, 2));
        table.put(operator, forms);
    }

    private static List<Form> one(Form form) {
        List<Form> forms = new ArrayList<Form>();
        forms.add(form);
        return forms;
    }

    private static List<Shape> registers(int count) {
        List<Shape> shapes = new ArrayList<Shape>(count);
        for (int i = 0; i < count; i++) {
            shapes.add(Shape.REGISTER);
        }
        return shapes;
    }

    private static List<Shape> shapes(Shape... shapes) {
        return Arrays.asList(shapes);
    }

    @Override
    public String name() {
        return "8086";
    }

    @Override
    public boolean isRegister(String name) {
        return REGISTERS.contains(name);
    }

    @Override
    public boolean isSegmentRegister(String name) {
        return SEGMENT_REGISTERS.contains(name);
    }

    @Override
    public String condition(String word) {
        return CONDITIONS.get(word);
    }

    @Override
    public List<String> conditions() {
        // The canonical spellings in the order they were declared: the map holds
        // each of them, and a canonical word is the one that maps to itself.
        List<String> canonical = new ArrayList<String>();
        for (Map.Entry<String, String> entry : CONDITIONS.entrySet()) {
            if (entry.getKey().equals(entry.getValue())) {
                canonical.add(entry.getKey());
            }
        }
        return Collections.unmodifiableList(canonical);
    }

    @Override
    public List<String> valueRegisters() {
        return VALUE_REGISTERS;
    }

    @Override
    public List<String> addressRegisters() {
        return ADDRESS_REGISTERS;
    }

    @Override
    public String byteRegister(String register) {
        return LOW_HALVES.get(register);
    }

    @Override
    public int registerBytes(String register) {
        if (HALVES.containsKey(register)) {
            return 1; // an eight-bit half: al, ah, and their neighbours
        }
        return REGISTERS.contains(register) ? 2 : 0;
    }

    @Override
    public List<Form> loadForms() {
        return LOAD_FORMS;
    }

    @Override
    public List<Form> storeForms() {
        return STORE_FORMS;
    }

    @Override
    public List<Form> storeLiteralForms() {
        return STORE_LITERAL_FORMS;
    }

    @Override
    public String negate(String condition) {
        String opposite = OPPOSITES.get(condition);
        if (opposite == null) {
            throw new IllegalArgumentException("no condition is called '" + condition + "'");
        }
        return opposite;
    }

    @Override
    public String conditionFor(Comparison comparison, boolean signed) {
        switch (comparison) {
            case EQUAL:
                return "jz";
            case NOT_EQUAL:
                return "jnz";
            case LESS:
                return signed ? "jl" : "jc";
            case LESS_OR_EQUAL:
                return signed ? "jle" : "jbe";
            case GREATER:
                return signed ? "jg" : "ja";
            default:
                // Unsigned "not below" is the carry being clear, which is the
                // same test as "not carry" — the canonical word for it.
                return signed ? "jge" : "jnc";
        }
    }

    @Override
    public String jumpMnemonic() {
        return "jmp";
    }

    /**
     * Three instructions leave and everything else carries on.
     *
     * <p>{@code ret} returns, {@code iret} returns from an interrupt and {@code hlt}
     * waits for one; whether it is ever woken is not something this module can know, so
     * nothing after a halt is reached by falling into it. {@code int} is not one of
     * them: a handler returns, and a handler that does not is a program that never comes
     * back — which is the same thing the machine does either way
     * ({@code docs/ir.md} §11).
     */
    @Override
    public boolean fallsThrough(String mnemonic) {
        if (mnemonic.equals("ret") || mnemonic.equals("hlt") || mnemonic.equals("iret")) {
            return false;
        }
        return Target.super.fallsThrough(mnemonic);
    }

    @Override
    public List<Form> compareForms(Item.Compare.Kind kind) {
        return kind == Item.Compare.Kind.TEST ? TEST_FORMS : COMPARE_FORMS;
    }

    /**
     * Comparing with zero and testing the operand against itself leave the same answer to every
     * question this machine can ask about the flags.
     *
     * <p>{@code test r, r} clears CF and OF, as subtracting zero does, and ZF, SF and PF come from
     * the operand either way — {@code r - 0} is {@code r} and {@code r & r} is {@code r}. The
     * auxiliary carry is the one flag the two do not promise the same thing about, and no condition
     * this machine has reads it: {@code CONDITIONS} holds carry, zero, sign, parity and overflow
     * tests and nothing else. So a comparison with zero can be written without the zero, which is a
     * byte shorter wherever the immediate form is.
     */
    @Override
    public boolean zeroComparisonIsATest() {
        return true;
    }

    @Override
    public List<Form> forms(Operator operator) {
        List<Form> forms = FORMS.get(operator);
        return forms == null ? Collections.<Form>emptyList() : forms;
    }

    /**
     * Where this machine leaves the answer of a multiplication or a division.
     *
     * <p>{@code mul} and {@code div} are the operations this machine has no ordinary form for: the
     * multiplicand or the dividend goes into {@code ax}, and the answer arrives there too — the
     * quotient in {@code ax} and the remainder in {@code dx}, which is what {@code %} asks for. Two
     * of them in a row are then one chain in {@code ax}, with nothing copied between them
     * ({@code docs/ir.md} §6.1).
     */
    @Override
    public String answerRegister(Operator operator) {
        switch (operator) {
            case MULTIPLY:
            case MULTIPLY_UNSIGNED:
            case MULTIPLY_SIGNED:
            case DIVIDE:
            case DIVIDE_UNSIGNED:
            case DIVIDE_SIGNED:
                return MULTIPLY_ANSWER;
            case REMAINDER:
                return REMAINDER_ANSWER;
            default:
                return null;
        }
    }

    /**
     * {@code mov ax, left; mul right; mov destination, ax}.
     *
     * <p>The machine multiplies what is in {@code AX} by the operand and leaves the
     * low half in {@code AX} with the high half in {@code DX} — which is why the low
     * half is the whole answer here: the surface says {@code *} truncates to the
     * operand width ({@code docs/ir.md} §6.1), and anything wider than that is the
     * writer's business to spell out.
     */
    @Override
    public Expansion multiply(SourcePos where, Operand destination, Operand left, Operand right,
                              boolean signed) {
        if (left == null || right == null) {
            return null;
        }
        if (right instanceof Operand.Number && !(left instanceof Operand.Number)) {
            Operand swap = left;
            left = right;
            right = swap;
        }
        if (right instanceof Operand.Number) {
            return null; // multiplying two literals is not this target's to do
        }
        List<Instruction> instructions = new ArrayList<Instruction>();
        instructions.add(instruction(where, "mov", new Operand.Name(where, MULTIPLY_ANSWER), left));
        instructions.add(instruction(where, signed ? "imul" : "mul", right));
        instructions.add(instruction(where, "mov", destination,
                new Operand.Name(where, MULTIPLY_ANSWER)));
        return new Expansion(instructions, true);
    }

    /**
     * {@code mov ax, left; [cwd | xor dx, dx]; div right; mov destination, dx|ax}.
     *
     * <p>{@code cwd} is the whole of the sign extension: it fills {@code DX} with a
     * copy of {@code AX}'s sign bit, which is exactly the dividend the instruction
     * wants. Without a sign to copy there is nothing for it to do, so an unsigned
     * division clears {@code DX} instead.
     *
     * <p>The flags do not survive this sequence and cannot: {@code div} leaves them
     * undefined on this machine, and clearing {@code DX} has been through them on the
     * way. Saying so is what makes selection refuse the sequence where the program can
     * still read them ({@code docs/ir.md} §5.1).
     */
    @Override
    public Expansion divide(SourcePos where, Operand destination, Operand left, Operand right,
                            boolean signed, boolean remainder) {
        if (left == null || right == null || right instanceof Operand.Number) {
            return null;
        }
        List<Instruction> instructions = new ArrayList<Instruction>();
        instructions.add(instruction(where, "mov", new Operand.Name(where, MULTIPLY_ANSWER), left));
        if (signed) {
            instructions.add(instruction(where, "cwd"));
        } else {
            instructions.add(instruction(where, "xor", new Operand.Name(where, REMAINDER_ANSWER),
                    new Operand.Name(where, REMAINDER_ANSWER)));
        }
        instructions.add(instruction(where, signed ? "idiv" : "div", right));
        instructions.add(instruction(where, "mov", destination,
                new Operand.Name(where, remainder ? REMAINDER_ANSWER : MULTIPLY_ANSWER)));
        return new Expansion(instructions, false);
    }

    /**
     * A division by a constant, where the constant makes it cheaper.
     *
     * <p>Dividing an unsigned value by a power of two is shifting it, and taking its remainder is
     * masking it: four bytes where the machine's division is eight, and — the half that is not about
     * bytes — no registers held. {@code div} keeps its answer in {@code ax} and {@code dx} and its
     * divisor in a register, so the values around it are pushed out of four of the six registers this
     * machine has, where a shift and a mask touch one.
     *
     * <p>A signed value is the case this is not: a shift rounds towards minus infinity and
     * {@code idiv} towards zero, so {@code -1 / 2} is 0 where {@code sar} would make it -1. Correcting
     * that costs more bytes than the division it replaces, so a signed division stays a division.
     */
    @Override
    public Expansion divideByConstant(SourcePos where, Operand destination, Operand source,
                                      long divisor, boolean signed, boolean remainder) {
        if (divisor == 1) {
            // Dividing by one is the answer itself, and its remainder is nothing at all. Both are a
            // move, and the quotient's is one the allocator may find unnecessary.
            Operand value = remainder
                    ? new Operand.Number(where, 0, Numbers.spelling(0))
                    : source;
            return new Expansion(Collections.singletonList(
                    instruction(where, "mov", destination, value)), false);
        }
        if (signed || divisor < 2 || (divisor & (divisor - 1)) != 0) {
            return null; // only an unsigned power of two is a shift
        }
        if (remainder) {
            return new Expansion(Collections.singletonList(instruction(where, "and", destination,
                    new Operand.Number(where, divisor - 1, Numbers.spelling(divisor - 1)))), false);
        }
        int steps = 0;
        for (long remaining = divisor; remaining > 1; remaining >>= 1) {
            steps++;
        }
        // A division leaves the flags undefined, so a shift's flags are as good as any other, and
        // saying so is what lets this stand where the division it replaces does (docs/ir.md §6.2).
        Expansion shift = shiftByConstant(where, "shr", destination, source, steps);
        return new Expansion(shift.instructions(), false);
    }

    @Override
    public Expansion multiplyByConstant(SourcePos where, Operand destination, Operand source,
                                        long factor) {
        if (factor < 2 || (factor & (factor - 1)) != 0) {
            return null; // only a power of two is a shift
        }
        int steps = 0;
        for (long remaining = factor; remaining > 1; remaining >>= 1) {
            steps++;
        }
        // Shifts by a count, not the multiply's flags: a multiply leaves flags this
        // sequence does not, however many times it is repeated.
        return shiftSequence(where, "shl", destination, source, steps, false);
    }

    /**
     * A shift by a count, however the count is written: once for each step, or once
     * with the count in {@code cl}.
     *
     * <p>The choice is made on bytes alone. Four bytes buys any count — two for the
     * count in {@code cl} and two for the shift — and three single shifts cost six,
     * so that is where it starts to pay. What the counted form costs beyond bytes is
     * that {@code cx} is destroyed, which the allocator may have to work around: a
     * cost this cannot see, and the reason the threshold is where the bytes alone
     * decide it.
     */
    private static Expansion shiftSequence(SourcePos where, String mnemonic, Operand destination,
                                           Operand source, int steps, boolean keepsFlags) {
        if (steps >= COUNT_FROM_REGISTER) {
            return countedShift(where, mnemonic, destination, source, steps, keepsFlags);
        }
        return repeatedShift(where, mnemonic, destination, source, steps, keepsFlags);
    }

    @Override
    public Expansion shiftByConstant(SourcePos where, String mnemonic, Operand destination,
                                     Operand source, long count) {
        if (count < 1 || count > 16) {
            return null; // a sixteen-bit value has nothing left after sixteen shifts
        }
        if (count >= COUNT_FROM_REGISTER) {
            return countedShift(where, mnemonic, destination, source, (int) count, count == 1);
        }
        // One shift is the operation itself; more than one is not, because the
        // flags after the last shift are not the flags after a single shift by
        // that count.
        return repeatedShift(where, mnemonic, destination, source, (int) count, count == 1);
    }

    @Override
    public List<String> stateRegisters() {
        return STATE_REGISTERS;
    }

    /**
     * The byte into {@code ax}, extended, and then out to where the answer goes.
     *
     * <p>{@code xor ah, ah} clears the top half and {@code cbw} fills it with the sign — the two
     * things this machine can do that a 386 calls {@code MOVZX} and {@code MOVSX}. Both work on
     * {@code ax} and nowhere else, so the sequence is stated in full: the copies that turn out to be
     * moves of a register into itself are the allocator's to drop.
     *
     * <p>The flags are given up, which the surface already assumes of any conversion
     * ({@code docs/ir.md} §3.5, {@code [open]}): {@code xor} and {@code cbw} both write them.
     */
    @Override
    public Expansion widen(SourcePos where, Operand destination, Operand source, boolean signed) {
        List<Instruction> instructions = new ArrayList<Instruction>();
        instructions.add(instruction(where, "mov", new Operand.Name(where, "al"), source));
        instructions.add(signed
                ? instruction(where, "cbw")
                : instruction(where, "xor", new Operand.Name(where, "ah"),
                        new Operand.Name(where, "ah")));
        instructions.add(instruction(where, "mov", destination, new Operand.Name(where, "ax")));
        return new Expansion(instructions, false);
    }

    /**
     * Setting one of the machine's own registers, from an operand.
     *
     * <p>{@code sp} and {@code bp} take the operand directly — {@code mov sp, x} is one instruction
     * — and so does a segment register, when the operand is already a general register: this
     * machine's {@code mov sreg, r/m16} takes one, so {@code mov ds, cx} is the whole of it, a byte
     * shorter than going through {@code ax} and without the register the copy would hold.
     *
     * <p>What a segment register cannot take is an immediate or a memory operand — there is no
     * {@code mov ds, 0} and no {@code mov ds, [x]} — and neither can it take another segment
     * register, {@code mov es, ds} being no encoding at all. Those go through {@code ax} first: the
     * copy is stated even when it turns out to be unnecessary, which is how the rest of this class
     * writes a sequence, and the allocator is the one that finds out and drops a copy of a register
     * into itself. A byte cannot arrive either; the verifier refuses one where a segment register is
     * written, because there is nothing to put it in.
     *
     * <p>A zero is built with {@code xor} rather than moved, when nothing can look at the flags
     * afterwards: {@code xor r, r} is one byte shorter than {@code mov r, 0} on a word, and it is
     * the same register cleared either way ({@code docs/ir.md} §4.2). The flags are what make it a
     * question at all — {@code xor} writes them, {@code mov} leaves them alone — so where they can
     * still be read the sequence does what it did before.
     */
    @Override
    public Expansion writeState(SourcePos where, String name, Operand value, boolean flagsMayBeRead) {
        List<Instruction> instructions = new ArrayList<Instruction>();
        if (SEGMENT_REGISTERS.contains(name)) {
            if (isAGeneralRegister(value)) {
                instructions.add(instruction(where, "mov", new Operand.Name(where, name), value));
                return new Expansion(instructions, true);
            }
            Operand scratch = new Operand.Name(where, SEGMENT_SCRATCH);
            instructions.add(built(where, scratch, value, flagsMayBeRead));
            instructions.add(instruction(where, "mov", new Operand.Name(where, name), scratch));
            return new Expansion(instructions, true);
        }
        instructions.add(built(where, new Operand.Name(where, name), value, flagsMayBeRead));
        return new Expansion(instructions, true);
    }

    /**
     * Whether this operand is a general register, which is what {@code mov sreg, r/m16} takes.
     *
     * <p>Two things say no, and the machine is the reason for both: a literal is not a register at
     * all, and a segment register is one this machine will not move into another — {@code mov es, ds}
     * has no encoding. The verifier refuses the other two operands that could not be named here, a
     * label and a byte, so what arrives is a value, an immediate or a segment register; the question
     * is asked of the operand all the same, because what the encoding takes is what decides it.
     */
    private static boolean isAGeneralRegister(Operand operand) {
        if (Shape.of(operand) != Shape.REGISTER) {
            return false;
        }
        return !(operand instanceof Operand.Name
                && SEGMENT_REGISTERS.contains(((Operand.Name) operand).name()));
    }

    /**
     * The instruction that puts {@code value} into a register: a literal zero goes through
     * {@link #zero}, anything else is moved.
     *
     * <p>A value that happens to hold zero is not a literal zero: this is the one place a zero is
     * known to be one, and a register is moved like any other value.
     */
    private Instruction built(SourcePos where, Operand register, Operand value,
                              boolean flagsMayBeRead) {
        if (value instanceof Operand.Number && ((Operand.Number) value).value() == 0) {
            return zero(where, register, Size.WORD, flagsMayBeRead);
        }
        return instruction(where, "mov", register, value);
    }

    /**
     * Clearing a register, which is what this machine does with a zero it does not have to move.
     *
     * <p>{@code xor r, r} is two bytes where {@code mov r, 0} is three, and it is the same register
     * with the same zero in it — the difference is the flags, which is the parameter
     * ({@code docs/ir.md} §4.2). On a byte the two are two bytes each, so the move stays: there is
     * nothing to win and one more thing for a reader to work out.
     */
    @Override
    public Instruction zero(SourcePos where, Operand register, Size size, boolean flagsMayBeRead) {
        if (flagsMayBeRead || size.bytes() < Size.WORD.bytes()) {
            return instruction(where, "mov", register,
                    new Operand.Number(where, 0, Numbers.spelling(0)));
        }
        return instruction(where, "xor", register, register);
    }

    /**
     * Two cleanups, in the order they are worth doing: a constant a register already holds is not
     * built again ({@link RepeatedConstants}), and a countdown is done the way this machine counts it
     * down ({@link CountedLoops}).
     */
    @Override
    public Selection tail(SsaForm form, Selection selection) {
        return CountedLoops.clean(form, RepeatedConstants.clean(this, form, selection));
    }

    /**
     * Reading one of the machine's own registers into a value.
     *
     * <p>One instruction either way: this machine moves a register into a register, and a segment
     * register is no harder to read than to write — {@code mov ax, ds} is one instruction, and so is
     * {@code mov al, dl}. The flags are left alone, so nothing about them has to be asked.
     *
     * <p>What makes the read say what the machine left in the register is the allocator's side of
     * it, not this one: the registers a value can live in are kept out of the way up to this point
     * ({@code docs/ir.md} §8.1).
     */
    @Override
    public Expansion readState(SourcePos where, Operand destination, String register) {
        if (!REGISTERS.contains(register)) {
            return null;
        }
        List<Instruction> instructions = new ArrayList<Instruction>();
        instructions.add(instruction(where, "mov", destination, new Operand.Name(where, register)));
        return new Expansion(instructions, true);
    }

    /**
     * {@code mov cl, n; shl r, cl} — the count in the one register the machine will
     * take it from.
     *
     * <p>This is what the flags have to be checked against: the flags after a shift
     * by {@code cl} are the flags after shifting that many times one at a time, so
     * this expansion keeps the operation's flags exactly when a single shift does.
     *
     * <p>{@code cx} is destroyed by the {@code mov}, and nothing here says so: the
     * allocator asks {@link #clobbers}, which reads the instruction and finds a
     * register written where a value could have been.
     */
    private static Expansion countedShift(SourcePos where, String mnemonic, Operand destination,
                                          Operand source, int count, boolean keepsFlags) {
        List<Instruction> instructions = new ArrayList<Instruction>();
        if (!sameRegister(destination, source)) {
            instructions.add(instruction(where, "mov", destination, source));
        }
        instructions.add(instruction(where, "mov", new Operand.Name(where, "cl"),
                new Operand.Number(where, count, Integer.toString(count))));
        instructions.add(instruction(where, mnemonic, destination,
                new Operand.Name(where, "cl")));
        return new Expansion(instructions, keepsFlags);
    }

    private static Expansion repeatedShift(SourcePos where, String mnemonic, Operand destination,
                                           Operand source, int steps, boolean keepsFlags) {
        List<Instruction> instructions = new ArrayList<Instruction>();
        if (!sameRegister(destination, source)) {
            instructions.add(instruction(where, "mov", destination, source));
        }
        for (int i = 0; i < steps; i++) {
            instructions.add(instruction(where, mnemonic, destination,
                    new Operand.Number(where, 1, "1")));
        }
        return new Expansion(instructions, keepsFlags);
    }

    /** Whether two operands name the same register, decided or not. */
    private static boolean sameRegister(Operand left, Operand right) {
        String leftName = left instanceof Operand.Name ? ((Operand.Name) left).name()
                : ((Operand.Virtual) left).name();
        String rightName = right instanceof Operand.Name ? ((Operand.Name) right).name()
                : ((Operand.Virtual) right).name();
        return leftName.equals(rightName);
    }

    private static Instruction instruction(SourcePos where, String mnemonic, Operand... operands) {
        return new Instruction(where, mnemonic, Arrays.asList(operands));
    }
}
