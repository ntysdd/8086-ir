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
        suite.add("Compiler gives a register back across a call",
                CompilerTest::redefinesAroundACall);
        suite.add("Compiler does not hold a register for a value that is dead across a label",
                CompilerTest::keepsADeadValueOffTheLabelsRegister);
        suite.add("Compiler keeps a value that crosses a merge in one register",
                CompilerTest::loopCarriedValue);
        suite.add("Compiler keeps a value out of a register an inline block destroys",
                CompilerTest::keepsValuesOffClobbers);
        suite.add("Compiler leaves a dead value in a register an inline block destroys",
                CompilerTest::ignoresClobbersOfDeadValues);
        suite.add("Compiler refuses rather than spilling", CompilerTest::refusesToSpill);
        suite.add("Compiler keeps a value in its home across a call",
                CompilerTest::keepsAValueInItsHome);
        suite.add("Compiler refuses that value when it has no home",
                CompilerTest::refusesAValueWithNoHome);
        suite.add("Compiler leaves a home alone when the value fits in a register",
                CompilerTest::leavesAnUnneededHomeAlone);
        suite.add("Compiler keeps a value out of a cell the program writes",
                CompilerTest::refusesToKeepAValueWhereTheProgramWrites);
        suite.add("Compiler refuses a value no register is free to move",
                CompilerTest::refusesWhenNoRegisterIsFreeToMoveAValue);
        suite.add("Compiler moves another value to its home to free a register",
                CompilerTest::movesAnotherValueToItsHome);
        suite.add("Compiler sets the machine's segmentation state up",
                CompilerTest::setsSegmentsUp);
        suite.add("Compiler keeps a value out of the register a segment move uses",
                CompilerTest::keepsValuesOutOfTheSegmentScratch);
        suite.add("Compiler keeps a segment set up that nothing reads",
                CompilerTest::keepsSegmentationState);
        suite.add("Compiler puts a byte value in its home", CompilerTest::putsAByteValueInItsHome);
        suite.add("Compiler compiles the control-flow sugar", CompilerTest::compilesSugar);
        suite.add("Compiler reads through a pointer and writes through a label",
                CompilerTest::loadsAndStores);
        suite.add("Compiler gives an address a register that can hold one",
                CompilerTest::addressesLiveInAddressRegisters);
        suite.add("Compiler keeps a volatile read nobody uses", CompilerTest::keepsVolatileReads);
        suite.add("Compiler refuses an access wider than a register",
                CompilerTest::refusesWideAccess);
        suite.add("Compiler walks a byte string a byte at a time",
                CompilerTest::walksAByteString);
        suite.add("Compiler loads, changes and stores a byte", CompilerTest::worksOnBytes);
        suite.add("Compiler refuses a multiply on bytes", CompilerTest::refusesAByteMultiply);
        suite.add("Compiler refuses five byte values at one point",
                CompilerTest::refusesFiveLiveBytes);
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
        suite.add("Command line warns on standard error, not in the program",
                CompilerTest::warnsOnStandardError);
        suite.add("Compiler collects the warnings of a program it compiles",
                CompilerTest::collectsWarnings);
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
                + "    inc cx\n"
                + "    mov ax, cx\n"
                + "    shl ax, 1\n"
                + "    shl ax, 1\n"
                + "    add cx, ax\n"
                + "    add cx, 1\n"
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
                + "    add ax, 1\n"
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
     * A value that is never read again gives its register away, and on this machine that
     * has to be visible somewhere: a value used as an address has three registers to live
     * in and not six, so four of them one after another fit only because each one's
     * register is free by the time the next one is wanted.
     *
     * <p>Both halves are in the output. The four pointers are all in {@code bx} and the
     * four loads all in {@code ax}, which is the reuse; and the test would be a refusal
     * rather than a program if a register were kept for a value nobody reads again.
     *
     * <p>Each load is stored because a value nobody reads is a value the optimiser is
     * right to delete — the first version of this test had four dead loads and compiled to
     * one.
     */
    private static void reusesRegisters() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov bx, $msg1\n"
                        + "    mov ax, [bx]\n"
                        + "    mov [0x40], ax\n"
                        + "    mov bx, $msg2\n"
                        + "    mov ax, [bx]\n"
                        + "    mov [0x41], ax\n"
                        + "    mov bx, $msg3\n"
                        + "    mov ax, [bx]\n"
                        + "    mov [0x42], ax\n"
                        + "    mov bx, $msg4\n"
                        + "    mov ax, [bx]\n"
                        + "    mov [0x43], ax\n"
                        + "    ret\n"
                        + "\n"
                        + "$msg1: dw 1\n"
                        + "\n"
                        + "$msg2: dw 2\n"
                        + "\n"
                        + "$msg3: dw 3\n"
                        + "\n"
                        + "$msg4: dw 4\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var p1: u16\n    var p2: u16\n    var p3: u16\n    var p4: u16\n"
                        + "    var x: u16\n"
                        + "    p1 = msg1\n    x = [p1]\n    volatile [0x40] = x\n"
                        + "    p2 = msg2\n    x = [p2]\n    volatile [0x41] = x\n"
                        + "    p3 = msg3\n    x = [p3]\n    volatile [0x42] = x\n"
                        + "    p4 = msg4\n    x = [p4]\n    volatile [0x43] = x\n"
                        + "    ret\n"
                        + "\n"
                        + "msg1: dw 1\nmsg2: dw 2\nmsg3: dw 3\nmsg4: dw 4\n"));
    }

    /**
     * A value that is still to be read after an inline block may not live in a
     * register that block declares it destroys.
     *
     * <p>This is the whole of what a clobber list is for. Ignoring it produced
     * code that read a register the block had already destroyed, which is the kind
     * of bug that is invisible until the program runs: `x` was put in `ax`, the
     * block destroyed `ax`, and `mov ax, cx` read the wreckage.
     *
     * <p>Nothing but {@code cx} appears in the output now, which is the point made
     * twice over: the value is in a register the block keeps, and it needs no copy to
     * get there, because the value it was computed from dies at that copy.
     */
    private static void keepsValuesOffClobbers() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    add cx, 1\n"
                        + "    int 0x21\n"
                        + "    add cx, 1\n"
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
                        + "    add ax, 1\n"
                        + "    add ax, 1\n"
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
     * A name written on both sides of a call is two values and not one — and the
     * allocator is only told that the names a φ joins have to share a register, so the
     * register the first value used is free again after the call.
     *
     * <p>This is what {@code docs/ir.md} §8.2 had written down as open: "a name that is
     * redefined around a call is kept out of everything the call destroys even though
     * nothing of it is alive there". The form gives the two definitions two names, the
     * allocator sees two lives, and the second one may live in a register the call
     * destroys — because the call happens before that value exists. Both land in
     * {@code ax} here, which is the point: it was given back.
     */
    private static void redefinesAroundACall() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov ax, 5\n"
                        + "    mov [0x40], ax\n"
                        + "    int 0x10\n"
                        + "    mov ax, 7\n"
                        + "    mov [0x41], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var n: u16\n"
                        + "    n = 5\n"
                        + "    volatile [0x40] = n\n"
                        + "    int 0x10\n"
                        + "    n = 7\n"
                        + "    volatile [0x41] = n\n"
                        + "    ret\n"));
    }

    /**
     * A value that is dead across a label does not hold a register across it, which is the
     * difference between knowing where a value is alive and reading that off the distance
     * between its first and last mention.
     *
     * <p>Four pointers, each defined before a label and read after it, and never two of them
     * alive at once. Reading a life off the instruction stream gave each of them the whole
     * function — the allocator before this one did exactly that, because a straight line
     * cannot tell which side of a label anything is on — so four values that each want one of
     * three address registers were refused. This program is the smallest one that says so,
     * and it says it by compiling: all four pointers are in the same register, one after
     * another, because each is finished with before the next is defined.
     */
    private static void keepsADeadValueOffTheLabelsRegister() {
        StringBuilder source = new StringBuilder("target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var i: u16\n    var x: u16\n");
        for (int number = 1; number <= 4; number++) {
            source.append("    var p").append(number).append(": u16\n");
        }
        source.append("    i = 0\n");
        for (int number = 1; number <= 4; number++) {
            source.append("    p").append(number).append(" = msg").append(number).append('\n')
                    .append("    cmp i, 0\n")
                    .append("    jz next").append(number).append('\n')
                    .append("next").append(number).append(":\n")
                    .append("    x = [p").append(number).append("]\n")
                    .append("    volatile [0x4").append(number).append("] = x\n");
        }
        source.append("    ret\n\nmsg1: dw 1\nmsg2: dw 2\nmsg3: dw 3\nmsg4: dw 4\n");

        String assembly = Compiler.compile("t.ir", source.toString());
        for (int number = 1; number <= 4; number++) {
            Assert.assertTrue(assembly.contains("    mov bx, $msg" + number + "\n"), assembly);
        }
        Assert.assertFalse(assembly.contains("    mov si, "),
                "one address register is enough for all four: " + assembly);
        Assert.assertFalse(assembly.contains("    mov di, "),
                "and a second is not reached for: " + assembly);
    }

    /**
     * The other half of that: a value that crosses a merge is <em>one</em> value, because
     * there is no copy at a merge to put it anywhere ({@code docs/ssa.md} §8) — the
     * register is what carries it along each path.
     *
     * <p>The loop is where that shows. The counter is written before the loop, read and
     * written inside it, and read by the test at the bottom; the φ joins all three, so
     * they are one life and the increment happens in place. Without that the loop would
     * cost a copy per turn and the value the test reads would be the wrong one.
     */
    private static void loopCarriedValue() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov ax, 0\n"
                        + "    jmp ..@lbl1\n"
                        + "\n"
                        + "..@lbl0:\n"
                        + "    inc ax\n"
                        + "\n"
                        + "..@lbl1:\n"
                        + "    cmp ax, 3\n"
                        + "    jc ..@lbl0\n"
                        + "    mov [0x40], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var i: u16\n    var n: u16\n"
                        + "    i = 0\n"
                        + "    n = 3\n"
                        + "    .while i < n\n"
                        + "        i = eval(i + 1)\n"
                        + "    .endw\n"
                        + "    volatile [0x40] = i\n"
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

    // --- homes (docs/ir.md §3.1.2) -----------------------------------------

    /**
     * A value that has to live across an interrupt which destroys every register, with or
     * without the bytes its declaration could keep it in.
     *
     * <p>The value comes from memory so that nothing can fold it away, and every register is
     * destroyed by the call, so there is no register it can wait in: without a home this is
     * {@link #refusesToSpill}'s refusal and nothing else.
     */
    private static String acrossACall(String home) {
        return "target 8086\n"
                + "org 0x100\n"
                + "entry $main\n"
                + "\n"
                + "$tries: pad 2\n"
                + "\n"
                + "$main:\n"
                + "    var $left: u16" + home + "\n"
                + "    $left = word [0x40]\n"
                + "    int 0x13 clobbers(ax, bx, cx, dx, si, di)\n"
                + "    $left = eval($left - 1)\n"
                + "    word [0x42] = $left\n"
                + "    ret\n";
    }

    /**
     * What a home is for: the value waits in the bytes the program named while the call that
     * destroys every register happens, and comes back afterwards.
     */
    private static void keepsAValueInItsHome() {
        String assembly = Compiler.compile("across.ir", acrossACall(" in $tries"));
        int stored = assembly.indexOf("mov word [$tries], ax");
        int called = assembly.indexOf("int 0x13");
        int loaded = assembly.indexOf("mov ax, word [$tries]");
        Assert.assertTrue(stored >= 0, "the value is stored into its home: " + assembly);
        Assert.assertTrue(loaded >= 0, "and read back out of it: " + assembly);
        Assert.assertTrue(called > stored && called < loaded,
                "with the call in between, which is the whole point: " + assembly);
        Assert.assertTrue(assembly.contains("mov word [0x42], ax"),
                "and the program's own use reads the value that came back: " + assembly);
    }

    /**
     * The other half of that: the same program without a home is refused, and the refusal is
     * about registers rather than about memory the compiler made up ({@code docs/ir.md} §8.2).
     */
    private static void refusesAValueWithNoHome() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("across.ir", acrossACall("")));
        Assert.assertTrue(refused.getMessage().contains("no register left for 'left'"),
                refused.getMessage());
    }

    /** A value in a register where the pressure is low: the same program, with a home on it. */
    private static String withinOneRegister(String home) {
        return "target 8086\n"
                + "org 0x100\n"
                + "entry $main\n"
                + "\n"
                + "$tries: pad 2\n"
                + "\n"
                + "$main:\n"
                + "    var $left: u16" + home + "\n"
                + "    $left = word [0x40]\n"
                + "    $left = eval($left + 2)\n"
                + "    word [0x42] = $left\n"
                + "    ret\n";
    }

    /**
     * A value that fits in a register stays in one and its home is never touched, so a program
     * that declares one and turns out not to need it has paid nothing: the two assemblies here
     * are the same program, and the entry point onwards is the same text.
     */
    private static void leavesAnUnneededHomeAlone() {
        String plain = body(Compiler.compile("plain.ir", withinOneRegister("")));
        String homed = body(Compiler.compile("homed.ir", withinOneRegister(" in $tries")));
        Assert.assertEquals(plain, homed);
        Assert.assertFalse(homed.contains("$tries"),
                "and the bytes are not written: " + homed);
    }

    /** Everything from the entry point on: the program, without the data in front of it. */
    private static String body(String assembly) {
        int at = assembly.indexOf("$main:");
        Assert.assertTrue(at >= 0, "the entry point is in the assembly: " + assembly);
        return assembly.substring(at);
    }

    // --- the machine's segmentation state (docs/ir.md §8.1) -----------------

    /**
     * The first lines of every boot loader: the segmentation state, in the sequences the target
     * declared. {@code sp} takes the value directly, and a segment register does not — a segment
     * register takes no immediate, so the value goes through {@code ax}.
     */
    private static void setsSegmentsUp() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x7c00\nentry $main\n\n"
                + "$main:\n"
                + "    movseg ds, 0\n"
                + "    movseg sp, 0x7c00\n"
                + "    movseg es, 0xb800\n"
                + "    movseg ds, cs\n"
                + "    ret\n");
        Assert.assertEquals("org 0x7c00\n\n$main:\n"
                + "    mov ax, 0\n"
                + "    mov ds, ax\n"
                + "    mov sp, 0x7c00\n"
                + "    mov ax, 0xb800\n"
                + "    mov es, ax\n"
                + "    mov ax, cs\n"
                + "    mov ds, ax\n"
                + "    ret\n", assembly);
    }

    /**
     * The sequence a segment move needs writes {@code ax}, and the allocator is told: a value alive
     * across the move is kept out of that register, because the register is written by the sequence
     * and not by anything the program wrote.
     */
    private static void keepsValuesOutOfTheSegmentScratch() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n"
                + "$main:\n"
                + "    var x: u16\n"
                + "    x = word [0x40]\n"
                + "    movseg ds, 0\n"
                + "    word [0x42] = x\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("mov ax, 0\n    mov ds, ax\n"), assembly);
        Assert.assertFalse(assembly.contains("mov word [0x42], ax"),
                "the value did not wait in the register the sequence uses: " + assembly);
    }

    /**
     * Setting the machine's state is not something a pass may remove. There is no value to be dead
     * and nothing after it has to read it for the program to need it — the segment is what every
     * later access goes through ({@code docs/ir.md} §2.3, §8.1).
     */
    private static void keepsSegmentationState() {
        Assert.assertEquals("org 0x100\n\n$main:\n    mov ax, 0x1234\n    mov ds, ax\n    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    movseg ds, 0x1234\n"
                        + "    ret\n"));
    }

    /**
     * The value has a home, and the program writes those same bytes while the value is alive.
     * Then the home cannot hold it — the bytes would not still be its value — and the value has
     * to be in a register across the write or the program is refused. Never a value quietly lost
     * ({@code docs/ir.md} §3.1.2).
     */
    private static String acrossACallWritingTheHome(boolean writesTheCell) {
        return "target 8086\n"
                + "org 0x100\n"
                + "entry $main\n"
                + "\n"
                + "$cell: pad 2\n"
                + "\n"
                + "$main:\n"
                + "    var $left: u16 in $cell\n"
                + "    $left = word [0x40]\n"
                + (writesTheCell ? "    word [$cell] = 7\n" : "")
                + "    int 0x13 clobbers(ax, bx, cx, dx, si, di)\n"
                + "    word [0x42] = $left\n"
                + "    ret\n";
    }

    private static void refusesToKeepAValueWhereTheProgramWrites() {
        String assembly = Compiler.compile("kept.ir", acrossACallWritingTheHome(false));
        Assert.assertTrue(assembly.contains("mov word [$cell], ax"),
                "nothing else is in the way, so the value waits in the cell: " + assembly);

        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("written.ir", acrossACallWritingTheHome(true)));
        Assert.assertTrue(
                refused.getMessage().contains("its home 'cell' is written by the program"),
                refused.getMessage());
    }

    /**
     * Seven values alive at one point, one of them in a home — and, when asked for, a home for one
     * of the other six as well.
     *
     * <p>Seven values and six registers is more than the machine has, and a value in a home still
     * needs a register at the point it is read or written. So the only way this compiles is by
     * moving one of the six into a home of its own: without a second home there is nothing to move
     * and the program is refused, and with one the allocator moves it. Which is what "no spill"
     * means here — the compiler does not invent storage, so the only register it can free is one a
     * value is waiting in on the program's own instructions.
     *
     * <p>The flags are what keeps {@code eval} from becoming {@code expr}: a value computed with
     * {@code expr} is put into a register of its own first, and then this would be a program about
     * temporaries rather than about homes.
     */
    private static String sevenValuesAtOnePoint(boolean secondHome) {
        StringBuilder source = new StringBuilder("target 8086\norg 0x100\nentry $main\n\n"
                + "$cell: pad 2\n");
        if (secondHome) {
            source.append("$spare: pad 2\n");
        }
        source.append("\n$main:\n");
        for (char name = 'a'; name <= 'f'; name++) {
            source.append("    var ").append(name).append(": u16");
            if (secondHome && name == 'f') {
                source.append(" in $spare");
            }
            source.append('\n');
        }
        source.append("    var h: u16 in $cell\n");
        for (char name = 'a'; name <= 'f'; name++) {
            source.append("    ").append(name).append(" = word [0x").append(name - 'a' + 40)
                    .append("]\n");
        }
        source.append("    h = word [0x4c]\n")
                .append("    h = eval(h - a)\n")
                .append("    jz done\n")
                .append("done:\n");
        for (char name = 'a'; name <= 'f'; name++) {
            source.append("    word [0x").append(name - 'a' + 0x4e).append("] = ").append(name)
                    .append('\n');
        }
        source.append("    word [0x60] = h\n    ret\n");
        return source.toString();
    }

    private static void refusesWhenNoRegisterIsFreeToMoveAValue() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("t.ir", sevenValuesAtOnePoint(false)));
        Assert.assertTrue(refused.getMessage().contains("no register free to move"),
                refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("its home 'cell'"),
                "and the refusal names the home it could not use: " + refused.getMessage());
    }

    private static void movesAnotherValueToItsHome() {
        String assembly = Compiler.compile("t.ir", sevenValuesAtOnePoint(true));
        int freed = assembly.indexOf("mov word [$spare], ");
        int needed = assembly.indexOf("mov word [$cell], ");
        Assert.assertTrue(freed >= 0, "a value waits in the second home: " + assembly);
        Assert.assertTrue(needed > freed,
                "and it was moved there before the register was needed: " + assembly);
    }

    /**
     * A byte value waiting in its home across a call that destroys every register: the access is
     * one byte wide, and the value lives in the low half of whichever register moves it
     * ({@code docs/ir.md} §3.1.2, §3.2).
     */
    private static void putsAByteValueInItsHome() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n"
                + "$cell: pad 2\n\n$main:\n"
                + "    var h: u8 in $cell\n"
                + "    var x: u8\n"
                + "    h = 3\n"
                + "    x = 4\n"
                + "    int 0x13 clobbers(ax, bx, cx, dx, si, di)\n"
                + "    cmp h, x\n"
                + "    jz done\n"
                + "done:\n"
                + "    ret\n");
        int stored = assembly.indexOf("mov byte [$cell], ");
        int called = assembly.indexOf("int 0x13");
        Assert.assertTrue(stored >= 0 && called > stored,
                "the byte goes into its home one byte wide, before the call: " + assembly);
        Assert.assertTrue(assembly.indexOf(", byte [$cell]", called) > called,
                "and comes back out of it one byte wide afterwards: " + assembly);
        Assert.assertFalse(assembly.contains("byte [$cell], ax"),
                "nor with a whole register, which would write past it: " + assembly);
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
        // What the rule about what an instruction destroys is for: `mov cl, 8` writes a
        // register no value was given, so the value the shift is about may not be there —
        // whichever register that turns out to be, which is the allocator's answer and not
        // this test's.
        Assert.assertTrue(assembly.contains("    mov cl, 8\n    shl "), assembly);
        Assert.assertFalse(assembly.contains("shl cx, cl"),
                "nothing shifts the value that was destroyed: " + assembly);
        Assert.assertFalse(assembly.contains("shl cl, cl"),
                "and the count is still the count: " + assembly);
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
     * itself and was dropped. And neither operand is in {@code ax} or {@code dx}, the two
     * registers a multiply uses, so what the sequence copies into {@code ax} is a real
     * copy.
     *
     * <p>Which registers they are instead is not the test's business — the allocator
     * decides that by colouring a graph, and the names in it are the program's, not this
     * test's.
     */
    private static void multiplies() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov bx, cx\n"
                        + "    inc bx\n"
                        + "    add cx, 2\n"
                        + "    mov ax, bx\n"
                        + "    mul cx\n"
                        + "    mov [0x40], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", twoValues("*")));
    }

    private static void divides() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov bx, cx\n"
                        + "    inc bx\n"
                        + "    add cx, 2\n"
                        + "    mov ax, bx\n"
                        + "    xor dx, dx\n"
                        + "    div cx\n"
                        + "    mov [0x40], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", twoValues("/")));
    }

    /** The same division, read from the other half of the answer. */
    private static void remainders() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov bx, cx\n"
                        + "    inc bx\n"
                        + "    add cx, 2\n"
                        + "    mov ax, bx\n"
                        + "    xor dx, dx\n"
                        + "    div cx\n"
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
        Assert.assertTrue(signed.contains("    cwd\n    idiv "),
                "a signed division sign-extends the dividend: " + signed);
        Assert.assertTrue(unsigned.contains("    xor dx, dx\n    div "),
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
     * The loop every boot loader and every DOS program starts with, and the reason byte accesses had
     * to come before anything else: a string is bytes, and a byte is read one byte at a time
     * ({@code docs/ir.md} §3.4).
     */
    private static void walksAByteString() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n"
                + "$msg: db \"Hi\", 0\n\n$main:\n"
                + "    var p: u16\n"
                + "    var c: u8\n"
                + "    p = $msg\n"
                + "loop:\n"
                + "    c = byte [p]\n"
                + "    cmp c, 0\n"
                + "    jz done\n"
                + "    p = eval(p + 1)\n"
                + "    jmp loop\n"
                + "done:\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("byte ["),
                "the character is read one byte at a time: " + assembly);
        Assert.assertFalse(assembly.contains("word ["),
                "and never as a word, which would read the character after it too: " + assembly);
        Assert.assertTrue(assembly.contains("jz $done") || assembly.contains("jz done"),
                "and the terminator is what leaves the loop: " + assembly);
    }

    /** A byte is loaded, changed in place and stored back, all at one byte's width. */
    private static void worksOnBytes() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var c: u8\n"
                + "    var p: u16\n"
                + "    p = 0x1000\n"
                + "    c = byte [0x40]\n"
                + "    c = eval(c + 1)\n"
                + "    [p] = c\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("byte [0x40]"),
                "the load is one byte wide: " + assembly);
        Assert.assertTrue(assembly.contains("inc "),
                "and one byte is added to it: " + assembly);
        Assert.assertTrue(assembly.contains("mov [bx], "),
                "and it goes back out with the width the value has, which is one byte: "
                        + assembly);
    }

    /**
     * Multiplication and division are done in registers the machine names itself, and the sequences
     * the target declares are written for that sixteen-bit pair. A byte form exists and the target
     * has not been asked for it, so this is refused rather than computed at the wrong width
     * ({@code docs/ir.md} §6.1).
     */
    private static void refusesAByteMultiply() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var c: u8\n"
                        + "    var d: u8\n"
                        + "    c = byte [0x40]\n"
                        + "    d = byte [0x41]\n"
                        + "    c = eval(c * d)\n"
                        + "    cmp c, 0\n"
                        + "    jz done\n"
                        + "done:\n"
                        + "    ret\n"));
        Assert.assertTrue(refused.getMessage().contains("cannot be used with '*'"),
                refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("written for a word"),
                "and says why: " + refused.getMessage());
    }

    /**
     * A byte value lives in the low half of a register, so only four registers can hold one where a
     * word has six. Five of them alive at once is therefore a refusal, and the refusal says why
     * {@code si} and {@code di} are not being used ({@code docs/ir.md} §3.2, §8.2).
     */
    private static void refusesFiveLiveBytes() {
        StringBuilder source = new StringBuilder("target 8086\norg 0x100\nentry $main\n\n$main:\n");
        for (char name = 'a'; name <= 'e'; name++) {
            source.append("    var ").append(name).append(": u8\n");
        }
        for (char name = 'a'; name <= 'e'; name++) {
            source.append("    ").append(name).append(" = byte [0x").append(name - 'a' + 40)
                    .append("]\n");
        }
        source.append("again:\n");
        for (char name = 'a'; name <= 'e'; name++) {
            source.append("    cmp ").append(name).append(", 0\n    jz done\n");
        }
        source.append("    jmp again\ndone:\n    ret\n");

        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("t.ir", source.toString()));
        Assert.assertTrue(refused.getMessage().contains("no register left"),
                refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("only live in the registers that have a low "
                + "half, which is four"), "and counts the registers a byte has: "
                + refused.getMessage());
    }

    private static void refusesWideAccess() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var wide: u32\n"
                        + "    wide = volatile dword [msg]\n"
                        + "    ret\n"
                        + "\n"
                        + "$msg: dw 0x1234, 0x5678\n"));
        Assert.assertTrue(refused.getMessage().contains("two registers at once"),
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

    /**
     * A warning goes to standard error and never into the program on standard output:
     * {@code optimize ... > x.asm} is how a file is made, and a warning in the middle of
     * the assembly would be an assembler error. A warning is also not a failure, so the
     * status says nothing about it.
     */
    private static void warnsOnStandardError() {
        File input = writeTemporary("warned.ir", SHARED_CELL);
        Run run = run("optimize", input.getPath());
        Assert.assertEquals(0L, run.status);
        Assert.assertTrue(run.err.startsWith(input.getPath() + ":11:5: warning: "), run.err);
        Assert.assertTrue(run.err.contains("'cell' is the home of 'kept' and 'other'"), run.err);
        Assert.assertFalse(run.out.contains("warning"),
                "the program stays a program: " + run.out);
        Assert.assertTrue(run.out.contains("mov word [$cell], ax"),
                "and the store into the cell is there: " + run.out);
    }

    /**
     * The same thing one level down, where a caller has somewhere to put the warnings: the
     * collector is filled by the time the call returns, so a caller never has to ask twice
     * or reach into the compiler for them.
     */
    private static void collectsWarnings() {
        Warnings warnings = new Warnings();
        String assembly = Compiler.compile("warned.ir", SHARED_CELL, Compiler.Stage.NASM,
                warnings);
        Assert.assertEquals(1L, warnings.all().size());
        Assert.assertEquals("warned.ir:11:5", warnings.all().get(0).position().toString());
        Assert.assertTrue(assembly.contains("mov word [$cell], ax"), assembly);
        // And a program with nothing to say leaves the collector empty.
        Warnings quiet = new Warnings();
        Compiler.compile("hello.ir", readExample(), Compiler.Stage.NASM, quiet);
        Assert.assertEquals(0L, quiet.all().size());
    }

    /**
     * A program that saves into bytes two variables call their home, with the store reading
     * a value nobody can fold so that the assembly shows the store rather than a literal.
     */
    private static final String SHARED_CELL =
            "target 8086\n"
                    + "org 0x100\n"
                    + "entry $main\n"
                    + "\n"
                    + "$cell: pad 2\n"
                    + "\n"
                    + "$main:\n"
                    + "    var $kept: u16 in $cell\n"
                    + "    var $other: u16 in $cell\n"
                    + "    $kept = word [0x40]\n"
                    + "    word [$cell] = $kept\n"
                    + "    ret\n";

    /**
     * Writes a program under {@code build/}, which is where a test may put a file: the
     * command line has to be given a real path, and this keeps the repository's own
     * directories out of it.
     */
    private static File writeTemporary(String name, String source) {
        File file = new File("build", name);
        try {
            Files.write(file.toPath(), source.getBytes(UTF_8));
            return file;
        } catch (IOException failure) {
            Assert.fail("cannot write " + file + ": " + failure.getMessage());
            return null; // unreachable: fail always throws
        }
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
