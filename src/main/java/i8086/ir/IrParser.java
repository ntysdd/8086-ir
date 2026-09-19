package i8086.ir;

import i8086.CompileError;
import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.asm.Size;
import i8086.asm.Token;
import i8086.asm.TokenKind;
import i8086.asm.Tokenizer;

import java.util.ArrayList;
import java.util.List;

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

    private static final String TARGET_8086 = "8086";
    private static final int MAX_ORIGIN = 0xFFFF;

    private final String file;
    private final List<Token> tokens;
    private int index;

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
        return new Module(target, origin.intValue(), entry, items);
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
        require(name.name().equals(TARGET_8086), name.position(),
                "unsupported target '" + name.text() + "'; the only target is " + TARGET_8086);
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

        if (first.is(TokenKind.IDENT) && isNameFollowing(TokenKind.PUNCT, ":")) {
            next();
            next();
            return afterLabel(first);
        }
        if (first.is(TokenKind.IDENT) && Size.fromDirective(first.name()) != null) {
            return parseData(next(), null);
        }
        if (first.isName("ret")) {
            next();
            endOfLine();
            return new Item.Return(first.position());
        }
        if (first.isName("asm")) {
            return parseInlineAsm();
        }
        if (first.is(TokenKind.IDENT)) {
            throw notImplemented(first, statementDescription(first));
        }
        throw new CompileError(first.position(),
                "expected a label, a data definition, 'ret' or 'asm', but found " + first.describe());
    }

    private static String statementDescription(Token first) {
        String name = first.name();
        if (name.equals("var")) {
            return "a variable declaration (docs/ir.md §3.2)";
        }
        if (name.equals("cmp") || name.equals("test")) {
            return "'" + name + "' (docs/ir.md §4.4)";
        }
        if (name.equals("eval") || name.equals("expr")) {
            return "'" + name + "' (docs/ir.md §5)";
        }
        if (name.equals("setcc") || name.startsWith("set")) {
            return "the setcc family (docs/ir.md §4.4)";
        }
        if (name.startsWith(".if") || name.startsWith(".else")
                || name.startsWith(".end") || name.startsWith(".while")) {
            return "control flow sugar (docs/ir.md §7.2)";
        }
        return "an assignment or a statement beginning with '" + first.text() + "'";
    }

    /** What follows a label on its own line: a data definition, or nothing. */
    private Item afterLabel(Token label) {
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
        require(!atoms.isEmpty(), peek().position(), "the brackets are empty");
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
