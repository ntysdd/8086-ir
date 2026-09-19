package i8086;

import i8086.cli.Main;
import i8086.testing.Assert;
import i8086.testing.Suite;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * End to end tests: a real example file goes through the compiler and comes out
 * as assembly.
 *
 * <p>The shipped example is read from disk on purpose. A file that ships with
 * the project and no longer compiles is a broken promise, and this is what
 * notices.
 */
public final class CompilerTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final String EXPECTED_ASM =
            "org 0x100\n"
                    + "\n"
                    + "main:\n"
                    + "    mov ah, 9\n"
                    + "    mov dx, offset msg\n"
                    + "    int 0x21\n"
                    + "    ret\n"
                    + "\n"
                    + "msg: db \"Hello, world!$\"\n";

    private CompilerTest() {
    }

    public static void register(Suite suite) {
        suite.add("Compiler turns the shipped example into assembly",
                CompilerTest::compilesShippedExample);
        suite.add("Compiler compiles arithmetic from variables", CompilerTest::compilesArithmetic);
        suite.add("Compiler keeps the flags under eval and spends them under expr",
                CompilerTest::keepsAndSpendsFlags);
        suite.add("Compiler expands a constant multiply into shifts",
                CompilerTest::expandsMultiply);
        suite.add("Compiler refuses a form whose flags are still wanted",
                CompilerTest::refusesFlagLosingForm);
        suite.add("Compiler gives a dead value's register away", CompilerTest::reusesRegisters);
        suite.add("Compiler refuses rather than spilling", CompilerTest::refusesToSpill);
        suite.add("Compiler refuses bad input with a position", CompilerTest::refusesBadInput);
        suite.add("Command line reports an unreadable input", CompilerTest::reportsUnreadableInput);
        suite.add("Command line refuses an unknown command", CompilerTest::refusesUnknownCommand);
        suite.add("Command line refuses a missing output file", CompilerTest::refusesMissingOutput);
        suite.add("Command line says that assembling does not exist yet",
                CompilerTest::saysAssembleIsMissing);
    }

    private static void compilesShippedExample() {
        Assert.assertEquals(EXPECTED_ASM, Compiler.compile("examples/hello.ir", readExample()));
    }

    private static String readExample() {
        try {
            return new String(Files.readAllBytes(new File("examples/hello.ir").toPath()), UTF_8);
        } catch (IOException failure) {
            Assert.fail("cannot read examples/hello.ir: " + failure.getMessage());
            return null; // unreachable: fail always throws
        }
    }

    /**
     * The whole path on the arithmetic a person would actually write, including
     * the temporary the multiply needs.
     */
    private static void compilesArithmetic() {
        Assert.assertEquals("org 0x100\n"
                + "\n"
                + "main:\n"
                + "    mov ax, 1\n"
                + "    add ax, 1\n"
                + "    mov cx, ax\n"
                + "    shl cx, 1\n"
                + "    shl cx, 1\n"
                + "    mov dx, ax\n"
                + "    add dx, cx\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry main\n\nmain:\n"
                        + "    var x: i16\n    var y: i16\n"
                        + "    x = 1\n"
                        + "    x = eval(x + 1)\n"
                        + "    y = expr(x + x * 4)\n"
                        + "    ret\n"));
    }

    /**
     * The same program twice, differing in one word, and the difference is what
     * the two forms are for: {@code eval} promises the flags are the operation's,
     * so {@code add} it is; {@code expr} gives them up, so the machine's one-byte
     * {@code inc} becomes available — and it is one byte precisely because it
     * leaves the carry alone.
     */
    private static void keepsAndSpendsFlags() {
        Assert.assertTrue(
                Compiler.compile("t.ir", arithmetic("x = eval(x + 1)", "y = 0"))
                        .contains("    add ax, 1\n"),
                "eval keeps the flags, so it pays for an add");
        Assert.assertTrue(
                Compiler.compile("t.ir", arithmetic("x = expr(x + 1)", "y = 0"))
                        .contains("    inc ax\n"),
                "expr gives them up, so the smaller instruction is available");
    }

    /** The 8086 has no multiply by a constant, so the target hands over a shift trick. */
    private static void expandsMultiply() {
        String assembly = Compiler.compile("t.ir", arithmetic("x = expr(x * 4)", "y = 0"));
        Assert.assertTrue(assembly.contains("    shl ax, 1\n    shl ax, 1\n"),
                "four times x is two shifts in place: " + assembly);
    }

    /**
     * A multiply whose flags are still wanted is refused, and the refusal says why
     * and what to write instead: the shift trick leaves different flags.
     */
    private static void refusesFlagLosingForm() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("t.ir", arithmetic("x = eval(x * 4)", "y = 0")));
        Assert.assertTrue(refused.getMessage().contains("different flags"),
                refused.getMessage());        Assert.assertTrue(refused.getMessage().contains("expr(...)"),
                "and says what to write instead: " + refused.getMessage());
    }

    /**
     * A value that is never read again gives its register away. Both stores land in
     * {@code ax}, which is correct only because the first value is dead — removing
     * the dead store itself waits for the optimiser.
     */
    private static void reusesRegisters() {
        Assert.assertEquals("org 0x100\n\nmain:\n    mov ax, 1\n    mov ax, 2\n    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry main\n\nmain:\n"
                        + "    var a: u16\n    var b: u16\n    a = 1\n    b = 2\n    ret\n"));
    }

    /**
     * Seven values alive at once, on a machine with six registers to hold them: a
     * hard error rather than a frame, because a program that needs more registers
     * than the machine has is not quietly given the stack.
     */
    private static void refusesToSpill() {
        String source = "target 8086\norg 0x100\nentry main\n\nmain:\n"
                + "    var a: u16\n    var b: u16\n    var c: u16\n    var d: u16\n"
                + "    var e: u16\n    var f: u16\n    var g: u16\n"
                + "    var y: u16\n"
                + "    a = 1\n    b = 2\n    c = 3\n    d = 4\n    e = 5\n    f = 6\n    g = 7\n"
                + "    y = eval(a + b)\n    y = eval(c + d)\n    y = eval(e + f)\n"
                + "    y = eval(g + y)\n    ret\n";
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("t.ir", source));
        Assert.assertTrue(refused.getMessage().contains("no register left"),
                refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("docs/ir.md"),
                "and points at what that rule means: " + refused.getMessage());
    }

    /** A program with one statement to compile, with the rest filled in. */
    private static String arithmetic(String first, String second) {
        return "target 8086\norg 0x100\nentry main\n\nmain:\n"
                + "    var x: i16\n    var y: i16\n"
                + "    " + first + "\n"
                + "    " + second + "\n"
                + "    ret\n";
    }

    private static void refusesBadInput() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("bad.ir", "target 8086\norg 0\nentry a\nnonsense\n"));
        Assert.assertEquals("bad.ir:4:1", refused.position().toString());
    }

    private static void reportsUnreadableInput() {
        Run run = run("optimize", "no/such/file.ir", "-o", "out.asm");
        Assert.assertEquals(1L, run.status);
        Assert.assertTrue(run.err.contains("cannot read no/such/file.ir"), run.err);
    }

    private static void refusesUnknownCommand() {
        Run run = run("bake", "in.ir", "-o", "out.asm");
        Assert.assertEquals(2L, run.status);
        Assert.assertTrue(run.err.contains("unknown command 'bake'"), run.err);
    }

    private static void refusesMissingOutput() {
        Run run = run("optimize", "examples/hello.ir");
        Assert.assertEquals(2L, run.status);
        Assert.assertTrue(run.err.contains("expected an input file and '-o OUTPUT'"), run.err);
    }

    private static void saysAssembleIsMissing() {
        Run run = run("assemble", "hello.asm", "-o", "hello.bin");
        Assert.assertEquals(1L, run.status);
        Assert.assertTrue(run.err.contains("not implemented yet"), run.err);
    }

    /** Runs the command line with the given arguments and captures what it said. */
    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(out);
        PrintStream errStream = new PrintStream(err);
        int status = Main.run(args, outStream, errStream);
        outStream.flush();
        errStream.flush();
        return new Run(status,
                new String(out.toByteArray(), UTF_8),
                new String(err.toByteArray(), UTF_8));
    }

    /** What a command line run produced. */
    private static final class Run {
        private final int status;
        private final String out;
        private final String err;

        Run(int status, String out, String err) {
            this.status = status;
            this.out = out;
            this.err = err;
        }
    }
}
