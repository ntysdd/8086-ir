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
import java.util.LinkedHashSet;
import java.util.List;
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

    private final String file;
    private final List<Token> tokens;
    private int index;
    private Target target;

    private IrParser(String file, List<Token> tokens) {
        this.file = file;
        this.tokens = tokens;
    }

    public static Module parse(String file, String source) {
        return new IrParser(file, Tokenizer.tokenize(file, source)).parseModule();
    }

    private Module parseModule() {
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
            items.add(parseItem());
            skipNewlines();
        }
        return new Module(target, origin.intValue(), entry, entryPosition, items);
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

    private Item parseItem() {
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
            Expression expression = parseParenthesisedExpression();
            endOfLine();
            return new Item.Eval(keyword.position(), expression);
        }
        if (first.is(TokenKind.IDENT) && target.condition(first.name()) != null) {
            next();
            String where = expect(TokenKind.IDENT, "a label to branch to").name();
            endOfLine();
            return new Item.Branch(first.position(), target.condition(first.name()), where);
        }
        if (first.is(TokenKind.IDENT)) {
            throw notImplemented(first, statementDescription(first));
        }
        throw new CompileError(first.position(),
                "expected a label, a data definition, 'var', an assignment, 'ret', 'asm', "
                        + "'cmp', 'jmp' or a condition, but found " + first.describe());
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
            return new Value.Eval(first.position(), parseParenthesisedExpression());
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
        if (operatorHere() == Operator.COMPLEMENT) {
            next();
            return new Expression.Complement(at.position(), parseUnaryExpression());
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
        return token.is(TokenKind.IDENT) && isNameFollowing(TokenKind.PUNCT, ":")
                && tokenAt(2).is("[");
    }

    private Token tokenAt(int offset) {
        int at = index + offset;
        return at < tokens.size() ? tokens.get(at) : tokens.get(tokens.size() - 1);
    }

    private MemoryOperand parseMemoryOperand() {
        Token start = peek();
        Size size = null;
        if (start.is(TokenKind.IDENT) && Size.named(start.name()) != null) {
            size = Size.named(start.name());
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
        return new MemoryOperand(start.position(), size, segment, base, displacement);
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
        if (name.startsWith(".if") || name.startsWith(".else")
                || name.startsWith(".end") || name.startsWith(".while")) {
            return "control flow sugar (docs/ir.md §7.2)";
        }
        return "a statement beginning with '" + first.text() + "'";
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
