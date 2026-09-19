package i8086.ir;

import i8086.CompileError;
import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.asm.Size;
import i8086.asm.Token;
import i8086.asm.TokenKind;
import i8086.asm.Tokenizer;
import i8086.target.Target;
import i8086.target.Targets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the IR surface into a {@link Module}.
 *
 * <p>The parser decides shape, and refuses everything else with a position and a
 * reason. It knows no 8086 vocabulary: a mnemonic, a register and a size prefix
 * inside an inline block are shapes here and are checked against the target
 * description later ({@code docs/asm.md} §1, {@code AGENTS.md} invariant 2).
 *
 * <p>Constructs that are specified but not built yet are refused as such, with
 * the section of {@code docs/ir.md} that specifies them, rather than being
 * mis-parsed into something plausible.
 */
public final class IrParser {

    private static final int MAX_ORIGIN = 0xFFFF;

    /** Below every operator, so that parsing starts at the loosest level. */
    private static final int LOWEST_PRECEDENCE = 0;

    /**
     * Words the surface already uses for something else, which therefore cannot
     * name anything. The list is the IR's own vocabulary; the conditions, the
     * type prefixes and the operator words come from the target, from
     * {@link Size} and from {@link Operator}, because those are not this file's
     * facts to know.
     */
    private static final Set<String> STATEMENT_WORDS = new LinkedHashSet<String>(Arrays.asList(
            "var", "ret", "asm", "jmp", "cmp", "test",
            "target", "org", "entry"));

    /** How a generated label is named: no name a person can write looks like this. */
    private static final String GENERATED_LABEL = "$lbl";

    private final String file;
    private final List<Token> tokens;
    private int index;
    private Target target;
    private int generatedLabels;

    /**
     * The type of every declared variable, read before anything else.
     *
     * <p>The control-flow sugar has to know the signedness of the values it
     * compares — that is what decides between {@code jb} and {@code jl} — and that
     * is written in the declarations, which may come after it. So the
     * declarations are read first; the parse proper is still the authority on what
     * a declaration is, and this only feeds an inference.
     */
    private Map<String, Type> declaredTypes = Collections.emptyMap();

    private IrParser(String file, List<Token> tokens) {
        this.file = file;
        this.tokens = tokens;
    }

    public static Module parse(String file, String source) {
        return new IrParser(file, Tokenizer.tokenize(file, source)).parseModule();
    }

    private Module parseModule() {
        declaredTypes = declaredTypes(tokens);
        SourcePos start = peek().position();
        String target = null;
        String entry = null;
        SourcePos entryPosition = null;
        Integer origin = null;

        skipNewlines();
        while (peek().is(TokenKind.IDENT) && isHeaderWord(peek().name())) {
            Token keyword = next();
            if (keyword.isName("target")) {
                target = expectTargetName(target == null, keyword);
            } else if (keyword.isName("org")) {
                origin = expectOrigin(origin == null, keyword);
            } else {
                entry = expectEntry(entry == null, keyword);
                entryPosition = keyword.position();
            }
            endOfLine();
            skipNewlines();
        }

        require(target != null, start, "a module must say which target it is for: 'target 8086'");
        require(origin != null, start, "a module must say where its image is loaded: 'org 0x100'");
        require(entry != null, start, "a module must say where execution begins: 'entry NAME'");

        List<Item> items = new ArrayList<Item>();
        while (!peek().isEof()) {
            items.addAll(parseItems());
            if (!peek().isEof()) {
                throw new CompileError(peek().position(),
                        "'" + peek().text() + "' closes nothing: there is no '.if' or '.while' "
                                + "for it to end");
            }
        }
        return new Module(target, origin.intValue(), entry, entryPosition, items);
    }

    /** The type of every name a {@code var} declares, for the sugar to reason with. */
    private static Map<String, Type> declaredTypes(List<Token> tokens) {
        Map<String, Type> types = new LinkedHashMap<String, Type>();
        for (int i = 0; i + 3 < tokens.size(); i++) {
            if (tokens.get(i).isName("var") && tokens.get(i + 1).is(TokenKind.IDENT)
                    && tokens.get(i + 2).is(":") && tokens.get(i + 3).is(TokenKind.IDENT)) {
                Type type = Type.named(tokens.get(i + 3).name());
                if (type != null) {
                    types.put(tokens.get(i + 1).name(), type);
                }
            }
        }
        return types;
    }

    private static boolean isHeaderWord(String name) {
        return name.equals("target") || name.equals("org") || name.equals("entry");
    }

    private String expectTargetName(boolean first, Token keyword) {
        require(first, keyword.position(), "'target' is given more than once");
        // A target name may begin with a digit — the 8086 is called 8086 — so a
        // number is accepted here and its spelling is the name. This is the only
        // place in the surface where a number is read as a word.
        Token name = peek();
        require(name.is(TokenKind.IDENT) || name.is(TokenKind.NUMBER), name.position(),
                "expected a target name after 'target', but found " + name.describe());
        next();
        Target known = Targets.byName(name.name());
        require(known != null, name.position(),
                "unsupported target '" + name.text() + "'; the known targets are "
                        + Targets.knownNames());
        target = known;
        return name.name();
    }

    private Integer expectOrigin(boolean first, Token keyword) {
        require(first, keyword.position(), "'org' is given more than once");
        Token number = expect(TokenKind.NUMBER, "a number after 'org'");
        require(number.value() <= MAX_ORIGIN, number.position(),
                "'org' is an offset inside a segment, so it is at most 0xFFFF, not "
                        + number.text());
        return Integer.valueOf((int) number.value());
    }

