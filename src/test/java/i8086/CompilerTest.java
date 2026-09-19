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
        suite.add("Compiler folds a program with nothing unknown in it away",
                CompilerTest::foldsWhatNothingReads);
        suite.add("Compiler compiles arithmetic from variables", CompilerTest::compilesArithmetic);
        suite.add("Compiler keeps the flags under eval and spends them under expr",
                CompilerTest::keepsAndSpendsFlags);
        suite.add("Compiler expands a constant multiply into shifts",
                CompilerTest::expandsMultiply);
        suite.add("Compiler refuses a form whose flags are still wanted",
                CompilerTest::refusesFlagLosingForm);
        suite.add("Compiler gives a dead value's register away", CompilerTest::reusesRegisters);
        suite.add("Compiler keeps a value out of a register an inline block destroys",
                CompilerTest::keepsValuesOffClobbers);
        suite.add("Compiler leaves a dead value in a register an inline block destroys",
                CompilerTest::ignoresClobbersOfDeadValues);
        suite.add("Compiler refuses rather than spilling", CompilerTest::refusesToSpill);
        suite.add("Compiler compiles the control-flow sugar", CompilerTest::compilesSugar);
        suite.add("Compiler reads the signedness of a comparison",
                CompilerTest::readsComparisonSignedness);
        suite.add("Compiler refuses bad input with a position", CompilerTest::refusesBadInput);
        suite.add("Command line prints to standard output without an output file",
                CompilerTest::printsToStandardOutput);
        suite.add("Command line stops at the IR it was given", CompilerTest::emitsIr);
        suite.add("Command line stops at the SSA form", CompilerTest::emitsSsa);
        suite.add("Command line refuses a stage it does not know",
                CompilerTest::refusesUnknownStage);
        suite.add("Command line reports an unreadable input", CompilerTest::reportsUnreadableInput);
        suite.add("Command line refuses an unknown command", CompilerTest::refusesUnknownCommand);
        suite.add("Command line says that assembling does not exist yet",
                CompilerTest::saysAssembleIsMissing);
    }

    /**
     * A program whose every input is a constant, and whose result nobody reads,
     * compiles to nothing at all — and the IR the passes leave shows the same thing
     * in the language it was written in.
     *
     * <p>Both answers are correct: the registers a program leaves behind are not
     * something it promises ({@code docs/ir.md} §2.3), so a value nobody reads may
     * be removed however much arithmetic was written to produce it. A module that
     * wants a particular register to hold something says so, with an inline block
     * or, one day, with a way to pin one.
     */
    private static void foldsWhatNothingReads() {
        String source = "target 8086\norg 0x100\nentry main\n\nmain:\n"
                + "    var x: i16\n    var y: i16\n"
                + "    x = 1\n"
                + "    x = eval(x + 1)\n"
                + "    y = expr(x + x * 4)\n"
                + "    ret\n";
        Assert.assertEquals("org 0x100\n\nmain:\n    ret\n",
                Compiler.compile("t.ir", source));
        Assert.assertEquals("target 8086\norg 0x100\nentry main\n\nmain:\n"
                        + "    var x: i16\n    var y: i16\n    ret\n",
                Compiler.compile("t.ir", source, Compiler.Stage.IR));
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
     *
     * <p>Nothing observes a value yet — there are no loads, no stores and no return
     * value — so a program whose result nobody reads is one the optimiser correctly
     * deletes. Every test here therefore anchors its arithmetic with a conditional
     * branch, which reads the flags: the last operation has to keep them, and what
     * feeds it stays alive.
     */
    private static void compilesArithmetic() {
        Assert.assertEquals("org 0x100\n"
                + "\n"
                + "main:\n"
                + "    mov ax, cx\n"
                + "    inc ax\n"
                + "    mov cx, ax\n"
                + "    shl cx, 1\n"
                + "    shl cx, 1\n"
                + "    mov dx, ax\n"
                + "    add dx, cx\n"
                + "    mov ax, dx\n"
                + "    add ax, 1\n"
                + "    jc l0\n"
                + "\n"
                + "l0:\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry main\n\nmain:\n"
                        + "    var x: i16\n    var y: i16\n    var z: i16\n    var u: i16\n"
                        + "    x = eval(u + 1)\n"
                        + "    y = expr(x + x * 4)\n"
                        + "    z = eval(y + 1)\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    /**
     * The one-word difference between the two forms, and what it buys.
     *
     * <p>{@code eval} promises the flags are the operation's, so as long as anyone
     * reads them the machine has to use a form that leaves them the way an addition
     * does. {@code expr} gives them up, which is what makes the one-byte
     * {@code inc} available — and {@code inc} is one byte precisely because it
     * leaves the carry alone.
     *
     * <p>Which of the two applies is a question about <em>reading</em> rather than
     * about a wish, and it is the question SSA answers: an {@code eval} whose flags
     * nobody reads need not have claimed them, so the pass gives them up and the
     * one-byte form appears. Both halves are in the program below — the first
     * addition gets {@code inc}, the second pays for an {@code add} because the
     * branch reads what it left.
     */
    private static void keepsAndSpendsFlags() {
        Assert.assertEquals("org 0x100\n"
                + "\n"
                + "main:\n"
                + "    inc ax\n"
                + "    mov cx, ax\n"
                + "    add cx, 1\n"
                + "    jc l0\n"
                + "\n"
                + "l0:\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry main\n\nmain:\n"
                        + "    var x: i16\n    var y: i16\n"
                        + "    x = eval(x + 1)\n"
                        + "    y = eval(x + 1)\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    /** The 8086 has no multiply by a constant, so the target hands over a shift trick. */
    private static void expandsMultiply() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry main\n\nmain:\n"
                + "    var x: i16\n    var y: i16\n"
                + "    x = expr(x * 4)\n"
                + "    y = eval(x + 1)\n"
                + "    jc l0\n"
                + "l0:\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("    shl ax, 1\n    shl ax, 1\n"),
                "four times x is two shifts in place: " + assembly);
    }

    /**
     * A multiply whose flags are still wanted is refused, and the refusal says why
     * and what to write instead: the shift trick leaves different flags.
     *
     * <p>The branch is what makes them wanted. Without it the flags are nobody's,
     * the pass gives them up, and the shift trick is allowed — which is the whole
     * difference between a mistake and an optimisation.
     */
    private static void refusesFlagLosingForm() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("t.ir", "target 8086\norg 0x100\nentry main\n\nmain:\n"
                        + "    var x: i16\n"
                        + "    x = eval(x * 4)\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
        Assert.assertTrue(refused.getMessage().contains("different flags"),
                refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("expr(...)"),
                "and says what to write instead: " + refused.getMessage());
    }

    /**
     * A value that is never read again gives its register away: both land in
     * {@code ax}, which is right because the first is finished with by the time the
     * second is wanted.
     *
     * <p>Both are kept rather than deleted because the branches read the flags their
     * additions left. What the test is about is the register, not the survival.
     */
    private static void reusesRegisters() {
        Assert.assertEquals("org 0x100\n"
                + "\n"
                + "main:\n"
                + "    mov ax, cx\n"
                + "    add ax, 1\n"
                + "    jc l0\n"
                + "    mov ax, cx\n"
                + "    add ax, 2\n"
                + "    jc l0\n"
                + "\n"
                + "l0:\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry main\n\nmain:\n"
                        + "    var a: u16\n    var b: u16\n    var u: u16\n"
                        + "    a = eval(u + 1)\n"
                        + "    jc l0\n"
                        + "    b = eval(u + 2)\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    /**
     * A value that is still to be read after an inline block may not live in a
     * register that block declares it destroys.
     *
     * <p>This is the whole of what a clobber list is for. Ignoring it produced
     * code that read a register the block had already destroyed, which is the kind
     * of bug that is invisible until the program runs: `x` was put in `ax`, the
     * block destroyed `ax`, and `mov ax, cx` read the wreckage.
     */
    private static void keepsValuesOffClobbers() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "main:\n"
                        + "    mov cx, ax\n"
                        + "    add cx, 1\n"
                        + "    int 0x21\n"
                        + "    mov ax, cx\n"
                        + "    add ax, 1\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry main\n\nmain:\n"
                        + "    var x: u16\n    var y: u16\n    var u: u16\n"
                        + "    x = eval(u + 1)\n"
                        + "    asm clobbers(ax) {\n        int 0x21\n    }\n"
                        + "    y = eval(x + 1)\n    ret\n"));
    }

    /**
     * And a value whose life is over before the block is left where it is.
     *
     * <p>Striking a register off for a value the block cannot destroy would cost
     * registers for nothing, which on a machine with six of them is not a small
     * thing. Both values here are finished with before the block runs.
     */
    private static void ignoresClobbersOfDeadValues() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "main:\n"
                        + "    mov ax, cx\n"
                        + "    add ax, 1\n"
                        + "    mov cx, ax\n"
                        + "    add cx, 1\n"
                        + "    int 0x21\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry main\n\nmain:\n"
                        + "    var x: u16\n    var y: u16\n    var u: u16\n"
                        + "    x = eval(u + 1)\n"
                        + "    y = eval(x + 1)\n"
                        + "    asm clobbers(ax) {\n        int 0x21\n    }\n"
                        + "    ret\n"));
    }

    /**
     * Seven values alive at once, on a machine with six registers to hold them: a
     * hard error rather than a frame, because a program that needs more registers
     * than the machine has is not quietly given the stack.
     *
     * <p>The inline block at the end is what keeps the seven alive: nothing else can
     * observe a computed value yet, and a block that cannot say what it reads makes
     * every value in its scope count as read ({@code docs/ir.md} §2.3, §9).
     */
    private static void refusesToSpill() {
        StringBuilder source = new StringBuilder("target 8086\norg 0x100\nentry main\n\nmain:\n"
                + "    var y: u16\n    var u: u16\n");
        for (char name = 'a'; name <= 'g'; name++) {
            source.append("    var ").append(name).append(": u16\n");
        }
        for (char name = 'a'; name <= 'g'; name++) {
            source.append("    ").append(name).append(" = eval(u + ").append(name - 'a' + 1)
                    .append(")\n");
        }
        source.append("    y = expr(a + b + c + d + e + f + g)\n")
                .append("    asm clobbers(ax) {\n        int 0x21\n    }\n")
                .append("    ret\n");

        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("t.ir", source.toString()));
        Assert.assertTrue(refused.getMessage().contains("no register left"),
                refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("docs/ir.md"),
                "and points at what that rule means: " + refused.getMessage());
    }

    /**
     * A loop the sugar wrote, compiled, with the passes having been through it.
     *
     * <p>Three things in the output are worth reading twice. {@code n} was the
     * constant 3, so it is written into the comparison and the register that held it
     * is gone. The body's addition is one nobody reads the flags of, so it is
     * written with {@code expr} and the machine's one-byte {@code inc} appears. And
     * the φ that merges the two values of {@code i} is nowhere, because leaving SSA
     * renames both of them back to {@code i} and the copy it would need is an
     * identity ({@code docs/ssa.md} §8).
     */
    private static void compilesSugar() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry main\n\nmain:\n"
                + "    var i: u16\n    var n: u16\n"
                + "    i = 0\n    n = 3\n"
                + "    .while i < n\n        i = eval(i + 1)\n    .endw\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("    jmp $lbl1\n\n$lbl0:\n    inc ax\n"),
                "the loop body comes first and the test is jumped to: " + assembly);
        Assert.assertTrue(assembly.contains("$lbl1:\n    cmp ax, 3\n    jc $lbl0\n"),
                "and the constant is folded into the comparison: " + assembly);
    }

    /**
     * The one word that decides between two instructions several layers down:
     * {@code i16} compares signed and {@code u16} unsigned, and each says so in
     * the mnemonic.
     */
    private static void readsComparisonSignedness() {
        String signed = Compiler.compile("t.ir", comparisonProgram("i16"));
        String unsigned = Compiler.compile("t.ir", comparisonProgram("u16"));
        Assert.assertTrue(signed.contains("    jge $lbl0\n"),
                "a signed less-than leaves on jge: " + signed);
        Assert.assertTrue(unsigned.contains("    jnc $lbl0\n"),
                "an unsigned one leaves on jnc: " + unsigned);
    }

    private static String comparisonProgram(String type) {
        return "target 8086\norg 0x100\nentry main\n\nmain:\n"
                + "    var x: " + type + "\n    var z: u16\n"
                + "    x = 0\n"
                + "    .if x < 0\n        z = 1\n    .endif\n"
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

    /** No {@code -o} means standard output, which is what a dump wants. */
    private static void printsToStandardOutput() {
        Run run = run("optimize", "examples/hello.ir");
        Assert.assertEquals(0L, run.status);
        Assert.assertEquals(EXPECTED_ASM, run.out);
        Assert.assertEquals("", run.err);
    }

    /**
     * {@code --emit ir} stops before anything is selected, so a program the
     * instruction selector cannot compile can still be printed back.
     */
    private static void emitsIr() {
        Run run = run("optimize", "--emit", "ir", "examples/hello.ir");
        Assert.assertEquals(0L, run.status);
        Assert.assertTrue(run.out.startsWith("target 8086\norg 0x100\nentry main\n"), run.out);
        Assert.assertTrue(run.out.contains("asm clobbers(ax, dx, flags) {"), run.out);
    }

    /** {@code --emit ssa} is where the renamed form can be looked at. */
    private static void emitsSsa() {
        Run run = run("optimize", "--emit", "ssa", "examples/hello.ir");
        Assert.assertEquals(0L, run.status);
        Assert.assertTrue(run.out.startsWith("; SSA form of target 8086"), run.out);
        Assert.assertTrue(run.out.contains("block0 (main):"), run.out);
    }

    private static void refusesUnknownStage() {
        Run run = run("optimize", "--emit", "bake", "examples/hello.ir");
        Assert.assertEquals(2L, run.status);
        Assert.assertTrue(run.err.contains("unknown --emit 'bake'"), run.err);
        Assert.assertTrue(run.err.contains("expected ir, ssa or asm"), run.err);
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
