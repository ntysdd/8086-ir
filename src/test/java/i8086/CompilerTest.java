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
                    + "$main:\n"
                    + "    mov ah, 9\n"
                    + "    mov dx, $msg\n"
                    + "    int 0x21\n"
                    + "    ret\n"
                    + "\n"
                    + "$msg: db \"Hello, world!$\"\n";

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
        suite.add("Compiler reads through a pointer and writes through a label",
                CompilerTest::loadsAndStores);
        suite.add("Compiler gives an address a register that can hold one",
                CompilerTest::addressesLiveInAddressRegisters);
        suite.add("Compiler keeps a volatile read nobody uses", CompilerTest::keepsVolatileReads);
        suite.add("Compiler refuses a byte access", CompilerTest::refusesNarrowAccess);
        suite.add("Compiler shifts by a large count through cl", CompilerTest::countsLargeShifts);
        suite.add("Compiler keeps a small shift to single steps", CompilerTest::repeatsSmallShifts);
        suite.add("Compiler keeps a value out of the register a shift destroys",
                CompilerTest::keepsValuesOffShiftCounts);
        suite.add("Compiler multiplies two values the way the machine does",
                CompilerTest::multiplies);
        suite.add("Compiler divides in dx:ax and reads the quotient", CompilerTest::divides);
        suite.add("Compiler takes the remainder from dx", CompilerTest::remainders);
        suite.add("Compiler reads the signedness of a division",
                CompilerTest::readsDivisionSignedness);
        suite.add("Compiler puts a literal divisor in a register",
                CompilerTest::literalDivisor);
        suite.add("Compiler refuses a division whose flags are read",
                CompilerTest::refusesDivisionWithLiveFlags);
        suite.add("Compiler allows a multiply whose flags are read",
                CompilerTest::allowsMultiplyWithLiveFlags);
        suite.add("Compiler refuses a store of a computed value",
                CompilerTest::refusesComputedStore);
        suite.add("Compiler reads the signedness of a comparison",
                CompilerTest::readsComparisonSignedness);
        suite.add("Compiler compiles an instruction-shaped statement as the eval form",
                CompilerTest::instructionSpellingIsTheEvalForm);
        suite.add("Compiler negates with the machine's one instruction",
                CompilerTest::negatesInOneInstruction);
        suite.add("Compiler still builds the zero for a subtraction from zero",
                CompilerTest::buildsTheZeroForSubtractionFromZero);
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
        String source = "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var $x: i16\n    var $y: i16\n"
                + "    x = 1\n"
                + "    x = eval(x + 1)\n"
                + "    y = expr(x + x * 4)\n"
                + "    ret\n";
        Assert.assertEquals("org 0x100\n\n$main:\n    ret\n",
                Compiler.compile("t.ir", source));
        Assert.assertEquals("target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $x: i16\n    var $y: i16\n    ret\n",
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
     * The two spellings of one operation compile to the same code.
     *
     * <p>That is what "a spelling and not a new operation" has to mean
     * ({@code docs/ir.md} §7.3): the instruction-shaped statement is the same IR, so
     * not only the optimiser but selection and allocation see one program.
     */
    private static void instructionSpellingIsTheEvalForm() {
        String head = "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var s: i16\n    var p: i16\n    p = 0x1000\n    s = [p]\n";
        String tail = "    word [0x40] = s\n    ret\n";
        Assert.assertEquals(
                Compiler.compile("t.ir", head
                        + "    s = eval(s + 1)\n    s = eval(-s)\n    s = eval(~s)\n" + tail),
                Compiler.compile("t.ir", head
                        + "    add s, 1\n    neg s\n    not s\n" + tail));
    }

    /**
     * A negation is {@code NEG}, which is two bytes, rather than a zero that is built
     * and subtracted from.
     */
    private static void negatesInOneInstruction() {
        Assert.assertEquals("org 0x100\n"
                + "\n"
                + "$main:\n"
                + "    mov bx, 0x1000\n"
                + "    mov ax, [bx]\n"
                + "    neg ax\n"
                + "    mov word [0x40], ax\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var s: i16\n    var p: i16\n    p = 0x1000\n    s = [p]\n"
                        + "    s = eval(-s)\n    word [0x40] = s\n    ret\n"));
    }

    /**
     * {@code d = eval(0 - d)} is the same operation as {@code d = eval(-d)}, and it is
     * still written out as subtracting from a zero.
     *
     * <p>This test says where the gap is rather than that all is well: recognising the
     * shape is a target's business — a form for the subtraction whose first operand is
     * the literal zero — and until there is one, the spelling that names the operation
     * is the one that gets the instruction ({@code docs/ir.md} §7.3).
     */
    private static void buildsTheZeroForSubtractionFromZero() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var s: i16\n    var p: i16\n    p = 0x1000\n    s = [p]\n"
                + "    s = eval(0 - s)\n    word [0x40] = s\n    ret\n");
        Assert.assertFalse(assembly.contains("neg"), assembly);
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
                + "$main:\n"
                + "    mov ax, cx\n"
                + "    inc ax\n"
                + "    mov cx, ax\n"
                + "    shl cx, 1\n"
                + "    shl cx, 1\n"
                + "    mov dx, ax\n"
                + "    add dx, cx\n"
                + "    mov ax, dx\n"
                + "    add ax, 1\n"
                + "    jc $l0\n"
                + "\n"
                + "$l0:\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $x: i16\n    var $y: i16\n    var z: i16\n    var u: i16\n"
                        + "    x = eval(u + 1)\n"
                        + "    y = expr(x + x * 4)\n"
                        + "    z = eval(y + 1)\n"
                        + "    jc $l0\n"
                        + "$l0:\n"
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
                + "$main:\n"
                + "    inc ax\n"
                + "    mov cx, ax\n"
                + "    add cx, 1\n"
                + "    jc $l0\n"
                + "\n"
                + "$l0:\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $x: i16\n    var $y: i16\n"
                        + "    x = eval(x + 1)\n"
                        + "    y = eval(x + 1)\n"
                        + "    jc $l0\n"
                        + "$l0:\n"
                        + "    ret\n"));
    }

    /** The 8086 has no multiply by a constant, so the target hands over a shift trick. */
    private static void expandsMultiply() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var $x: i16\n    var $y: i16\n"
                + "    x = expr(x * 4)\n"
                + "    y = eval(x + 1)\n"
                + "    jc $l0\n"
                + "$l0:\n"
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
                () -> Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $x: i16\n"
                        + "    x = eval(x * 4)\n"
                        + "    jc $l0\n"
                        + "$l0:\n"
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
                + "$main:\n"
                + "    mov ax, cx\n"
                + "    add ax, 1\n"
                + "    jc $l0\n"
                + "    mov ax, cx\n"
                + "    add ax, 2\n"
                + "    jc $l0\n"
                + "\n"
                + "$l0:\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var a: u16\n    var b: u16\n    var u: u16\n"
                        + "    a = eval(u + 1)\n"
                        + "    jc $l0\n"
                        + "    b = eval(u + 2)\n"
                        + "    jc $l0\n"
                        + "$l0:\n"
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
                        + "$main:\n"
                        + "    mov cx, ax\n"
                        + "    add cx, 1\n"
                        + "    int 0x21\n"
                        + "    mov ax, cx\n"
                        + "    add ax, 1\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
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
                        + "$main:\n"
                        + "    mov ax, cx\n"
                        + "    add ax, 1\n"
                        + "    mov cx, ax\n"
                        + "    add cx, 1\n"
                        + "    int 0x21\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
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
        StringBuilder source = new StringBuilder("target 8086\norg 0x100\nentry $main\n\n$main:\n"
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
     * A load and a store, through both kinds of address the surface has.
     *
     * <p>{@code [p]} is a load through a value, so {@code p} has to be in a register
     * that can be inside brackets — {@code bx}, {@code si} or {@code di}, and on this
     * machine nothing else. {@code [msg]} is a load from a fixed address, which needs
     * no register at all, and the store back is written the same way.
     */
    private static void loadsAndStores() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov bx, $msg\n"
                        + "    mov ax, [bx]\n"
                        + "    mov [$msg], ax\n"
                        + "    ret\n"
                        + "\n"
                        + "$msg: dw 0x1234\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var x: u16\n    var p: u16\n"
                        + "    p = msg\n"
                        + "    x = [p]\n"
                        + "    [msg] = x\n"
                        + "    ret\n"
                        + "\n"
                        + "$msg: dw 0x1234\n"));
    }

    /**
     * The same thing said twice, to pin the reason: {@code ax} is not a register an
     * address can live in, and a value used as an address is a value with three
     * places to live rather than six.
     */
    private static void addressesLiveInAddressRegisters() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var x: u16\n    var p: u16\n"
                + "    p = msg\n"
                + "    x = [p]\n"
                + "    [msg] = x\n"
                + "    ret\n"
                + "\n"
                + "$msg: dw 0x1234\n");
        Assert.assertTrue(assembly.contains("    mov bx, $msg\n"),
                "the address went into an address register: " + assembly);
        Assert.assertFalse(assembly.contains("mov ax, $msg"),
                "and not into one it cannot live in: " + assembly);
    }

    /**
     * A read marked {@code volatile} happens because the program said so, not because
     * the value is wanted: the register it lands in is dead here and the read stays
     * anyway. The plain read from the image, right beside it, is gone — nothing uses
     * its value and no program can tell whether it happened.
     */
    private static void keepsVolatileReads() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov ax, [0x40]\n"
                        + "    mov word [bx], 5\n"
                        + "    ret\n"
                        + "\n"
                        + "$msg: dw 0x1234\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var x: u16\n    var y: u16\n    var p: u16\n"
                        + "    x = [msg]\n"
                        + "    y = volatile [0x40]\n"
                        + "    word [p] = 5\n"
                        + "    ret\n"
                        + "\n"
                        + "$msg: dw 0x1234\n"));
    }

    /**
     * A count too big to repeat: {@code mov cl, n; shl r, cl} is four bytes whatever
     * the count, and three single shifts cost six.
     */
    private static void countsLargeShifts() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var v: u16\n    var w: u16\n"
                + "    w = eval(v shl 8)\n"
                + "    volatile [0x40] = w\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("    mov cl, 8\n    shl ax, cl\n"),
                "the count goes through cl rather than eight shifts: " + assembly);
    }

    /** And two shifts stay two shifts: the count register would cost more than it saves. */
    private static void repeatsSmallShifts() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var v: u16\n    var w: u16\n"
                + "    w = expr(v shl 2)\n"
                + "    volatile [0x40] = w\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("    shl ax, 1\n    shl ax, 1\n"),
                "two single shifts: " + assembly);
        Assert.assertFalse(assembly.contains("cl"),
                "and no count register at all: " + assembly);
    }

    /**
     * The whole point of knowing what an instruction destroys: {@code mov cl, 8}
     * writes a register no value was given, so a value that is still to be read after
     * it may not be living in {@code cx}.
     *
     * <p>{@code b} is live across the shift and would otherwise be the second value
     * allocated — {@code ax} is taken and {@code cx} is next — so without this rule
     * the shift would destroy the value it was computing from. What the test pins is
     * that it went somewhere the instruction does not touch.
     */
    private static void keepsValuesOffShiftCounts() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var a: u16\n    var b: u16\n    var u: u16\n    var s: u16\n"
                + "    a = eval(u + 1)\n"
                + "    b = eval(u shl 8)\n"
                + "    s = eval(a + b)\n"
                + "    volatile [0x40] = s\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("    mov cl, 8\n    shl dx, cl\n"),
                "the shifted value is not in the register the count arrives in: " + assembly);
        Assert.assertFalse(assembly.contains("shl cx, cl"),
                "and nothing shifts the value that was destroyed: " + assembly);
    }

    /**
     * A program with two computed values and one to put their result in, so that the
     * machine's own registers can be read off the answer.
     */
    private static String twoValues(String operation) {
        return "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var a: u16\n    var b: u16\n    var q: u16\n    var e: u16\n"
                + "    a = eval(e + 1)\n"
                + "    b = eval(e + 2)\n"
                + "    q = eval(a " + operation + " b)\n"
                + "    volatile [0x40] = q\n"
                + "    ret\n";
    }

    /**
     * Multiplication, which the machine does to whatever is in {@code ax}.
     *
     * <p>Two things in the output are the point. The copies the sequence is written
     * with are gone where the allocator found them unnecessary: the answer goes into
     * {@code ax} and stays there, so the copy back out is a copy from a register into
     * itself and was dropped. And {@code b} is in {@code bx} rather than the natural
     * {@code dx}, because {@code mul} destroys {@code dx} and {@code b} is still being
     * read.
     */
    private static void multiplies() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov cx, ax\n"
                        + "    inc cx\n"
                        + "    mov bx, ax\n"
                        + "    add bx, 2\n"
                        + "    mov ax, cx\n"
                        + "    mul bx\n"
                        + "    mov [0x40], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", twoValues("*")));
    }

    private static void divides() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov cx, ax\n"
                        + "    inc cx\n"
                        + "    mov bx, ax\n"
                        + "    add bx, 2\n"
                        + "    mov ax, cx\n"
                        + "    xor dx, dx\n"
                        + "    div bx\n"
                        + "    mov [0x40], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", twoValues("/")));
    }

    /** The same division, read from the other half of the answer. */
    private static void remainders() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov cx, ax\n"
                        + "    inc cx\n"
                        + "    mov bx, ax\n"
                        + "    add bx, 2\n"
                        + "    mov ax, cx\n"
                        + "    xor dx, dx\n"
                        + "    div bx\n"
                        + "    mov ax, dx\n"
                        + "    mov [0x40], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", twoValues("%")));
    }

    /**
     * {@code div} and {@code idiv} are not two spellings of one instruction, and the
     * signed one needs the dividend's sign in {@code dx} — which is what {@code cwd}
     * does, and it is the same instruction a {@code movsx} of {@code ax} would be.
     */
    private static void readsDivisionSignedness() {
        String signed = Compiler.compile("t.ir", signedValues());
        String unsigned = Compiler.compile("t.ir", twoValues("/"));
        Assert.assertTrue(signed.contains("    cwd\n    idiv bx\n"),
                "a signed division sign-extends the dividend: " + signed);
        Assert.assertTrue(unsigned.contains("    xor dx, dx\n    div bx\n"),
                "and an unsigned one clears it: " + unsigned);
    }

    /** The signed twin of {@link #twoValues}, with the same shape. */
    private static String signedValues() {
        return "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var a: i16\n    var b: i16\n    var q: i16\n    var e: i16\n"
                + "    a = eval(e + 1)\n"
                + "    b = eval(e + 2)\n"
                + "    q = eval(a / b)\n"
                + "    volatile [0x40] = q\n"
                + "    ret\n";
    }

    /**
     * The machine divides by a register or a memory operand, never by an immediate,
     * and a division cannot swap its sides — so a literal divisor is put in one. That
     * register is clobbered, which the allocator finds out the same way it finds out
     * about any other register the selector wrote by hand.
     */
    private static void literalDivisor() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var a: u16\n    var q: u16\n"
                + "    a = eval(a + 1)\n"
                + "    q = eval(a / 10)\n"
                + "    volatile [0x40] = q\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("    mov bx, 0xa\n"),
                "the literal divisor goes into a register: " + assembly);
        Assert.assertTrue(assembly.contains("    div bx\n"),
                "and the division reads that register: " + assembly);
    }

    /**
     * A division leaves the flags undefined on this machine, and the sequence clears
     * {@code dx} on the way through them. So it may only be used where nobody can
     * still read them — which the surface says by writing {@code expr}, or by not
     * reading them at all.
     */
    private static void refusesDivisionWithLiveFlags() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var a: u16\n    var b: u16\n    var q: u16\n"
                        + "    q = eval(a / b)\n"
                        + "    jc $l0\n"
                        + "$l0:\n"
                        + "    ret\n"));
        Assert.assertTrue(refused.getMessage().contains("expr(...)"),
                "and says what to write instead: " + refused.getMessage());
    }

    /** A multiply does leave the flags the multiplication leaves, so a branch may read them. */
    private static void allowsMultiplyWithLiveFlags() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var a: u16\n    var b: u16\n    var q: u16\n"
                + "    a = eval(a + 1)\n"
                + "    b = eval(b + 1)\n"
                + "    q = eval(a * b)\n"
                + "    jc $l0\n"
                + "$l0:\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("    mul "),
                "a multiply whose flags are read is ordinary: " + assembly);
    }

    /**
     * A byte load would need {@code al}, and this back end has no way to name half a
     * register. The refusal says that rather than refusing in general terms, because
     * that is the piece of the target description that is missing.
     */
    private static void refusesNarrowAccess() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var b: u8\n"
                        + "    b = volatile byte [msg]\n"
                        + "    ret\n"
                        + "\n"
                        + "$msg: dw 0x1234\n"));
        Assert.assertTrue(refused.getMessage().contains("half of one"),
                "the refusal says what is missing: " + refused.getMessage());
    }

    private static void refusesComputedStore() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var y: u16\n    var p: u16\n"
                        + "    y = eval(y + 1)\n"
                        + "    [p] = eval(y + 1)\n"
                        + "    ret\n"));
        Assert.assertTrue(refused.getMessage().contains("put the value in a variable"),
                "and says what to write instead: " + refused.getMessage());
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
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var i: u16\n    var n: u16\n"
                + "    i = 0\n    n = 3\n"
                + "    .while i < n\n        i = eval(i + 1)\n    .endw\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("    jmp ..@lbl1\n\n..@lbl0:\n    inc ax\n"),
                "the loop body comes first and the test is jumped to: " + assembly);
        Assert.assertTrue(assembly.contains("..@lbl1:\n    cmp ax, 3\n    jc ..@lbl0\n"),
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
        Assert.assertTrue(signed.contains("    jge ..@lbl0\n"),
                "a signed less-than leaves on jge: " + signed);
        Assert.assertTrue(unsigned.contains("    jnc ..@lbl0\n"),
                "an unsigned one leaves on jnc: " + unsigned);
    }

    private static String comparisonProgram(String type) {
        return "target 8086\norg 0x100\nentry $main\n\n$main:\n"
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
        Assert.assertTrue(run.out.startsWith("target 8086\norg 0x100\nentry $main\n"), run.out);
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
        Assert.assertTrue(run.err.contains("expected ir, ssa or nasm"), run.err);
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