    private String expectEntry(boolean first, Token keyword) {
        require(first, keyword.position(), "'entry' is given more than once");
        return expect(TokenKind.IDENT, "a label name after 'entry'").name();
    }

    /**
     * Every item until the end of the file or until something closes a block.
     *
     * <p>What closes a block is not decided here: this stops, and the construct
     * that opened the block says whether the word it stopped at is the one it was
     * waiting for.
     */
    private List<Item> parseItems() {
        List<Item> items = new ArrayList<Item>();
        while (!peek().isEof() && !closesABlock(peek())) {
            items.addAll(parseItem());
            skipNewlines();
        }
        return items;
    }

    private static boolean closesABlock(Token token) {
        return token.is(TokenKind.IDENT) && (token.name().equals(".elseif")
                || token.name().equals(".else") || token.name().equals(".endif")
                || token.name().equals(".endw"));
    }

    private List<Item> parseItem() {
        Token first = peek();

        if (first.isName(".if")) {
            return parseIf(next());
        }
        if (first.isName(".while")) {
            return parseWhile(next());
        }
        if (first.is(TokenKind.IDENT) && first.name().startsWith(".")) {
            throw new CompileError(first.position(),
                    "'" + first.text() + "' is not a directive of this surface");
        }

        List<Item> one = new ArrayList<Item>();
        one.add(parseStatement());
        return one;
    }

    /**
     * {@code .if cond ... .elseif cond ... .else ... .endif}, into tests and jumps:
     *
     * <pre>
     *   cmp ...
     *   j{not cond} L1      ; past this branch when the test fails
     *   ...                 ; the body
     *   jmp END
     * L1: ...
     * END:
     * </pre>
     *
     * <p>The shape is {@code docs/ir.md} §7.2's: the sugar is normalised away
     * here, and the printer prints what it became.
     */
    private List<Item> parseIf(Token keyword) {
        SourcePos at = keyword.position();
        List<Item> out = new ArrayList<Item>();
        String end = null;
        String next = freshLabel();
        out.addAll(test(parseCondition("'.if'"), next, false, at));
        endOfLine();

        while (true) {
            out.addAll(parseItems());
            if (peek().isName(".elseif")) {
                Token here = next();
                end = end == null ? freshLabel() : end;
                out.add(jump(end, here.position()));
                out.add(label(next, here.position()));
                next = freshLabel();
                out.addAll(test(parseCondition("'.elseif'"), next, false, here.position()));
                endOfLine();
                continue;
            }
            if (peek().isName(".else")) {
                Token here = next();
                endOfLine();
                end = end == null ? freshLabel() : end;
                out.add(jump(end, here.position()));
                out.add(label(next, here.position()));
                out.addAll(parseItems());
            } else {
                out.add(label(next, at));
            }
            if (end != null) {
                out.add(label(end, at));
            }
            require(peek().isName(".endif"), peek().position(),
                    "expected '.endif' to close the '.if' at " + at);
            next();
            endOfLine();
            return out;
        }
    }

    /**
     * {@code .while cond ... .endw}, with the test at the bottom:
     *
     * <pre>
     *   jmp TEST
     * BODY:
     *   ...
     * TEST:
     *   cmp ...
     *   j{cond} BODY      ; the condition's own branch, back to the body
     * </pre>
     *
     * <p>The test has to happen before the first time round, so there is a jump to
     * it — paid once. After that the <em>conditional</em> branch is what goes back,
     * which saves an instruction every time round, and instructions are what this
     * project counts. The branch is the one the writer asked for rather than its
     * opposite, because falling out of the loop is the path that needs no
     * instruction at all.
     */
    private List<Item> parseWhile(Token keyword) {
        SourcePos at = keyword.position();
        Condition condition = parseCondition("'.while'");
        endOfLine();
        List<Item> out = new ArrayList<Item>();
        String body = freshLabel();
        String test = freshLabel();
        out.add(jump(test, at));
        out.add(label(body, at));
        out.addAll(parseItems());
        out.add(label(test, at));
        out.addAll(test(condition, body, true, at));
        require(peek().isName(".endw"), peek().position(),
                "expected '.endw' to close the '.while' at " + at);
        next();
        endOfLine();
        return out;
    }

    /**
     * One test of the sugar: compare, then branch — to the target when the
     * comparison holds, or when it does not.
     *
     * <p>Which way round is the caller's business, and it is the difference between
     * the two constructs: an {@code .if} leaves when its test fails, a
     * {@code .while} goes back when its test holds.
     */
    private List<Item> test(Condition condition, String destination, boolean whenTrue,
                            SourcePos where) {
        List<Item> out = new ArrayList<Item>();
        out.add(new Item.Compare(where, Item.Compare.Kind.CMP, condition.left, condition.right));
        String word = target.conditionFor(condition.comparison, condition.signed);
        out.add(new Item.Branch(where, whenTrue ? word : target.negate(word), destination));
        return out;
    }

    /** A comparison as the sugar writes one, with the signedness its operands imply. */
    private Condition parseCondition(String what) {
        Token at = peek();
        Value left = parseValue();
        Token operator = peek();
        Comparison comparison = operator.is(TokenKind.PUNCT)
                ? Comparison.named(operator.text()) : null;
        require(comparison != null, operator.position(),
                what + " needs a comparison — '==', '!=', '<', '<=', '>' or '>=' — but found "
                        + operator.describe());
        next();
        Value right = parseValue();
        return new Condition(left, comparison, right,
                comparisonSignedness(left, right, comparison, at.position()));
    }

