package i8086.emit;

import i8086.Compiler;
import i8086.target.Target;
import i8086.target.Targets;
import i8086.testing.Assert;
import i8086.testing.Suite;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One width per instruction, over the assembly the compiler writes.
 *
 * <p>This is a property test and not a golden: it says what has to be true of every instruction in
 * every program, so it covers programs nobody wrote a golden for. Being at the text is the point —
 * the widths of a register are facts about the names in the line, and the lines are what an
 * assembler is given. It is also where the two mixed-width bugs this compiler has had would have
 * been caught on their way out rather than by NASM, which is not part of the build
 * ({@code README.md}, "Tests").
 *
 * <p>What it checks, and what it deliberately does not: the registers <b>outside</b> brackets in
 * one instruction have one width, because one width per register class is what says which register
 * a value lives in. Registers inside brackets are addresses, and are another matter. The exception
 * is a count, which the target is asked about ({@link Target#hasAByteCount}) — a shift is one
 * instruction whatever the width of what it shifts, and its count is a byte because the machine
 * takes it from {@code cl}.
 *
 * <p>What it cannot check is anything about meaning: an instruction can be all one width and still
 * compute the wrong thing. That is what the must-not tests and the goldens are for
 * ({@code AGENTS.md}, invariant 4).
 */
public final class InstructionWidthsTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /**
     * Programs the property is asked of, the shapes as well as the shipped examples.
     *
     * <p>The shipped ones are read from disk, so this grows with them. The shapes are here because
     * the generator does not tell the compiler what width anything is: a byte value, a word value,
     * and a temporary that outlives the statement that made it are three different paths through
     * selection, and the mixed-width bugs were on the paths a golden happened not to walk.
     */
    private static final List<String> SHAPES = Arrays.asList(
            // A byte compared with a literal, where the literal takes the width of the byte.
            "    var c: u8\n    c = byte [0x40]\n    cmp c, 0x80\n    ret\n",
            // The same value compared with a word, through a widening.
            "    var c: u8\n    var w: u16\n    c = byte [0x40]\n    w = movzx c\n    cmp w, 0x80\n"
                    + "    ret\n",
            // A temporary: an operand that has to live across the statement that reads it.
            "    var a: u8\n    var b: u8\n    var x: u8\n    a = byte [0x40]\n    b = byte [0x42]\n"
                    + "    x = eval(a + b)\n    [0x44] = x\n    [0x46] = b\n    ret\n",
            // A tree whose destination is narrower than its operands' registers.
            "    var a: u16\n    var b: u16\n    var x: u16\n    a = word [0x40]\n    b = word [0x42]\n"
                    + "    x = expr(a + b * 3)\n    [0x44] = x\n    ret\n",
            // A shift by a value: the count is a byte and the value is not.
            "    var x: u16\n    var n: u16\n    x = word [0x40]\n    n = word [0x42]\n"
                    + "    x = eval(x shl n)\n    [0x44] = x\n    ret\n",
            // Narrowing and widening in the same program.
            "    var w: u16\n    var c: u8\n    w = word [0x40]\n    c = byte w\n    c = eval(c + 1)\n"
                    + "    w = movzx c\n    [0x44] = w\n    ret\n",
            // Two bytes into one word, which is halves of ax.
            "    var x: u8\n    var y: u8\n    var w: u16\n    var v: u16\n    var t: u16\n"
                    + "    x = byte [0x40]\n    y = byte [0x42]\n    w = movzx x\n    v = movzx y\n"
                    + "    t = expr(w * 256 + v)\n    [0x44] = t\n    ret\n",
            // A copy, whose count register and pointers are all words.
            "    var src: u16\n    var dst: u16\n    src = 0x7E00\n    dst = 0x8000\n    cld\n"
                    + "    rep movsb with cx = 0x200, si = src, di = dst\n    ret\n");

    private InstructionWidthsTest() {
    }

    public static void register(Suite suite) {
        suite.add("Assembly writes one width per instruction", InstructionWidthsTest::oneWidthEach);
        suite.add("Assembly writes a shift's count as a byte",
                InstructionWidthsTest::aCountIsAByte);
        suite.add("The width check refuses a mixed instruction", InstructionWidthsTest::refusesAMix);
    }

    private static void oneWidthEach() {
        int checked = 0;
        for (String program : SHAPES) {
            checked += check(Compiler.compile("t.ir", HEAD + program));
        }
        for (String path : shipped()) {
            checked += check(Compiler.compile(path, read(path)));
        }
        Assert.assertTrue(checked >= 80,
                "the examples and the shapes have this many instructions between them, and a check "
                        + "that stopped seeing any would be a check that had stopped being asked: "
                        + checked);
    }

    /**
     * And the exception is the one the target names: a byte count under a word shift, and nothing
     * else. Without this the property would be true of an empty set of exceptions, which is the
     * shape a check that is never asked has.
     */
    private static void aCountIsAByte() {
        Target target = Targets.byName("8086");
        Assert.assertTrue(target.hasAByteCount("shl"), "shl shifts by a count");
        Assert.assertTrue(target.hasAByteCount("sar"), "sar shifts by a count");
        Assert.assertFalse(target.hasAByteCount("add"), "add adds a value of the size it adds to");
        Assert.assertFalse(target.hasAByteCount("cmp"), "a comparison is the width of its operands");
        String shifted = Compiler.compile("t.ir", HEAD
                + "    var x: u16\n    var n: u16\n    x = word [0x40]\n    n = word [0x42]\n"
                + "    x = eval(x shl n)\n    [0x44] = x\n    ret\n");
        Assert.assertTrue(shifted.contains("    shl dx, cl\n"),
                "the count is a byte under a word value: " + shifted);
    }

    /** The check has teeth: this is what it is for, and it has to refuse one. */
    private static void refusesAMix() {
        try {
            check("org 0x100\n\n$main:\n    cmp ax, cl\n    ret\n");
            Assert.fail("a word register and a byte register in one comparison was accepted");
        } catch (IllegalStateException refused) {
            Assert.assertTrue(refused.getMessage().contains("width"), refused.getMessage());
        }
        // And the count is not a loophole: the width of the value being shifted still has to match
        // the rest of the instruction.
        try {
            check("org 0x100\n\n$main:\n    cmp dx, cl\n    shl dx, cl\n    ret\n");
            Assert.fail("a mixed comparison in front of a shift was accepted");
        } catch (IllegalStateException refused) {
            Assert.assertTrue(refused.getMessage().contains("width"), refused.getMessage());
        }
    }

    // --- the check itself --------------------------------------------------

    private static final String HEAD = "target 8086\norg 0x100\nentry $main\n\n$main:\n";

    /**
     * Checks one program's assembly, and answers with how many instructions it looked at.
     *
     * <p>A line is an instruction when it is not a directive, not a label, and not data: those are
     * the lines whose first word is one of the assembler's own, or a name with a colon after it.
     */
    private static int check(String assembly) {
        Target target = Targets.byName("8086");
        int checked = 0;
        for (String line : lines(assembly)) {
            List<String> words = words(line);
            if (words.isEmpty() || isData(words) || endsWithColon(words.get(0))) {
                continue;
            }
            checked++;
            widthsOfInstruction(words, target);
        }
        return checked;
    }

    /**
     * The registers outside the brackets of one instruction, which all have one width.
     *
     * <p>The count of a shift is the exception, and only where it is the last thing in the line: a
     * shift may take a byte count and a word value, and nothing else here may take two widths.
     */
    private static void widthsOfInstruction(List<String> words, Target target) {
        List<Integer> widths = new ArrayList<Integer>();
        List<String> registers = new ArrayList<String>();
        int depth = 0;
        for (String word : words) {
            depth += openings(word) - closings(word);
            if (depth > 0 || !target.isRegister(word)) {
                continue;
            }
            widths.add(Integer.valueOf(target.registerBytes(word)));
            registers.add(word);
        }
        String mnemonic = words.get(0).toLowerCase();
        if (target.hasAByteCount(mnemonic) && widths.size() > 1
                && widths.get(widths.size() - 1).intValue() == 1) {
            widths.remove(widths.size() - 1);
            registers.remove(registers.size() - 1);
        }
        for (int i = 1; i < widths.size(); i++) {
            if (!widths.get(i).equals(widths.get(0))) {
                throw new IllegalStateException("'" + registers.get(0) + "' is "
                        + widths.get(0) + " byte(s) and '" + registers.get(i) + "' is "
                        + widths.get(i) + " byte(s), and one instruction has one width: "
                        + registers + " in '" + String.join(" ", words) + "'");
            }
        }
    }

    private static int openings(String word) {
        return count(word, '[');
    }

    private static int closings(String word) {
        return count(word, ']');
    }

    private static int count(String text, char character) {
        int found = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == character) {
                found++;
            }
        }
        return found;
    }

    private static boolean isData(List<String> words) {
        String first = words.get(0).toLowerCase();
        return first.equals("org") || first.equals("entry") || first.equals("db")
                || first.equals("dw") || first.equals("dd") || first.equals("times")
                || first.equals("pad") || first.equals("target");
    }

    private static boolean endsWithColon(String word) {
        return word.endsWith(":");
    }

    // --- the text ---------------------------------------------------------

    /**
     * The lines of the assembly, with the comments and the blank ones dropped.
     *
     * <p>Words are separated the way the assembler separates them: a comma is punctuation between
     * operands and not part of one, and brackets do not hide what is inside them from this counting
     * — it is the depth of the bracket that decides that ({@link #widthsOfInstruction}).
     */
    private static List<String> lines(String assembly) {
        List<String> lines = new ArrayList<String>();
        for (String line : assembly.split("\n")) {
            String text = line;
            int comment = text.indexOf(';');
            if (comment >= 0) {
                text = text.substring(0, comment);
            }
            text = text.trim();
            if (!text.isEmpty()) {
                lines.add(text);
            }
        }
        return lines;
    }

    private static List<String> words(String line) {
        List<String> words = new ArrayList<String>();
        for (String word : line.replace(",", " ").split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    /** The examples that ship, which this property is asked of as well. */
    private static List<String> shipped() {
        List<String> paths = new ArrayList<String>();
        File directory = new File("examples");
        String[] names = directory.list();
        if (names != null) {
            Arrays.sort(names);
            for (String name : names) {
                if (name.endsWith(".ir")) {
                    paths.add("examples/" + name);
                }
            }
        }
        return paths;
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(new File(path).toPath()), UTF_8);
        } catch (IOException failure) {
            Assert.fail("cannot read " + path + ": " + failure.getMessage());
            return null; // unreachable: fail always throws
        }
    }
}
