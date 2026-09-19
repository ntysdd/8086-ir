package i8086.target;

import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.ir.Comparison;
import i8086.ir.Item;
import i8086.ir.Operator;

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
     * without keeping it; both are two bytes when both operands are registers.
     */
    private static final List<Form> COMPARE_FORMS = Collections.unmodifiableList(
            Arrays.asList(new Form("cmp", shapes(Shape.REGISTER, Shape.REGISTER), 2),
                    new Form("cmp", shapes(Shape.REGISTER, Shape.IMMEDIATE), 3)));

    private static final List<Form> TEST_FORMS = Collections.unmodifiableList(
            Arrays.asList(new Form("test", shapes(Shape.REGISTER, Shape.REGISTER), 2),
                    new Form("test", shapes(Shape.REGISTER, Shape.IMMEDIATE), 3)));

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

    private static Set<String> names(String... names) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(names)));
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

    @Override
    public List<Form> compareForms(Item.Compare.Kind kind) {
        return kind == Item.Compare.Kind.TEST ? TEST_FORMS : COMPARE_FORMS;
    }

    @Override
    public List<Form> forms(Operator operator) {
        List<Form> forms = FORMS.get(operator);
        return forms == null ? Collections.<Form>emptyList() : forms;
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
        return repeatedShift(where, "shl", destination, source, steps, false);
    }

    @Override
    public Expansion shiftByConstant(SourcePos where, String mnemonic, Operand destination,
                                     Operand source, long count) {
        if (count < 1 || count > 16) {
            return null; // a sixteen-bit value has nothing left after sixteen shifts
        }
        // One shift is the operation itself; more than one is not, because the
        // flags after the last shift are not the flags after a single shift by
        // that count.
        return repeatedShift(where, mnemonic, destination, source, (int) count, count == 1);
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