    /**
     * Whether the comparison is between signed values.
     *
     * <p>A variable says; a literal and a load do not, so they take the answer
     * from the other side. When nothing says, the answer is unsigned, which is
     * what the plainest mnemonics say: {@code jb} and {@code ja}.
     */
    private boolean comparisonSignedness(Value left, Value right, Comparison comparison,
                                         SourcePos at) {
        Boolean leftSigned = signednessOf(left);
        Boolean rightSigned = signednessOf(right);
        require(leftSigned == null || rightSigned == null || leftSigned.equals(rightSigned), at,
                "the two sides of '" + comparison.spelling() + "' are one signed and one unsigned, "
                        + "so which test to use would be a guess; make them the same type, or "
                        + "compare through a variable of the type you mean (docs/ir.md §3.5)");
        if (leftSigned != null) {
            return leftSigned.booleanValue();
        }
        return rightSigned != null && rightSigned.booleanValue();
    }

    private Boolean signednessOf(Value value) {
        if (value instanceof Value.Name) {
            Type type = declaredTypes.get(((Value.Name) value).name());
            return type == null ? null : Boolean.valueOf(type.isSigned());
        }
        return null;
    }

    private String freshLabel() {
        return GENERATED_LABEL + generatedLabels++;
    }

    private static Item label(String name, SourcePos where) {
        return new Item.Label(where, name);
    }

    private static Item jump(String target, SourcePos where) {
        return new Item.Jump(where, target);
    }

    /** A comparison the sugar is about to turn into flags. */
    private static final class Condition {

        private final Value left;
        private final Comparison comparison;
        private final Value right;
        private final boolean signed;

        Condition(Value left, Comparison comparison, Value right, boolean signed) {
            this.left = left;
            this.comparison = comparison;
            this.right = right;
            this.signed = signed;
        }
    }

    private Item parseStatement() {
        Token first = peek();

        // Before labels: `es:[p]` starts with the same two tokens as a label,
        // and only the bracket after them says which one it is.
        if (startsMemoryOperand()
                || (first.is(TokenKind.IDENT) && isNameFollowing(TokenKind.PUNCT, "=")))
        {
            return parseAssignment();
        }
        if (first.is(TokenKind.IDENT) && isNameFollowing(TokenKind.PUNCT, ":")) {
            next();
            next();
            return afterLabel(first);
        }
        if (first.is(TokenKind.IDENT) && Size.fromDirective(first.name()) != null) {
            return parseData(next(), null);
        }
        if (first.isName("var")) {
            return parseVar();
        }
        if (first.isName("ret")) {
            next();
            endOfLine();
            return new Item.Return(first.position());
        }
        if (first.isName("asm")) {
            return parseInlineAsm();
        }
        if (first.isName("jmp")) {
            next();
            String where = expect(TokenKind.IDENT, "a label to jump to").name();
            endOfLine();
            return new Item.Jump(first.position(), where);
        }
        if (first.isName("cmp") || first.isName("test")) {
            return parseCompare();
        }
        if (first.isName("eval")) {
            Token keyword = next();
            Operation operation = parseParenthesisedOperation();
            endOfLine();
            return new Item.Eval(keyword.position(), operation);
        }
        if (first.is(TokenKind.IDENT) && target.condition(first.name()) != null) {
            next();
            String where = expect(TokenKind.IDENT, "a label to branch to").name();
            endOfLine();
            return new Item.Branch(first.position(), target.condition(first.name()), where);
        }
        if (first.is(TokenKind.IDENT)) {
            return parseInstructionStatement(first);
        }
        throw new CompileError(first.position(),
                "expected a label, a data definition, 'var', an assignment, 'ret', 'asm', "
                        + "'cmp', 'jmp' or a condition, but found " + first.describe());
    }

    /**
     * A statement written the way the machine writes it: an operation whose
     * destination is spelled out ({@code docs/ir.md} §7.3).
     *
     * <p>{@code OP d, s} is the same statement as {@code d = eval(d OP s)} and is
     * built as that, so nothing downstream — the verifier, the passes, the printer —
     * has to know this spelling exists. Which words are accepted is the target's
     * answer, because whether a word names the operation it looks like is a fact about
     * the machine ({@link Target#statementOperator(String)}).
     *
     * <p>{@code mov} is handled here rather than asked about, because it is not an
     * operation: it is the bare assignment of §5.3, which the surface already spells
     * with {@code =}.
     */
    private Item parseInstructionStatement(Token first) {
        String word = first.name();
        boolean move = word.equals("mov");
        next();
        // The operands are read before the question is asked, because how many were
        // written is part of what the target is asked about: 'mul s, t' is an
        // operation and 'mul s' is an instruction that works on ax and dx.
        List<Value> operands = parseInstructionOperands();
        endOfLine();
        if (move) {
            return moveStatement(first, operands);
        }
        Operator operator = target.statementOperator(word);
        if (operator == null || operator.arity() != operands.size()) {
            throw statementRefusal(first, operands.size(), operator);
        }
        Place destination = place(operands.get(0));
        if (destination == null) {
            throw new CompileError(first.position(),
                    "the first operand of '" + first.text() + "' is where the result goes, so it "
                            + "is a variable or a memory operand, and a literal is neither "
                            + "(docs/ir.md §7.3)");
        }
        return new Item.Assign(first.position(), destination,
                new Value.Eval(first.position(),
                        new Operation(first.position(), operator, operands)));
    }

    /** The operands of an instruction-shaped statement, in the order they were written. */
    private List<Value> parseInstructionOperands() {
        List<Value> operands = new ArrayList<Value>();
        if (peek().is(TokenKind.NEWLINE) || peek().isEof()) {
            return operands;
        }
        operands.add(parseInstructionOperand("an operand"));
        while (peek().is(",")) {
            next();
            operands.add(parseInstructionOperand("an operand"));
        }
        if (!peek().is(TokenKind.NEWLINE) && !peek().isEof()) {
            throw new CompileError(peek().position(),
                    "an instruction-shaped statement is one operation with its operands written "
                            + "out, so nothing here has structure: anything computed is written "
                            + "with expr(...) in a statement of its own (docs/ir.md §5.4, §7.3)");
        }
        return operands;
    }

    /** {@code mov d, s}, which is the assignment {@code d = s} (§5.3). */
    private Item moveStatement(Token first, List<Value> operands) {
        if (operands.size() != 2) {
            throw new CompileError(first.position(),
                    "'mov' takes a destination and a value, and " + operands.size()
                            + (operands.size() == 1 ? " was" : " were") + " written; the "
                            + "assignment is also spelled 'd = s' (docs/ir.md §5.3, §7.3)");
        }
        Place destination = place(operands.get(0));
        if (destination == null) {
            throw new CompileError(first.position(),
                    "'mov' needs somewhere to put the value: a variable or a memory operand, "
                            + "not a literal (docs/ir.md §5.3)");
        }
        return new Item.Assign(first.position(), destination, operands.get(1));
    }

    /** The place a value can stand in as the destination of a statement, or null. */
    private static Place place(Value value) {
        if (value instanceof Value.Name) {
            return new Place.Name(value.position(), ((Value.Name) value).name());
        }
        if (value instanceof Value.Memory) {
            return new Place.Memory(value.position(), ((Value.Memory) value).operand());
        }
        return null;
    }

    /**
     * One operand of an instruction-shaped statement: a variable, a memory operand or
     * a literal, and never a register name.
     *
     * <p>A register name is refused here and not everywhere, because a variable may
     * legally be called {@code ax} ({@code docs/ir.md} §3.1) and {@code mov ax, 1}
     * would then quietly mean a variable rather than the register the writer meant
     * ({@code docs/ir.md} §7.3).
     */
    private Value parseInstructionOperand(String what) {
        Token at = peek();
        if (at.is(TokenKind.IDENT) && target.isRegister(at.name())) {
            throw new CompileError(at.position(),
                    "'" + at.text() + "' is a register, and a register is not an operand here: "
                            + "this spelling is for operations on variables and memory, and a "
                            + "value that has to be in a register of its own is written in an "
                            + "inline block (docs/ir.md §7.3, §9, §12 item 12)");
        }
        return parseOperationOperand(what);
    }

    /**
     * The refusal for a word that cannot begin a statement here.
     *
     * <p>The target's reason comes first, because only the target knows why its own
     * machine's word is not the operation it looks like. The surface's own words come
     * second, for the ones that are real constructs of the surface not built yet.
     */
    private CompileError statementRefusal(Token at, int operands, Operator operator) {
        String problem = target.statementProblem(at.name(), operands);
        if (problem != null) {
            return new CompileError(at.position(), problem);
        }
        String surface = statementDescription(at);
        if (surface != null) {
            return notImplemented(at, surface);
        }
        if (operator != null) {
            return new CompileError(at.position(), "'" + at.text() + "' takes " + operator.arity()
                    + " operand" + (operator.arity() == 1 ? "" : "s") + " and " + operands
                    + (operands == 1 ? " was" : " were") + " written; the operation itself is "
                    + "written " + evalSpelling(operator) + " (docs/ir.md §5.1, §7.3)");
        }
        return new CompileError(at.position(),
                "a statement does not begin with '" + at.text() + "': an operation is written "
                        + "'d = eval(d + 1)' or as the instruction-shaped statement 'add d, 1', "
                        + "and an instruction this surface has no operation for goes in an "
                        + "inline block (docs/ir.md §5.1, §7.3, §9)");
    }

    /** How an operation is written in the form everything downstream understands. */
    private static String evalSpelling(Operator operator) {
        return operator.arity() == 1
                ? "'d = eval(" + operator.spelling() + "d)'"
                : "'d = eval(d " + operator.spelling() + " s)'";
    }

    private Item parseCompare() {
        Token keyword = next();
        Item.Compare.Kind kind = keyword.isName("test")
                ? Item.Compare.Kind.TEST
                : Item.Compare.Kind.CMP;
        Value left = parseValue();
        expectPunct(",");
        Value right = parseValue();
        endOfLine();
        return new Item.Compare(keyword.position(), kind, left, right);
    }

    private Item parseVar() {
        Token keyword = expectName("var");
        Token name = expect(TokenKind.IDENT, "a variable name");
        requireNameable(name);
        expectPunct(":");
        Token typeWord = expect(TokenKind.IDENT, "a type after ':'");
        Type type = Type.named(typeWord.name());
        require(type != null, typeWord.position(),
                "unknown type '" + typeWord.text() + "'; the types are u8, u16, u32, i8, i16, i32");
        endOfLine();
        return new Item.Var(keyword.position(), name.name(), type);
    }

    /**
     * Refuses a name that is a word of the surface rather than a name.
     *
     * <p>{@code byte} and {@code db} mean something wherever they appear before
     * an operand, and so do {@code jmp}, {@code cmp} and the conditions. A
     * declaration using one would produce a program whose meaning depends on
     * where you look. Better to say so at the declaration.
     */
    private void requireNameable(Token name) {
        boolean reserved = Size.named(name.name()) != null
                || Size.fromDirective(name.name()) != null
                || STATEMENT_WORDS.contains(name.name())
                || Operator.named(name.name()) != null
                || Conversion.named(name.name()) != null
                || target.condition(name.name()) != null;
        require(!reserved, name.position(),
                "'" + name.text() + "' is a word of the surface, so it cannot name anything");
    }

    private Item parseAssignment() {
        Token start = peek();
        Place place = startsMemoryOperand()
                ? new Place.Memory(start.position(), parseMemoryOperand())
                : new Place.Name(start.position(), expect(TokenKind.IDENT, "a variable name").name());
        expectPunct("=");
        Value value = parseValue();
        endOfLine();
        return new Item.Assign(start.position(), place, value);
    }

    private Value parseValue() {
        Token first = peek();
        if (startsMemoryOperand()) {
            return new Value.Memory(first.position(), parseMemoryOperand());
        }
        if (first.isName("eval")) {
            next();
            return new Value.Eval(first.position(), parseParenthesisedOperation());
        }
        if (first.isName("expr")) {
            next();
            return new Value.Expr(first.position(), parseParenthesisedExpression());
        }
        if (first.is(TokenKind.IDENT) && Conversion.named(first.name()) != null) {
            Conversion conversion = Conversion.named(first.name());
            next();
            return new Value.Convert(first.position(), conversion,
                    parseConversionOperand(conversion));
        }
        if (first.is(TokenKind.IDENT) && Size.named(first.name()) != null) {
            throw new CompileError(first.position(),
                    "'" + first.text() + "' says how wide a memory access is, so it needs a "
                            + "bracket after it; to narrow a value, 'byte' and 'word' take the low "
                            + "byte or the low word, and there is nothing wider than a 'dword' "
                            + "to narrow (docs/ir.md §3.5)");
        }
        if (first.is(TokenKind.IDENT) && isOperatorWord(first.name())) {
            throw new CompileError(first.position(),
                    "'" + first.text() + "' is an operator, so it needs an expression: "
                            + "write eval(...) or expr(...) around it (docs/ir.md §5)");
        }
        if (first.is(TokenKind.NUMBER)) {
            next();
            return new Value.Number(first.position(), first.value(), first.text());
        }
        if (first.is(TokenKind.IDENT)) {
            next();
            return new Value.Name(first.position(), first.name());
        }
        if (first.is("-") || first.is("+")) {
            throw new CompileError(first.position(),
                    "a value cannot be negated where it is written: '-1' is the bit pattern "
                            + "0xFFFF, and '0 - x' subtracts");
        }
        throw new CompileError(first.position(),
                "expected a value, but found " + first.describe());
    }

    /**
     * The value a conversion applies to. It is a value and not an expression,
     * because an expression already has one width throughout and converting it
     * would have nothing to say ({@code docs/ir.md} §3.5).
     */
    private Value parseConversionOperand(Conversion conversion) {
        Token at = peek();
        boolean anotherConversion = Conversion.named(at.name()) != null && !startsMemoryOperand();
        if (at.isName("eval") || at.isName("expr") || anotherConversion) {
            throw new CompileError(at.position(),
                    "'" + conversion.spelling() + "' takes a value with one width, not "
                            + at.describe() + "; one conversion changes one width, which is what "
                            + "an operand with a 'byte' or 'word' prefix is already doing "
                            + "(docs/ir.md §3.5)");
        }
        return parseValue();
    }

    private Expression parseParenthesisedExpression() {
        expectPunct("(");
        Expression expression = parseExpression();
        expectPunct(")");
        return expression;
    }

    private Operation parseParenthesisedOperation() {
        expectPunct("(");
        Operation operation = parseOperation();
        expectPunct(")");
        return operation;
    }

    /**
     * One operation, which is all {@code eval} takes ({@code docs/ir.md} §5.1).
     *
     * <p>The grammar says so rather than the verifier, because a rule the model
     * cannot express is a rule nobody can break: an {@link Operation} holds an
     * operator and its operands, so a second operation has nowhere to go.
     */
    private Operation parseOperation() {
        Token at = peek();
        Operator prefix = prefixOperatorHere();
        if (prefix != null) {
            next();
            return new Operation(at.position(), prefix, one(parseOperationOperand("an operand")));
        }

        Value left = parseOperationOperand("the first operand");
        Token between = peek();
        Operator operator = operatorHere();
        require(operator != null && operator.arity() == 2, between.position(),
                "eval(...) takes exactly one operation, so an operator belongs here; anything "
                        + "with structure is written with expr(...), which is a tree "
                        + "(docs/ir.md §5.1)");
        next();
        Value right = parseOperationOperand("the second operand");
        require(operatorHere() == null, peek().position(),
                "eval(...) takes exactly one operation, and this would be a second one; the "
                        + "rest belongs in expr(...) or in a statement of its own (docs/ir.md §5.1)");
        // The operation's position is its operator's, the way an expression node's
        // is: that is where a complaint about the operator belongs.
        return new Operation(between.position(), operator, two(left, right));
    }

    private static List<Value> one(Value value) {
        List<Value> operands = new ArrayList<Value>(1);
        operands.add(value);
        return operands;
    }

    private static List<Value> two(Value left, Value right) {
        List<Value> operands = new ArrayList<Value>(2);
        operands.add(left);
        operands.add(right);
        return operands;
    }

    /**
     * A value an operation may take: a name, a literal or a load. A load is an
     * operand and not an operation, so {@code eval(a + [p])} is one operation.
     */
    private Value parseOperationOperand(String what) {
        Token at = peek();
        if (startsMemoryOperand()) {
            return new Value.Memory(at.position(), parseMemoryOperand());
        }
        if (at.is(TokenKind.NUMBER)) {
            next();
            return new Value.Number(at.position(), at.value(), at.text());
        }
        if (at.isName("eval") || at.isName("expr")) {
            throw new CompileError(at.position(),
                    "'eval' and 'expr' do not nest inside one another (docs/ir.md §5.4)");
        }
        if (at.is(TokenKind.IDENT) && Conversion.named(at.name()) != null) {
            throw new CompileError(at.position(),
                    "a conversion is not an operand of an operation: the operands of one "
                            + "operation are one width, and a conversion is how that width changes "
                            + "(docs/ir.md §3.2, §3.5)");
        }
        if (at.is(TokenKind.IDENT) && Operator.named(at.name()) != null) {
            throw new CompileError(at.position(),
                    "'" + at.text() + "' is an operator, so it needs operands (docs/ir.md §5.5)");
        }
        if (at.is(TokenKind.IDENT) && isWordOfTheSurface(at.name())) {
            throw new CompileError(at.position(),
                    "'" + at.text() + "' is a word of the surface, so it is not an operand here");
        }
        if (at.is(TokenKind.IDENT)) {
            next();
            return new Value.Name(at.position(), at.name());
        }
        throw new CompileError(at.position(),
                "expected " + what + ", but found " + at.describe());
    }

    /** Whether a word means something in the surface other than a name. */
    private static boolean isWordOfTheSurface(String name) {
        return STATEMENT_WORDS.contains(name)
                || Size.named(name) != null
                || Size.fromDirective(name) != null;
    }

    private Expression parseExpression() {
        return parseExpression(LOWEST_PRECEDENCE);
    }

    /**
     * Precedence climbing: an operator binds tighter than the one that called it,
     * and every operator is left-associative, so the right operand is parsed one
     * level above.
     */
    private Expression parseExpression(int minimumPrecedence) {
        Expression left = parseUnaryExpression();
        while (true) {
            Operator operator = operatorHere();
            if (operator == null || operator.arity() != 2
                    || operator.precedence() < minimumPrecedence) {
                return left;
            }
            Token at = next();
            Expression right = parseExpression(operator.precedence() + 1);
            left = new Expression.Apply(at.position(), operator, left, right);
        }
    }

    private Expression parseUnaryExpression() {
        Token at = peek();
        Operator prefix = prefixOperatorHere();
        if (prefix != null) {
            next();
            return new Expression.Unary(at.position(), prefix, parseUnaryExpression());
        }
        if (at.is("(")) {
            // Brackets are syntax, not a node: the tree already says what binds to
            // what, and the printer puts the brackets back where they are needed.
            next();
            Expression inner = parseExpression();
            expectPunct(")");
            return inner;
        }
        return new Expression.Leaf(at.position(), parseExpressionLeaf());
    }

    /** The operator that begins here, or null when an operand does. */
    private Operator operatorHere() {
        Token token = peek();
        if (token.is(TokenKind.PUNCT)) {
            return Operator.named(token.text());
        }
        if (token.is(TokenKind.IDENT)) {
            return Operator.named(token.name());
        }
        return null;
    }

    /**
     * The operator that begins here where a value would, or null if a value does.
     *
     * <p>Position is what tells a shared spelling apart: {@code -} in front of an
     * operand is negation and between two of them is subtraction.
     */
    private Operator prefixOperatorHere() {
        Token token = peek();
        if (token.is(TokenKind.PUNCT)) {
            return Operator.prefix(token.text());
        }
        if (token.is(TokenKind.IDENT)) {
            return Operator.prefix(token.name());
        }
        return null;
    }

    private static boolean isOperatorWord(String name) {
        Operator operator = Operator.named(name);
        return operator != null && operator.arity() == 2 && operator.spelling().length() > 1;
    }

    /** A value inside an expression: a variable, a literal, or a load. */
    private Value parseExpressionLeaf() {
        Token at = peek();
        if (startsMemoryOperand()) {
            return new Value.Memory(at.position(), parseMemoryOperand());
        }
        if (at.is(TokenKind.NUMBER)) {
            next();
            return new Value.Number(at.position(), at.value(), at.text());
        }
        if (at.is(TokenKind.IDENT)) {
            if (at.isName("eval") || at.isName("expr")) {
                throw new CompileError(at.position(),
                        "'eval' and 'expr' do not nest inside one another (docs/ir.md §5.4)");
            }
            if (Operator.named(at.name()) != null || Conversion.named(at.name()) != null) {
                throw new CompileError(at.position(),
                        "'" + at.text() + "' cannot appear inside an expression here: "
                                + "an expression has one width and one set of flags "
                                + "(docs/ir.md §5.5, §3.5)");
            }
            next();
            return new Value.Name(at.position(), at.name());
        }
        if (at.is("-") || at.is("+") || at.is("~")) {
            throw new CompileError(at.position(),
                    "'" + at.text() + "' here would negate a value, which the surface does not "
                            + "write: '-1' is the bit pattern 0xFFFF, and '0 - x' subtracts");
        }
        throw new CompileError(at.position(),
                "expected a value in the expression, but found " + at.describe());
    }

    /**
     * Whether a memory operand begins here.
     *
     * <p>A size prefix or a segment override comes before the bracket, so seeing
     * one is almost enough — almost, because {@code byte a} is a conversion and
     * {@code es:} can be a label, and only the bracket after them says which of
     * the two it is ({@code docs/ir.md} §3.4, §3.5).
     */
    private boolean startsMemoryOperand() {
        Token token = peek();
        if (token.is("[")) {
            return true;
        }
        if (token.is(TokenKind.IDENT) && Size.named(token.name()) != null) {
            if (tokenAt(1).is("[")) {
                return true;
            }
            return tokenAt(1).is(TokenKind.IDENT) && tokenAt(2).is(":") && tokenAt(3).is("[");
        }
        if (isVolatileWord(token)) {
            // 'volatile [p]', and 'volatile word [p]' after it.
            return tokenAt(1).is("[") || startsMemoryOperandAt(1);
        }
        return token.is(TokenKind.IDENT) && isNameFollowing(TokenKind.PUNCT, ":")
                && tokenAt(2).is("[");
    }

    /** The same question, asked from a token a little further along. */
    private boolean startsMemoryOperandAt(int offset) {
        Token token = tokenAt(offset);
        if (token.is("[")) {
            return true;
        }
        if (token.is(TokenKind.IDENT) && Size.named(token.name()) != null) {
            if (tokenAt(offset + 1).is("[")) {
                return true;
            }
            return tokenAt(offset + 1).is(TokenKind.IDENT) && tokenAt(offset + 2).is(":")
                    && tokenAt(offset + 3).is("[");
        }
        return token.is(TokenKind.IDENT) && tokenAt(offset + 1).is(TokenKind.PUNCT)
                && tokenAt(offset + 1).text().equals(":") && tokenAt(offset + 2).is("[");
    }

    /** Whether this token is the word that marks an access as one that must happen. */
    private static boolean isVolatileWord(Token token) {
        return token.is(TokenKind.IDENT) && token.name().equals("volatile");
    }

    private Token tokenAt(int offset) {
        int at = index + offset;
        return at < tokens.size() ? tokens.get(at) : tokens.get(tokens.size() - 1);
    }

    private MemoryOperand parseMemoryOperand() {
        Token start = peek();
        boolean isVolatile = isVolatileWord(start);
        if (isVolatile) {
            next();
        }
        Size size = null;
        Token afterVolatile = peek();
        if (afterVolatile.is(TokenKind.IDENT) && Size.named(afterVolatile.name()) != null) {
            size = Size.named(afterVolatile.name());
            next();
        }
        String segment = null;
        if (peek().is(TokenKind.IDENT) && isNameFollowing(TokenKind.PUNCT, ":")) {
            segment = next().name();
            next();
        }
        expectPunct("[");

        String base = null;
        long displacement = 0;
        Token inside = peek();
        if (inside.is(TokenKind.IDENT)) {
            base = next().name();
        } else if (inside.is(TokenKind.NUMBER)) {
            displacement = next().value();
        } else {
            throw new CompileError(inside.position(),
                    "expected an address inside the brackets, but found " + inside.describe());
        }

        if (peek().is("+") || peek().is("-")) {
            boolean subtract = next().is("-");
            require(base != null, peek().position(),
                    "a memory address is a name and a displacement, or a displacement alone");
            Token number = expect(TokenKind.NUMBER, "a displacement after the sign");
            displacement = subtract ? -number.value() : number.value();
        }
        expectPunct("]");
        return new MemoryOperand(start.position(), size, isVolatile, segment, base,
                displacement);
    }

    private static String statementDescription(Token first) {
        String name = first.name();
        if (name.equals("expr")) {
            return "'expr' as a statement: its value is the point, and its flags are undefined "
                    + "afterwards, so nothing would be left (docs/ir.md §5.2)";
        }
        if (name.startsWith("set")) {
            return "the setcc family (docs/ir.md §4.4)";
        }
        return null;
    }

    /** What follows a label on its own line: a data definition, or nothing. */
    private Item afterLabel(Token label) {
        requireNameable(label);
        if (peek().is(TokenKind.NEWLINE) || peek().isEof()) {
            return new Item.Label(label.position(), label.name());
        }
        if (peek().is(TokenKind.IDENT) && Size.fromDirective(peek().name()) != null) {
            return parseData(next(), label.name());
        }
        throw new CompileError(peek().position(),
                "a label stands on its own line, except before a data definition; found "
                        + peek().describe() + " after '" + label.text() + ":'");
    }

    private Item.Data parseData(Token sizeWord, String label) {
        Size size = Size.fromDirective(sizeWord.name());
        List<Item.Data.Atom> atoms = new ArrayList<Item.Data.Atom>();
        while (true) {
            Token value = peek();
            if (value.is(TokenKind.NUMBER)) {
                next();
                requireFits(value, size);
                atoms.add(Item.Data.Atom.ofNumber(value.value()));
            } else if (value.is(TokenKind.STRING)) {
                require(size == Size.BYTE, value.position(),
                        "a string is a sequence of bytes, so it needs 'db', not '" + sizeWord.text() + "'");
                next();
                atoms.add(Item.Data.Atom.ofText(value.text()));
            } else {
                throw new CompileError(value.position(),
                        "expected a number or a string after '" + sizeWord.text() + "', but found "
                                + value.describe());
            }
            if (peek().is(",")) {
                next();
                continue;
            }
            endOfLine();
            return new Item.Data(sizeWord.position(), label, size, atoms);
        }
    }

    private void requireFits(Token number, Size size) {
        long limit = (1L << (size.bytes() * 8)) - 1;
        require(number.value() <= limit, number.position(),
                "value " + number.text() + " does not fit in a " + size.spelling());
    }

    private Item.InlineAsm parseInlineAsm() {
        Token keyword = expectName("asm");
        List<String> clobbers = parseClobbers();
        expectPunct("{");
        skipNewlines();
        List<Instruction> body = new ArrayList<Instruction>();
        while (!peek().is("}")) {
            if (peek().isEof()) {
                throw new CompileError(keyword.position(), "inline assembly block is not closed");
            }
            body.add(parseInstruction());
            skipNewlines();
        }
        expectPunct("}");
        endOfLine();
        return new Item.InlineAsm(keyword.position(), clobbers, body);
    }

    private List<String> parseClobbers() {
        expectName("clobbers");
        expectPunct("(");
        List<String> names = new ArrayList<String>();
        if (!peek().is(")")) {
            while (true) {
                Token name = expect(TokenKind.IDENT, "a register name");
                require(!names.contains(name.name()), name.position(),
                        "the clobber list already names '" + name.text() + "'");
                names.add(name.name());
                if (peek().is(",")) {
                    next();
                    continue;
                }
                break;
            }
        }
        expectPunct(")");
        return names;
    }

    private Instruction parseInstruction() {
        Token mnemonic = expect(TokenKind.IDENT, "a mnemonic");
        List<Operand> operands = new ArrayList<Operand>();
        if (!peek().is(TokenKind.NEWLINE) && !peek().isEof() && !peek().is("}")) {
            while (true) {
                operands.add(parseOperand());
                if (peek().is(",")) {
                    next();
                    continue;
                }
                break;
            }
        }
        endOfLine();
        return new Instruction(mnemonic.position(), mnemonic.name(), operands);
    }

    private Operand parseOperand() {
        Token first = peek();

        if (first.isName("offset")) {
            next();
            return new Operand.Offset(first.position(),
                    expect(TokenKind.IDENT, "a label name after 'offset'").name());
        }
        Size size = null;
        if (first.is(TokenKind.IDENT) && Size.named(first.name()) != null) {
            size = Size.named(first.name());
            next();
        }
        String segment = null;
        if (peek().is(TokenKind.IDENT) && isNameFollowing(TokenKind.PUNCT, ":")) {
            segment = next().name();
            next();
        }
        if (peek().is("[")) {
            return parseMemory(first.position(), size, segment);
        }
        if (size != null) {
            throw new CompileError(first.position(),
                    "'" + size.spelling() + "' says how wide a memory access is, so it needs "
                            + "a memory operand after it");
        }
        if (segment != null) {
            throw new CompileError(first.position(),
                    "a segment override needs a memory operand after it");
        }
        if (first.is(TokenKind.NUMBER)) {
            next();
            return new Operand.Number(first.position(), first.value(), first.text());
        }
        if (first.is(TokenKind.IDENT)) {
            next();
            return new Operand.Name(first.position(), first.name());
        }
        throw new CompileError(first.position(), "expected an operand, but found " + first.describe());
    }

    private Operand parseMemory(SourcePos position, Size size, String segment) {
        expectPunct("[");
        List<Operand.Memory.Atom> atoms = new ArrayList<Operand.Memory.Atom>();
        boolean subtracted = false;
        while (true) {
            Token term = peek();
            Operand.Memory.Atom atom;
            if (term.is(TokenKind.IDENT)) {
                next();
                atom = Operand.Memory.Atom.ofName(term.name());
            } else if (term.is(TokenKind.NUMBER)) {
                next();
                atom = Operand.Memory.Atom.ofNumber(term.value());
            } else {
                throw new CompileError(term.position(),
                        "expected a register or a displacement inside the brackets, but found "
                                + term.describe());
            }
            atoms.add(subtracted ? atom.subtracted() : atom);
            subtracted = false;
            if (peek().is("+")) {
                next();
            } else if (peek().is("-")) {
                next();
                subtracted = true;
            } else {
                break;
            }
        }
        require(!atoms.isEmpty(), position, "the brackets are empty");
        expectPunct("]");
        return new Operand.Memory(position, size, segment, atoms);
    }

    // --- token plumbing ----------------------------------------------------

    private Token peek() {
        return tokens.get(index);
    }

    private Token next() {
        Token token = tokens.get(index);
        if (!token.isEof()) {
            index++;
        }
        return token;
    }

    private boolean isNameFollowing(TokenKind kind, String text) {
        Token after = index + 1 < tokens.size() ? tokens.get(index + 1) : tokens.get(tokens.size() - 1);
        return after.is(kind) && after.text().equals(text);
    }

    private Token expect(TokenKind kind, String what) {
        Token token = peek();
        if (token.kind() != kind) {
            throw new CompileError(token.position(),
                    "expected " + what + ", but found " + token.describe());
        }
        return next();
    }

    private void expectPunct(String punctuation) {
        Token token = peek();
        if (!token.is(punctuation)) {
            throw new CompileError(token.position(),
                    "expected '" + punctuation + "', but found " + token.describe());
        }
        next();
    }

    private Token expectName(String name) {
        Token token = peek();
        if (!token.isName(name)) {
            throw new CompileError(token.position(),
                    "expected '" + name + "', but found " + token.describe());
        }
        return next();
    }

    private void endOfLine() {
        Token token = peek();
        if (token.is(TokenKind.NEWLINE)) {
            next();
            return;
        }
        if (token.isEof()) {
            return;
        }
        if (operatorHere() != null || token.is("(") || token.is(")")) {
            throw new CompileError(token.position(),
                    "arithmetic is written inside eval(...) or expr(...), and an assignment is "
                            + "not an expression of its own; found " + token.describe()
                            + " here (docs/ir.md §5)");
        }
        throw new CompileError(token.position(),
                "expected the end of the line, but found " + token.describe());
    }

    private void skipNewlines() {
        while (peek().is(TokenKind.NEWLINE)) {
            next();
        }
    }

    private void require(boolean condition, SourcePos where, String message) {
        if (!condition) {
            throw new CompileError(where, message);
        }
    }

    private static CompileError notImplemented(Token token, String what) {
        return new CompileError(token.position(), "not implemented yet: " + what);
    }
}
