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
        suite.add("Compiler keeps the example loop in registers",
                CompilerTest::keepsTheExampleLoopInRegisters);
        suite.add("Compiler keeps the copies around an operation with a form",
                CompilerTest::keepsTheCopiesAroundAnOperationWithAForm);
        suite.add("Compiler writes a literal into the register its sequence names",
                CompilerTest::writesALiteralIntoTheRegisterItsSequenceNames);
        suite.add("Compiler divides by a power of two by shifting",
                CompilerTest::dividesByAPowerOfTwoByShifting);
        suite.add("Compiler keeps dividing where a trick would be wrong",
                CompilerTest::keepsDividingWhereATrickWouldBeWrong);
        suite.add("Compiler keeps a temporary for a tree on the right",
                CompilerTest::keepsATemporaryForATreeOnTheRight);
        suite.add("Compiler compiles arithmetic from variables", CompilerTest::compilesArithmetic);
        suite.add("Compiler keeps the flags under eval and spends them under expr",
                CompilerTest::keepsAndSpendsFlags);
        suite.add("Compiler builds a zero by clearing the register",
                CompilerTest::buildsAZeroByClearing);
        suite.add("Compiler moves a zero the flags are still waiting on",
                CompilerTest::movesAZeroTheFlagsAreWaitingOn);
        suite.add("Compiler moves a zero between two comparisons",
                CompilerTest::movesAZeroBetweenTwoComparisons);
        suite.add("Compiler builds a zero where the flags are already spent",
                CompilerTest::buildsAZeroWhereTheFlagsAreAlreadySpent);
        suite.add("Compiler leaves a byte's zero as a move", CompilerTest::leavesABytesZeroAsAMove);
        suite.add("Compiler tests a value against zero without the zero",
                CompilerTest::testsAgainstZero);
        suite.add("Compiler tests a sugar's zero the same way",
                CompilerTest::testsASugarsZeroTheSameWay);
        suite.add("Compiler tests a byte against zero", CompilerTest::testsAByteAgainstZero);
        suite.add("Compiler leaves a test the writer wrote alone",
                CompilerTest::leavesATestThatWasWritten);
        suite.add("Compiler compares a literal with a byte",
                CompilerTest::comparesALiteralOnTheLeftOfAByte);
        suite.add("Compiler sets a segment up in a clause",
                CompilerTest::setsASegmentUpInAClause);
        suite.add("Compiler hands a call the fields of a table",
                CompilerTest::handsACallTheFieldsOfATable);
        suite.add("Compiler compares a literal with a word",
                CompilerTest::comparesALiteralOnTheLeftOfAWord);
        suite.add("Compiler computes a byte expression in byte registers",
                CompilerTest::computesAByteExpressionInByteRegisters);
        suite.add("Compiler keeps a statement eval at the width of its operands",
                CompilerTest::keepsAStatementEvalAtTheWidthOfItsOperands);
        suite.add("Compiler refuses a value wider than a register",
                CompilerTest::refusesAValueWiderThanARegister);
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
        suite.add("Compiler writes the base pointer like any other register",
                CompilerTest::writesTheBasePointer);
        suite.add("Compiler gives a BIOS call its registers", CompilerTest::givesABiosCallItsRegisters);
        suite.add("Compiler hands the next stage its registers",
                CompilerTest::handsTheNextStageItsRegisters);
        suite.add("Compiler keeps a value out of the register a segment move uses",
                CompilerTest::keepsValuesOutOfTheSegmentScratch);
        suite.add("Compiler moves a value into a segment register directly",
                CompilerTest::setsASegmentFromARegister);
        suite.add("Compiler sets three segments up from one zero",
                CompilerTest::setsThreeSegmentsFromOneZero);
        suite.add("Compiler hands a clause a segment from the register a value is in",
                CompilerTest::givesAClauseASegmentFromARegister);
        suite.add("Compiler counts down with the machine's own loop",
                CompilerTest::countsDownWithTheMachineLoop);
        suite.add("Compiler counts down without the comparison",
                CompilerTest::countsDownWithoutTheComparison);
        suite.add("Compiler keeps the comparison when the flags are read after it",
                CompilerTest::keepsTheComparisonWhenTheFlagsAreReadAfter);
        suite.add("Compiler leaves the counted form alone when the body cannot be sized",
                CompilerTest::leavesTheCountedFormAloneWhenTheBodyCannotBeSized);
        suite.add("Compiler leaves the counted form alone when the loop is too long",
                CompilerTest::leavesTheCountedFormAloneWhenTheLoopIsTooLong);
        suite.add("Compiler folds a load into the comparison that reads it",
                CompilerTest::foldsALoadIntoTheComparison);
        suite.add("Compiler leaves a load that has another reader",
                CompilerTest::leavesALoadWithAnotherReader);
        suite.add("Compiler leaves a volatile access where it was written",
                CompilerTest::leavesAVolatileAccessWhereItIs);
        suite.add("Compiler compares with an access on the other side",
                CompilerTest::comparesWithAnAccessOnTheOtherSide);
        suite.add("Compiler keeps a segment set up that nothing reads",
                CompilerTest::keepsSegmentationState);
        suite.add("Compiler reads the drive number the BIOS hands over",
                CompilerTest::readsTheDriveNumber);
        suite.add("Compiler reads the machine's segmentation state",
                CompilerTest::readsSegmentationState);
        suite.add("Compiler keeps values out of the register a read asks for",
                CompilerTest::keepsValuesOutOfTheRegisterItReads);
        suite.add("Compiler keeps a scratch register off a read",
                CompilerTest::keepsScratchRegistersOffARead);
        suite.add("Compiler refuses a read of a register it has already written",
                CompilerTest::refusesAReadOfARegisterTheCompilerWrote);
        suite.add("Compiler reads what the machine left after an interrupt",
                CompilerTest::readsWhatTheMachineLeftAfterAnInterrupt);
        suite.add("Compiler keeps the drive number across an interrupt",
                CompilerTest::keepsTheDriveNumberAcrossAnInterrupt);
        suite.add("Compiler drops a read nobody uses", CompilerTest::dropsAReadNobodyUses);
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
        suite.add("Compiler narrows a value to its low byte", CompilerTest::narrowsToTheLowByte);
        suite.add("Compiler narrows a value it still needs afterwards",
                CompilerTest::narrowsAValueItStillNeeds);
        suite.add("Compiler reloads a narrowed value from its home",
                CompilerTest::narrowsFromAHome);
        suite.add("Compiler widens a byte with zeroes", CompilerTest::widensWithZeroes);
        suite.add("Compiler widens a byte with its sign", CompilerTest::widensWithTheSign);
        suite.add("Compiler keeps a value out of the register a widening uses",
                CompilerTest::keepsAValueOutOfTheWideningRegister);
        suite.add("Compiler refuses a widening to a double word",
                CompilerTest::refusesAWideningToADoubleWord);
        suite.add("Compiler refuses a widening of a load", CompilerTest::refusesAWideningOfALoad);
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

    /**
     * The second shipped example, and the reason it is one: a loop that multiplies, divides and
     * accumulates needs six registers and gets them, because nothing is copied that does not have to
     * be.
     *
     * <p>Read the body of the loop and the whole of the back end is in it. Four variables live across
     * the loop — {@code s}, {@code d}, {@code a} and {@code b} — and {@code imul} and {@code idiv} work
     * in {@code ax} and {@code dx} and nowhere else, which is six; the division reads {@code b} where
     * it already is instead of copying it somewhere, which is the register that makes the count work.
     * The multiply and the divide are one chain in {@code ax}, so nothing is copied between them.
     * {@code inc} appears where the flags were claimed and given up again, and {@code test si, si} is
     * the comparison with zero that does not need the zero.
     */
    private static void keepsTheExampleLoopInRegisters() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov di, 0x4e20\n"
                        + "    mov si, 0x4e20\n"
                        + "    mov bx, 1\n"
                        + "    mov cx, 3\n"
                        + "    jmp ..@lbl1\n"
                        + "\n"
                        + "..@lbl0:\n"
                        + "    mov ax, si\n"
                        + "    imul bx\n"
                        + "    cwd\n"
                        + "    idiv cx\n"
                        + "    mov si, ax\n"
                        + "    inc bx\n"
                        + "    add cx, 2\n"
                        + "    add di, si\n"
                        + "\n"
                        + "..@lbl1:\n"
                        + "    test si, si\n"
                        + "    jg ..@lbl0\n"
                        + "    mov [0x40], di\n"
                        + "    ret\n",
                Compiler.compile("examples/sum.ir", readExample("examples/sum.ir")));
    }

    /**
     * The chain is two operations the machine does in a register of its own, and that is the whole of
     * what it covers: an operation with an ordinary form in the middle of a tree is computed into a
     * register of its own, because the multiply outside it reads its operand after that register has
     * been written. The two copies around the multiply below are that case, and they are what the
     * chain cannot take away ({@code docs/ir.md} §6.1).
     */
    private static void keepsTheCopiesAroundAnOperationWithAForm() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov bx, word [0x40]\n"
                        + "    mov ax, word [0x42]\n"
                        + "    mov cx, word [0x44]\n"
                        + "    add bx, ax\n"
                        + "    mov ax, bx\n"
                        + "    mul cx\n"
                        + "    mov bx, ax\n"
                        + "    mov [0x46], bx\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $a: u16\n    var $b: u16\n    var $c: u16\n    var $x: u16\n"
                        + "    a = word [0x40]\n"
                        + "    b = word [0x42]\n"
                        + "    c = word [0x44]\n"
                        + "    x = expr((a + b) * c)\n"
                        + "    [0x46] = x\n"
                        + "    ret\n"));
    }

    /**
     * A literal a sequence needs in a register is put there by the selector, and every use of it
     * names that register — because nothing else is keeping the two together. When the copy was
     * written as a value instead, the allocator placed it wherever it liked and the division divided
     * by that register, silently.
     *
     * <p>The shape is how a table of fixed-size entries is walked — {@code bytes / 20} is an E820
     * entry count — and twenty is not a power of two, so the division is a division.
     */
    private static void writesALiteralIntoTheRegisterItsSequenceNames() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var $bytes: u16\n    var $entries: u16\n"
                + "    bytes = word [0x40]\n"
                + "    entries = expr(bytes / 20)\n"
                + "    [0x42] = entries\n"
                + "    ret\n");
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov cx, word [0x40]\n"
                        + "    mov bx, 0x14\n"
                        + "    mov ax, cx\n"
                        + "    xor dx, dx\n"
                        + "    div bx\n"
                        + "    mov [0x42], ax\n"
                        + "    ret\n",
                assembly);
        // Stated as a property as well, because this is the bug that was here: the register the
        // constant went into and the register the division reads have to be the same one.
        Assert.assertTrue(assembly.contains("mov bx, 0x14\n") && assembly.contains("div bx\n"),
                "the constant is divided by from where it was put: " + assembly);
    }

    /**
     * A divisor written out is one the compiler can look at, and a power of two of one is not a
     * division at all: it is a shift for the quotient and a mask for the remainder
     * ({@code docs/ir.md} §6.2).
     *
     * <p>Three real shapes, because the bytes are the point: a byte offset split into the sector it
     * is in and where it is in that sector, a length rounded up to whole sectors, and the offset of a
     * FAT12 entry. Each is a few bytes where the {@code div} instruction was eight or ten — and none
     * of them holds {@code ax} or {@code dx}, which is the half of this that is not about bytes.
     */
    private static void dividesByAPowerOfTwoByShifting() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov dx, word [0x40]\n"
                        + "    and ax, 0x1ff\n"
                        + "    mov cl, 9\n"
                        + "    shr dx, cl\n"
                        + "    mov [0x42], ax\n"
                        + "    mov [0x44], dx\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $offset: u16\n    var $within: u16\n    var $sector: u16\n"
                        + "    offset = word [0x40]\n"
                        + "    within = eval(offset % 512)\n"
                        + "    sector = eval(offset / 512)\n"
                        + "    [0x42] = within\n"
                        + "    [0x44] = sector\n"
                        + "    ret\n"));

        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov ax, word [0x40]\n"
                        + "    add ax, 0x1ff\n"
                        + "    mov cl, 9\n"
                        + "    shr ax, cl\n"
                        + "    mov [0x42], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $size: u16\n    var $rounded: u16\n    var $sectors: u16\n"
                        + "    size = word [0x40]\n"
                        + "    rounded = eval(size + 511)\n"
                        + "    sectors = eval(rounded / 512)\n"
                        + "    [0x42] = sectors\n"
                        + "    ret\n"));

        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov cx, word [0x40]\n"
                        + "    mov ax, 3\n"
                        + "    mul cx\n"
                        + "    shr ax, 1\n"
                        + "    mov [0x42], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $cluster: u16\n    var $off: u16\n"
                        + "    cluster = word [0x40]\n"
                        + "    off = expr(cluster * 3 / 2)\n"
                        + "    [0x42] = off\n"
                        + "    ret\n"));

        // Dividing by one is the value itself, so nothing is emitted for it at all.
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov ax, word [0x40]\n"
                        + "    mov [0x42], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $x: u16\n    var $y: u16\n"
                        + "    x = word [0x40]\n"
                        + "    y = expr(x / 1)\n"
                        + "    [0x42] = y\n"
                        + "    ret\n"));
    }

    /**
     * And the three cases that are still divisions, which is what makes the one above a rule rather
     * than a habit: a **signed** value, where a shift and {@code idiv} disagree about negative
     * numbers; a constant that is **not a power of two**; and a divisor that is a **value**, which
     * the compiler cannot look at at all ({@code docs/ir.md} §6.2).
     */
    private static void keepsDividingWhereATrickWouldBeWrong() {
        String signed = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var $signed: i16\n    var $half: i16\n"
                + "    signed = word [0x40]\n"
                + "    half = eval(signed / 2)\n"
                + "    [0x42] = half\n"
                + "    ret\n");
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov cx, word [0x40]\n"
                        + "    mov bx, 2\n"
                        + "    mov ax, cx\n"
                        + "    cwd\n"
                        + "    idiv bx\n"
                        + "    mov [0x42], ax\n"
                        + "    ret\n",
                signed);

        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov cx, word [0x40]\n"
                        + "    mov bx, 7\n"
                        + "    mov ax, cx\n"
                        + "    xor dx, dx\n"
                        + "    div bx\n"
                        + "    mov [0x42], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $size: u16\n    var $parts: u16\n"
                        + "    size = word [0x40]\n"
                        + "    parts = eval(size / 7)\n"
                        + "    [0x42] = parts\n"
                        + "    ret\n"));

        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov dx, word [0x40]\n"
                        + "    mov cx, word [0x42]\n"
                        + "    mov ax, dx\n"
                        + "    xor dx, dx\n"
                        + "    div cx\n"
                        + "    mov [0x44], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $size: u16\n    var $parts: u16\n    var $divisor: u16\n"
                        + "    size = word [0x40]\n"
                        + "    divisor = word [0x42]\n"
                        + "    parts = eval(size / divisor)\n"
                        + "    [0x44] = parts\n"
                        + "    ret\n"));
    }

    /**
     * A right-hand side that is not a value but a tree needs a register of its own: the operation
     * outside reads it after the left side has been computed, and computing one into the other would
     * lose it. That is what the temporary is for, and it is still made where it is needed
     * ({@code docs/ir.md} §3.2).
     */
    private static void keepsATemporaryForATreeOnTheRight() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov bx, word [0x40]\n"
                        + "    mov dx, word [0x42]\n"
                        + "    mov ax, 3\n"
                        + "    mul dx\n"
                        + "    mov cx, ax\n"
                        + "    add bx, cx\n"
                        + "    mov [0x44], bx\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $a: u16\n    var $b: u16\n    var $x: u16\n"
                        + "    a = word [0x40]\n"
                        + "    b = word [0x42]\n"
                        + "    x = expr(a + b * 3)\n"
                        + "    [0x44] = x\n"
                        + "    ret\n"));
    }

    private static void compilesShippedExample() {
        Assert.assertEquals(EXPECTED_ASM, Compiler.compile("examples/hello.ir", readExample()));
    }

    private static String readExample() {
        return readExample("examples/hello.ir");
    }

    private static String readExample(String path) {
        try {
            return new String(Files.readAllBytes(new File(path).toPath()), UTF_8);
        } catch (IOException failure) {
            Assert.fail("cannot read " + path + ": " + failure.getMessage());
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

    // --- the shorter instruction, where the flags allow (docs/ir.md §4.2) ---

    /**
     * A zero is built rather than moved where nothing can look at the flags: {@code xor r, r} is two
     * bytes and {@code mov r, 0} is three, and the register holds zero either way. The value here is
     * written before anything has defined the flags at all, so there is nothing a clear can destroy.
     */
    private static void buildsAZeroByClearing() {
        Assert.assertEquals("org 0x100\n\n$main:\n    xor ax, ax\n    mov [0x40], ax\n    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $a: u16\n"
                        + "    a = 0\n"
                        + "    [0x40] = a\n"
                        + "    ret\n"));
    }

    /**
     * And the other half of it, which is what makes it an optimisation rather than a rewrite: where
     * the flags can still be read the zero is moved, because the clear writes them and the move does
     * not. The branch reads what the comparison left, so those flags are live across the assignment.
     */
    private static void movesAZeroTheFlagsAreWaitingOn() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    cmp word [0x40], 1\n"
                        + "    mov ax, 0\n"
                        + "    jnz $skip\n"
                        + "    ret\n"
                        + "\n"
                        + "$skip:\n"
                        + "    mov [0x42], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $a: u16\n    var $b: u16\n"
                        + "    a = word [0x40]\n"
                        + "    cmp a, 1\n"
                        + "    b = 0\n"
                        + "    jnz $skip\n"
                        + "    ret\n"
                        + "$skip:\n"
                        + "    [0x42] = b\n"
                        + "    ret\n"));
    }

    /**
     * The flags are live where something still reads them, even though a comparison just before
     * defined them: the branch reads what the last comparison left, so the assignment in between
     * must not write them ({@code docs/ir.md} §4.2).
     *
     * <p>What the first comparison is doing in the source is being removed — its flags are read by
     * nothing, so dead value elimination takes the whole statement away — and that is what this test
     * is really about. A selector that worked out the flags question by counting items would be
     * counting the items of the module, where that statement still is, against the statements of the
     * form, where it is not: one item out, and the answer belongs to the wrong instruction.
     */
    private static void movesAZeroBetweenTwoComparisons() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov ax, word [0x40]\n"
                        + "    cmp ax, 2\n"
                        + "    mov ax, 0\n"
                        + "    jnz $skip\n"
                        + "    ret\n"
                        + "\n"
                        + "$skip:\n"
                        + "    mov [0x42], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $a: u16\n    var $b: u16\n"
                        + "    a = word [0x40]\n"
                        + "    cmp a, 1\n"
                        + "    cmp a, 2\n"
                        + "    b = 0\n"
                        + "    jnz $skip\n"
                        + "    ret\n"
                        + "$skip:\n"
                        + "    [0x42] = b\n"
                        + "    ret\n"));
    }

    /**
     * A byte's zero is a move either way — {@code mov al, 0} and {@code xor al, al} are two bytes
     * each — so nothing is given up for nothing and the value stays the instruction it was written
     * as ({@code docs/ir.md} §4.2).
     */
    private static void leavesABytesZeroAsAMove() {
        Assert.assertEquals("org 0x100\n\n$main:\n    mov al, 0\n    mov [0x40], al\n    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $c: u8\n"
                        + "    c = 0\n"
                        + "    [0x40] = c\n"
                        + "    ret\n"));
    }

    /**
     * The flags are dead in a block that nothing reads them in, even though the block before defined
     * them: the branch at the top spent what the comparison left, and nothing after it asks again.
     * That is the ordinary liveness question asked of the one variable, and the answer is what lets
     * the clear be used away from the top of the program as well.
     */
    private static void buildsAZeroWhereTheFlagsAreAlreadySpent() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    cmp word [0x40], 1\n"
                        + "    jz $done\n"
                        + "    xor ax, ax\n"
                        + "    mov [0x42], ax\n"
                        + "\n"
                        + "$done:\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $a: u16\n    var $b: u16\n"
                        + "    a = word [0x40]\n"
                        + "    cmp a, 1\n"
                        + "    jz $done\n"
                        + "    b = 0\n"
                        + "    [0x42] = b\n"
                        + "$done:\n"
                        + "    ret\n"));
    }

    /**
     * Comparing with zero is a test of the operand against itself, which is the same answer and does
     * not need the zero: on this machine both clear the carry and the overflow flag and both take
     * ZF, SF and PF from the operand ({@code docs/ir.md} §4.2). So the instruction is a byte shorter
     * than the comparison it stands for.
     */
    private static void testsAgainstZero() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov ax, word [0x40]\n"
                        + "    test ax, ax\n"
                        + "    jz $done\n"
                        + "    mov [0x42], ax\n"
                        + "\n"
                        + "$done:\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $a: u16\n"
                        + "    a = word [0x40]\n"
                        + "    cmp a, 0\n"
                        + "    jz $done\n"
                        + "    [0x42] = a\n"
                        + "$done:\n"
                        + "    ret\n"));
    }

    /**
     * The sugar says {@code .if a == 0} and is normalised to a comparison and a branch, so the same
     * choice reaches it: what the writer asked is the question, and which instruction asks it is the
     * back end's ({@code docs/ir.md} §7.2, §4.2).
     */
    private static void testsASugarsZeroTheSameWay() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov ax, word [0x40]\n"
                        + "    test ax, ax\n"
                        + "    jnz ..@lbl0\n"
                        + "    mov [0x42], ax\n"
                        + "\n"
                        + "..@lbl0:\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $a: u16\n"
                        + "    a = word [0x40]\n"
                        + "    .if a == 0\n"
                        + "        [0x42] = a\n"
                        + "    .endif\n"
                        + "    ret\n"));
    }

    /**
     * A byte is tested against itself as well, and it is the case where nothing is saved by it:
     * {@code cmp al, 0} has a two-byte encoding of its own. What is kept is one rule for one
     * question — a comparison with zero is a test — rather than a size comparison the selector would
     * have to guess at.
     */
    private static void testsAByteAgainstZero() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var $c: u8\n"
                + "    c = byte [0x44]\n"
                + "    cmp c, 0\n"
                + "    jz $done\n"
                + "$done:\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("    test al, al\n"),
                "a byte is tested against itself too: " + assembly);
    }

    /**
     * A test the writer wrote is left alone, and so is a comparison with anything that is not zero:
     * {@code test a, 0} tests a literal rather than the operand, which always sets ZF, and a
     * comparison with one is a comparison ({@code docs/ir.md} §4.2).
     */
    private static void leavesATestThatWasWritten() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var $a: u16\n"
                + "    a = word [0x40]\n"
                + "    test a, 0\n"
                + "    jz $done\n"
                + "    cmp a, 1\n"
                + "    jz $done\n"
                + "$done:\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("    test ax, 0\n"),
                "the operand is not the literal, so the test stays as it was written: " + assembly);
        Assert.assertTrue(assembly.contains("    cmp ax, 1\n"),
                "and a comparison with something that is not zero is a comparison: " + assembly);
    }

    // --- a temporary has the width of what it holds (docs/ir.md §3.2) ------

    /**
     * A literal on the left of a comparison takes the width of the other side, and the register the
     * selector puts it in has to be that wide: the machine takes the first operand in a register and
     * will not take a byte value through a word one, so {@code cmp al, cl} is an instruction and
     * {@code cmp ax, cl} is not ({@code docs/ir.md} §3.2).
     */
    private static void comparesALiteralOnTheLeftOfAByte() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov cl, byte [0x40]\n"
                        + "    mov al, 0\n"
                        + "    cmp al, cl\n"
                        + "    jz $done\n"
                        + "    mov [0x42], cl\n"
                        + "\n"
                        + "$done:\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $c: u8\n"
                        + "    c = byte [0x40]\n"
                        + "    cmp 0, c\n"
                        + "    jz $done\n"
                        + "    [0x42] = c\n"
                        + "$done:\n"
                        + "    ret\n"));
    }

    /**
     * The same comparison against a word, where the temporary is a word and the zero is built
     * without being moved, because nothing can read the flags in front of a comparison that defines
     * them ({@code docs/ir.md} §4.2). One question, two widths, and the width travels with the name.
     */
    private static void comparesALiteralOnTheLeftOfAWord() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov cx, word [0x40]\n"
                        + "    xor ax, ax\n"
                        + "    cmp ax, cx\n"
                        + "    jz $done\n"
                        + "    mov [0x42], cx\n"
                        + "\n"
                        + "$done:\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $w: u16\n"
                        + "    w = word [0x40]\n"
                        + "    cmp 0, w\n"
                        + "    jz $done\n"
                        + "    [0x42] = w\n"
                        + "$done:\n"
                        + "    ret\n"));
    }

    /**
     * An expression is computed where its operands already are: a right-hand side that is a value in
     * a register is read there, and no temporary is made for it. A temporary is not one instruction,
     * it is one register — and the register is what a two-address machine runs out of
     * ({@code docs/ir.md} §3.2).
     *
     * <p>{@code b} is read again after the sum, so it has a register of its own to be read from, and
     * the sum happens between the two registers.
     */
    private static void computesAByteExpressionInByteRegisters() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov cl, byte [0x40]\n"
                        + "    mov al, byte [0x41]\n"
                        + "    add cl, al\n"
                        + "    mov [0x42], cl\n"
                        + "    mov [0x43], al\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $a: u8\n    var $b: u8\n    var $x: u8\n"
                        + "    a = byte [0x40]\n"
                        + "    b = byte [0x41]\n"
                        + "    x = expr(a + b)\n"
                        + "    [0x42] = x\n"
                        + "    [0x43] = b\n"
                        + "    ret\n"));
    }

    /**
     * An operation written for its flags alone has nowhere to put its value, so the selector makes
     * somewhere — and it is as wide as the operands, or the flags of a byte addition would be the
     * flags of a word one ({@code docs/ir.md} §3.2, §5.1).
     */
    private static void keepsAStatementEvalAtTheWidthOfItsOperands() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov dl, byte [0x40]\n"
                        + "    mov cl, byte [0x41]\n"
                        + "    mov al, dl\n"
                        + "    add al, cl\n"
                        + "    jc $done\n"
                        + "    mov [0x42], dl\n"
                        + "\n"
                        + "$done:\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $a: u8\n    var $b: u8\n"
                        + "    a = byte [0x40]\n"
                        + "    b = byte [0x41]\n"
                        + "    eval(a + b)\n"
                        + "    jc $done\n"
                        + "    [0x42] = a\n"
                        + "$done:\n"
                        + "    ret\n"));
    }

    /**
     * A value wider than a register has nowhere to live, and it is refused where it is first
     * mentioned rather than given half a register: everything a four-byte value is used for would
     * otherwise be a word operation on the low half of it — a store that writes half the value out,
     * an addition that carries sixteen bits too few.
     *
     * <p>The same work as two halves is what the message points at, and it compiles.
     */
    private static void refusesAValueWiderThanARegister() {
        CompileError refused = Assert.assertRefused("wide.ir:7:5",
                () -> Compiler.compile("wide.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $x: u32\n"
                        + "    x = 0\n"
                        + "    [0x40] = x\n"
                        + "    ret\n"));
        Assert.assertTrue(refused.getMessage().contains("is wider than a register"),
                refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("u32"), refused.getMessage());

        String halves = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var $lo: u16\n    var $hi: u16\n"
                + "    lo = word [0x40]\n"
                + "    hi = word [0x42]\n"
                + "    [0x50] = lo\n"
                + "    [0x52] = hi\n"
                + "    ret\n");
        Assert.assertTrue(halves.contains("mov [0x50], ") && halves.contains("mov [0x52], "),
                "two halves are two word moves, whichever registers they went through: " + halves);
    }

    /**
     * A clause is where a loader sets a segment up, and a segment register takes no immediate: the
     * value goes through a general register, which is the target's answer and the same one
     * {@code movreg} gets ({@code docs/ir.md} §8.1, §11).
     *
     * <p>That write happens before the arguments that are written into {@code ax}'s halves, because
     * the register the machine moves a segment through is one of them: {@code ah = 2} after
     * {@code es = 0} would otherwise be the value that got it there, gone.
     */
    private static void setsASegmentUpInAClause() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov dl, byte [0x40]\n"
                        + "    mov cx, 0x7e00\n"
                        + "    xor ax, ax\n"
                        + "    mov es, ax\n"
                        + "    mov bx, cx\n"
                        + "    mov ah, 2\n"
                        + "    mov al, 1\n"
                        + "    int 0x13\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $c: u8\n    var $buffer: u16\n"
                        + "    c = byte [0x40]\n"
                        + "    buffer = 0x7E00\n"
                        + "    int 0x13 clobbers(ax, bx, cx, dx) with ah = 2, al = 1, es = 0,"
                        + " bx = buffer, dl = c\n"
                        + "    ret\n"));
    }

    /**
     * A clause operand may be an access, which is how a boot loader hands a call the fields of a
     * table it is looking at: {@code ch = byte [entry + 3]} is one instruction where reading the
     * field into a value first costs a register and the move that follows it ({@code docs/ir.md}
     * §11).
     *
     * <p>The three fields are read straight into the registers the call wants, and the drive number
     * — which lives in a cell because it has to survive the interrupts — is moved into {@code dl}
     * before the arguments that write {@code dx}'s other half.
     */
    private static void handsACallTheFieldsOfATable() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov cl, dl\n"
                        + "    mov bx, $parts\n"
                        + "    xor ax, ax\n"
                        + "    mov es, ax\n"
                        + "    mov dl, cl\n"
                        + "    mov ah, 2\n"
                        + "    mov al, 1\n"
                        + "    mov ch, byte [bx+3]\n"
                        + "    mov cl, byte [bx+2]\n"
                        + "    mov dh, byte [bx+1]\n"
                        + "    mov bx, 0x7e00\n"
                        + "    int 0x13\n"
                        + "    jc $fail\n"
                        + "    ret\n"
                        + "\n"
                        + "$fail:\n"
                        + "\n"
                        + "$halt:\n"
                        + "    hlt\n"
                        + "    jmp $halt\n"
                        + "\n"
                        + "$parts: times 0x40-($-$$) db 0\n"
                        + "\n"
                        + "$saved: times 1 db 0\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var $base: u16\n"
                        + "    var $drive: u8 in saved\n"
                        + "    movreg drive, dl\n"
                        + "    base = parts\n"
                        + "    int 0x13 clobbers(ax, bx, cx, dx) with ah = 2, al = 1,"
                        + " ch = byte [base + 3], cl = byte [base + 2], dh = byte [base + 1],"
                        + " dl = drive, es = 0, bx = 0x7E00\n"
                        + "    jc $fail\n"
                        + "    ret\n"
                        + "$fail:\n"
                        + "halt:\n"
                        + "    hlt\n"
                        + "    jmp $halt\n"
                        + "\n"
                        + "parts: pad to 0x40\n"
                        + "saved: pad 1\n"));
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
     * cost a copy per turn and the value the test reads would be the wrong one. What it
     * starts from is a zero, and the machine builds one without moving it
     * ({@code docs/ir.md} §4.2).
     */
    private static void loopCarriedValue() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    xor ax, ax\n"
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
     * declared. {@code sp} takes the value directly, and a segment register does not take an
     * immediate — so an immediate goes through {@code ax}, and so does another segment register,
     * which this machine cannot move into one ({@code docs/ir.md} §8.1).
     */
    private static void setsSegmentsUp() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x7c00\nentry $main\n\n"
                + "$main:\n"
                + "    movreg ds, 0\n"
                + "    movreg sp, 0x7c00\n"
                + "    movreg es, 0xb800\n"
                + "    movreg ds, cs\n"
                + "    ret\n");
        Assert.assertEquals("org 0x7c00\n\n$main:\n"
                + "    xor ax, ax\n"
                + "    mov ds, ax\n"
                + "    mov sp, 0x7c00\n"
                + "    mov ax, 0xb800\n"
                + "    mov es, ax\n"
                + "    mov ax, cs\n"
                + "    mov ds, ax\n"
                + "    ret\n", assembly);
    }

    /**
     * The same three registers from a value: a segment register takes a general register directly,
     * so {@code mov es, ax} is the whole of it where an immediate has to be built in a register
     * first. The register is the one the value already lives in, which is what the copy the
     * immediate needs was buying — two bytes and one register that nothing else may use
     * ({@code docs/ir.md} §8.1).
     */
    private static void setsASegmentFromARegister() {
        Assert.assertEquals("org 0x100\n\n$main:\n"
                + "    mov ax, word [0x40]\n"
                + "    mov es, ax\n"
                + "    mov [0x42], ax\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var v: u16\n"
                        + "    v = word [0x40]\n"
                        + "    movreg es, v\n"
                        + "    volatile [0x42] = v\n"
                        + "    ret\n"));
    }

    /**
     * Three segment registers set to one zero, which is the cheap way to write a preamble and the
     * shape a person writes by hand: the zero is one value, so it is built once and moved three
     * times. Three literals cost a build each, because a statement cannot see another statement's
     * literal ({@code docs/ir.md} §3.1.2 — a named constant is what would make it automatic).
     */
    private static void setsThreeSegmentsFromOneZero() {
        Assert.assertEquals("org 0x7c00\n\n$main:\n"
                + "    xor ax, ax\n"
                + "    mov ds, ax\n"
                + "    mov es, ax\n"
                + "    mov ss, ax\n"
                + "    mov sp, 0x7c00\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x7c00\nentry $main\n\n$main:\n"
                        + "    var zero: u16\n"
                        + "    zero = 0\n"
                        + "    movreg ds, zero\n"
                        + "    movreg es, zero\n"
                        + "    movreg ss, zero\n"
                        + "    movreg sp, 0x7c00\n"
                        + "    ret\n"));
    }

    /**
     * And the same in the shape a loader uses it: a clause giving an interrupt a segment register
     * out of a value ({@code docs/ir.md} §11). What the clause does with the segment is the
     * target's, so this is the same sequence as the statement above — with the value in whatever
     * register it is in, and no copy into {@code ax}.
     */
    private static void givesAClauseASegmentFromARegister() {
        Assert.assertEquals("org 0x100\n\n$main:\n"
                + "    mov si, word [0x40]\n"
                + "    mov es, si\n"
                + "    mov ah, 2\n"
                + "    mov bx, 0x7e00\n"
                + "    int 0x13\n"
                + "    mov [0x42], si\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var v: u16\n"
                        + "    v = word [0x40]\n"
                        + "    int 0x13 clobbers(ax, bx, cx, dx) with ah = 2, es = v, bx = 0x7e00\n"
                        + "    volatile [0x42] = v\n"
                        + "    ret\n"));
    }

    /**
     * The sequence a segment move needs writes {@code ax}, and the allocator is told: a value alive
     * across the move is kept out of that register, because the register is written by the sequence
     * and not by anything the program wrote. The zero it is set up through is built rather than
     * moved, because the flags are dead this early in the program ({@code docs/ir.md} §4.2).
     */
    private static void keepsValuesOutOfTheSegmentScratch() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n"
                + "$main:\n"
                + "    var x: u16\n"
                + "    x = word [0x40]\n"
                + "    movreg ds, 0\n"
                + "    word [0x42] = x\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("xor ax, ax\n    mov ds, ax\n"), assembly);
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
                        + "    movreg ds, 0x1234\n"
                        + "    ret\n"));
    }

    // --- the countdown a loop pays for twice (the target's tail) -------------

    /**
     * The scan loop of a boot sector, done the way the machine does it: the comparison carries the
     * access it is about, and {@code loop} counts the word down. Two rewrites in one line of source,
     * and they are the two the byte ledger of an MBR names.
     *
     * <p>The counter is in {@code cx} because the byte the loop counts in wants {@code al} — a byte
     * value lives in the low half of one of the four registers that has a half ({@code docs/ir.md}
     * §3.2) — and {@code loop} counts in {@code cx} and nowhere else. Which register a value ends up
     * in is the allocator's answer, which is why both rewrites happen where they do: the fold reads
     * the form, the counted form runs after allocation.
     */
    private static void countsDownWithTheMachineLoop() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov bx, $parts\n"
                        + "    mov cx, 4\n"
                        + "    mov al, 0\n"
                        + "\n"
                        + "$top:\n"
                        + "    cmp byte [bx], 0x80\n"
                        + "    jnz $next\n"
                        + "    inc al\n"
                        + "\n"
                        + "$next:\n"
                        + "    add bx, 0x10\n"
                        + "    loop $top\n"
                        + "    mov [0x40], al\n"
                        + "    ret\n"
                        + "\n"
                        + "$parts: times 4 db 0\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var n: u16\n"
                        + "    var count: u8\n"
                        + "    var base: u16\n"
                        + "    base = $parts\n"
                        + "    n = 4\n"
                        + "    count = 0\n"
                        + "$top:\n"
                        + "    cmp byte [base], 0x80\n"
                        + "    jnz $next\n"
                        + "    count = eval(count + 1)\n"
                        + "$next:\n"
                        + "    base = eval(base + 16)\n"
                        + "    n = eval(n - 1)\n"
                        + "    cmp n, 0\n"
                        + "    jnz $top\n"
                        + "    volatile [0x40] = count\n"
                        + "    ret\n"
                        + "\n"
                        + "$parts: pad 4\n"));
    }

    /**
     * The comparison is the byte that goes even where the counted instruction cannot: this counter
     * is a byte, and {@code loop} counts in {@code cx} and nowhere else. What is left is a decrement
     * and a branch, because the decrement has already said whether the result is zero.
     */
    private static void countsDownWithoutTheComparison() {
        Assert.assertEquals("org 0x100\n"
                + "\n"
                + "$main:\n"
                + "    mov al, 4\n"
                + "\n"
                + "$top:\n"
                + "    hlt\n"
                + "    dec al\n"
                + "    jnz $top\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var n: u8\n"
                        + "    n = 4\n"
                        + "$top:\n"
                        + "    hlt\n"
                        + "    n = eval(n - 1)\n"
                        + "    cmp n, 0\n"
                        + "    jnz $top\n"
                        + "    ret\n"));
    }

    /**
     * And the must-not: the flags the comparison leaves are read by a second branch, so the
     * comparison is not a repeat of anything — it is the only thing that says what the second
     * branch is asking about. Nothing here may be removed, and the second branch is what makes the
     * difference between {@code test}, which clears the carry, and the decrement, which does not.
     */
    private static void keepsTheComparisonWhenTheFlagsAreReadAfter() {
        Assert.assertEquals("org 0x100\n"
                + "\n"
                + "$main:\n"
                + "    mov ax, 3\n"
                + "\n"
                + "$top:\n"
                + "    hlt\n"
                + "    dec ax\n"
                + "    test ax, ax\n"
                + "    jnz $top\n"
                + "    jz $other\n"
                + "\n"
                + "$other:\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var x: u16\n"
                        + "    x = 3\n"
                        + "$top:\n"
                        + "    hlt\n"
                        + "    x = eval(x - 1)\n"
                        + "    cmp x, 0\n"
                        + "    jnz $top\n"
                        + "    jz $other\n"
                        + "$other:\n"
                        + "    ret\n"));
    }

    /**
     * The other must-not, and the reason the counted form is not simply "a countdown": how far
     * {@code loop} reaches is a signed byte, and the bytes an inline block takes are not something
     * this compiler knows — it is text it cannot read ({@code docs/ir.md} §9). So the block's loop
     * keeps a decrement and a branch, and the comparison still goes.
     */
    private static void leavesTheCountedFormAloneWhenTheBodyCannotBeSized() {
        Assert.assertEquals("org 0x100\n"
                + "\n"
                + "$main:\n"
                + "    mov cx, 3\n"
                + "\n"
                + "$top:\n"
                + "    nop\n"
                + "    sub cx, 1\n"
                + "    jnz $top\n"
                + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var x: u16\n"
                        + "    x = 3\n"
                        + "$top:\n"
                        + "    asm clobbers(ax) {\n        nop\n    }\n"
                        + "    x = eval(x - 1)\n"
                        + "    cmp x, 0\n"
                        + "    jnz $top\n"
                        + "    ret\n"));
    }

    /**
     * A loop body too long to count: seventeen stores and the countdown are more than a signed byte
     * can reach, counted as generously as the widest instruction this machine has. The three
     * instructions stay, and the comparison still goes — which is the half that needs no range.
     */
    private static void leavesTheCountedFormAloneWhenTheLoopIsTooLong() {
        StringBuilder source = new StringBuilder("target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var n: u16\n    var x: u16\n"
                + "    n = 4\n"
                + "    x = word [0x40]\n"
                + "$top:\n");
        for (int at = 0; at < 17; at++) {
            source.append("    volatile [0x").append(Integer.toHexString(0x50 + 2 * at))
                    .append("] = x\n");
        }
        source.append("    n = eval(n - 1)\n")
                .append("    cmp n, 0\n")
                .append("    jnz $top\n")
                .append("    ret\n");

        String assembly = Compiler.compile("t.ir", source.toString());
        Assert.assertFalse(assembly.contains("loop "),
                "a byte of displacement cannot reach this far: " + assembly);
        Assert.assertTrue(assembly.contains("    dec cx\n    jnz $top\n"), assembly);
    }

    // --- the access the comparison can carry (docs/ir.md §5.4) --------------

    /**
     * A load whose only reader is the comparison standing next to it: the value exists to give the
     * comparison something to name, and the machine has one instruction for the two of them,
     * {@code cmp byte [bx], 0x80} — three bytes where the load and the comparison are four.
     */
    private static void foldsALoadIntoTheComparison() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov bx, $parts\n"
                        + "    cmp byte [bx], 0x80\n"
                        + "    jz $stop\n"
                        + "    hlt\n"
                        + "\n"
                        + "$stop:\n"
                        + "    ret\n"
                        + "\n"
                        + "$parts: times 4 db 0\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var p: u16\n"
                        + "    var flag: u8\n"
                        + "    p = $parts\n"
                        + "    flag = byte [p]\n"
                        + "    cmp flag, 0x80\n"
                        + "    jz $stop\n"
                        + "    hlt\n"
                        + "$stop:\n"
                        + "    ret\n"
                        + "\n"
                        + "$parts: pad 4\n"));
    }

    /**
     * And the must-not that is about the value rather than the access: with another reader the load
     * has to stay for it, so folding it into the comparison would buy nothing and could only make
     * the reader read something else.
     */
    private static void leavesALoadWithAnotherReader() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov bx, $here\n"
                        + "    mov al, byte [bx]\n"
                        + "    test al, al\n"
                        + "    jz $stop\n"
                        + "    mov [0x40], al\n"
                        + "\n"
                        + "$stop:\n"
                        + "    ret\n"
                        + "\n"
                        + "$here: times 2 db 0\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var p: u16\n"
                        + "    var c: u8\n"
                        + "    p = $here\n"
                        + "    c = byte [p]\n"
                        + "    cmp c, 0\n"
                        + "    jz $stop\n"
                        + "    volatile [0x40] = c\n"
                        + "$stop:\n"
                        + "    ret\n"
                        + "\n"
                        + "$here: pad 2\n"));
    }

    /**
     * The must-not that is about the access: {@code volatile} says the access happens where it was
     * written, and the comparison is a different place. What makes this test say that and not
     * something else is that the comparison is against {@code 0x80} — a comparison with zero would
     * be left alone for its own reason ({@code LoadFolding}).
     */
    private static void leavesAVolatileAccessWhereItIs() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov bx, $parts\n"
                        + "    mov al, byte [bx]\n"
                        + "    cmp al, 0x80\n"
                        + "    jz $stop\n"
                        + "    hlt\n"
                        + "\n"
                        + "$stop:\n"
                        + "    ret\n"
                        + "\n"
                        + "$parts: times 4 db 0\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var p: u16\n"
                        + "    var c: u8\n"
                        + "    p = $parts\n"
                        + "    c = volatile byte [p]\n"
                        + "    cmp c, 0x80\n"
                        + "    jz $stop\n"
                        + "    hlt\n"
                        + "$stop:\n"
                        + "    ret\n"
                        + "\n"
                        + "$parts: pad 4\n"));
    }

    /**
     * The access on the other side of a comparison, which the surface could always write and this
     * compiler could not select: {@code cmp ax, word [bx]} is one instruction, and the byte goes the
     * other way from the load the comparison could have carried (there is no instruction whose two
     * operands are both in memory, so the value that was loaded stays in its register).
     */
    private static void comparesWithAnAccessOnTheOtherSide() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov bx, $here\n"
                        + "    mov ax, word [0x40]\n"
                        + "    cmp ax, word [bx]\n"
                        + "    jz $stop\n"
                        + "    mov word [0x42], ax\n"
                        + "\n"
                        + "$stop:\n"
                        + "    ret\n"
                        + "\n"
                        + "$here: times 2 db 0\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var p: u16\n"
                        + "    var x: u16\n"
                        + "    p = $here\n"
                        + "    x = word [0x40]\n"
                        + "    cmp x, word [p]\n"
                        + "    jz $stop\n"
                        + "    word [0x42] = x\n"
                        + "$stop:\n"
                        + "    ret\n"
                        + "\n"
                        + "$here: pad 2\n"));
    }

    // --- reading the machine's own registers (docs/ir.md §8.1) --------------

    /**
     * The other direction of {@code movreg}: the drive number the BIOS hands a boot loader in
     * {@code dl}, read into a value the program can use ({@code docs/ir.md} §8.1).
     *
     * <p>The copy is a real instruction and not a register moved into itself: the register is kept
     * free for what the machine left there, so the value is read into another one of the four
     * registers that can hold a byte.
     */
    private static void readsTheDriveNumber() {
        Assert.assertEquals("org 0x7c00\n\n$main:\n    mov al, dl\n    mov [0x500], al\n    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x7c00\nentry $main\n\n$main:\n"
                        + "    var drive: u8\n"
                        + "    movreg drive, dl\n"
                        + "    [0x500] = drive\n"
                        + "    ret\n"));
    }

    /**
     * A register no value can live in is read the same way and needs nothing kept out of it: the
     * machine's own segmentation state is one {@code mov}, and what a loader does with it is carry
     * it around as a value ({@code docs/ir.md} §8.1).
     */
    private static void readsSegmentationState() {
        Assert.assertEquals("org 0x100\n\n$main:\n    mov ax, ds\n    mov [0x500], ax\n    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var seg: u16\n"
                        + "    movreg seg, ds\n"
                        + "    [0x500] = seg\n"
                        + "    ret\n"));
    }

    /**
     * The whole reason the read direction exists, in one program: the BIOS hands a boot loader the
     * drive number in {@code dl}, the loader has to keep it across an interrupt that destroys every
     * register, and the next stage wants it in {@code dl} again ({@code docs/ir.md} §8.1).
     *
     * <p>The value lives in the cell the declaration gave it exactly while the interrupt is in the
     * way, and nowhere else — which is what a home is for, and what says the read composes with the
     * rest of the pipeline rather than needing a block.
     */
    private static void keepsTheDriveNumberAcrossAnInterrupt() {
        Assert.assertEquals("org 0x7c00\n\n$main:\n    mov al, dl\n    mov byte [$saved], al\n"
                        + "    int 0x13\n    mov al, byte [$saved]\n    mov dl, al\n"
                        + "    jmp 0:0x7e00\n    ret\n\n$saved: times 1 db 0\n",
                Compiler.compile("t.ir", "target 8086\norg 0x7c00\nentry $main\n\n$main:\n"
                        + "    var drive: u8 in saved\n"
                        + "    movreg drive, dl\n"
                        + "    int 0x13 clobbers(ax, bx, cx, dx)\n"
                        + "    jmp 0:0x7e00 with dl = drive\n"
                        + "    ret\n"
                        + "\nsaved: pad 1\n"));
    }

    /**
     * A read asks for what the machine left in the register, so the compiler keeps its own values
     * out of it — and that is not free: {@code dx} is one of the four registers a byte value can
     * live in, and a program that needs all four while a read still has to find {@code dl} untouched
     * is refused rather than given a register the read would then see. The same program without the
     * read compiles, which is what says the read is what took the register away.
     */
    private static void keepsValuesOutOfTheRegisterItReads() {
        String program = "target 8086\n"
                + "org 0x100\n"
                + "entry $main\n"
                + "\n"
                + "$main:\n"
                + "    var a: u8\n"
                + "    var b: u8\n"
                + "    var c: u8\n"
                + "    var drive: u8\n"
                + "$loop:\n"
                + "    movreg drive, dl\n"
                + "    a = byte [0x501]\n"
                + "    b = byte [0x502]\n"
                + "    c = byte [0x503]\n"
                + "    [0x510] = a\n"
                + "    [0x511] = b\n"
                + "    [0x512] = c\n"
                + "    [0x514] = drive\n"
                + "    jmp $loop\n";
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("read.ir", program));
        Assert.assertTrue(refused.getMessage().contains("is what a 'movreg' reads"),
                "the register is asked for by the read: " + refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("dx"), refused.getMessage());

        String without = program.replace("    movreg drive, dl\n",
                "    drive = byte [0x500]\n");
        String assembly = Compiler.compile("free.ir", without);
        Assert.assertTrue(assembly.contains("mov dl, byte [0x501]"),
                "and with nothing reading the machine, dl is a register like any other: " + assembly);
    }

    /**
     * A scratch register is a write of the compiler's like any other, so a register a read can still
     * ask for is not one it may pick — here the value in the cell needs a register to be got at, and
     * the only one left is the one {@code movreg} reads ({@code docs/ir.md} §8.1, §3.1.2).
     */
    private static void keepsScratchRegistersOffARead() {
        String program = "target 8086\n"
                + "org 0x100\n"
                + "entry $main\n"
                + "\n"
                + "$main:\n"
                + "    var a: u8 in cell\n"
                + "    var b: u8\n"
                + "    var c: u8\n"
                + "    var drive: u8\n"
                + "$loop:\n"
                + "    movreg drive, dl\n"
                + "    a = byte [0x501]\n"
                + "    b = byte [0x502]\n"
                + "    c = byte [0x503]\n"
                + "    [0x510] = a\n"
                + "    [0x511] = b\n"
                + "    [0x512] = c\n"
                + "    [0x514] = drive\n"
                + "    jmp $loop\n"
                + "\n"
                + "cell: pad 1\n";
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("scratch.ir", program));
        Assert.assertTrue(refused.getMessage().contains("has to hold what a 'movreg' reads"),
                refused.getMessage());

        String without = program.replace("    movreg drive, dl\n",
                "    drive = byte [0x500]\n");
        Compiler.compile("free.ir", without);
    }

    /**
     * What a read would return must be the machine's, and the compiler writes registers for its own
     * reasons: {@code div} leaves the remainder in {@code dx}, and no other register will do. So a
     * read of that register after such an operation is refused with a position, rather than answered
     * with the compiler's own arithmetic ({@code docs/ir.md} §8.1).
     */
    private static void refusesAReadOfARegisterTheCompilerWrote() {
        String program = "target 8086\n"
                + "org 0x100\n"
                + "entry $main\n"
                + "\n"
                + "$main:\n"
                + "    var a: u16\n"
                + "    var b: u16\n"
                + "    var q: u16\n"
                + "    var drive: u8\n"
                + "    a = 100\n"
                + "    b = 7\n"
                + "    q = eval(a / b)\n"
                + "    movreg drive, dl\n"
                + "    [0x500] = q\n"
                + "    [0x502] = drive\n"
                + "    ret\n";
        CompileError refused = Assert.assertRefused("read.ir:13:5",
                () -> Compiler.compile("read.ir", program));
        Assert.assertTrue(refused.getMessage().contains("'dl' is read here"),
                refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("already written 'dx'"),
                refused.getMessage());
    }

    /**
     * A statement that says what goes into a register puts it back in the machine's hands: the
     * answer an interrupt leaves there is read after it, whatever the compiler did before
     * ({@code docs/ir.md} §8.1).
     */
    private static void readsWhatTheMachineLeftAfterAnInterrupt() {
        Assert.assertEquals("org 0x100\n\n$main:\n    int 0x13\n    mov cl, ah\n"
                        + "    mov [0x500], cl\n    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var status: u8\n"
                        + "    int 0x13 clobbers(ax, bx, cx, dx)\n"
                        + "    movreg status, ah\n"
                        + "    [0x500] = status\n"
                        + "    ret\n"));
    }

    /**
     * A read nobody uses is not an instruction: what it produces is a value, and a value with no
     * reader is what dead value elimination is for. Reading a register has no effect of its own —
     * nothing about the machine changes — so nothing has to be kept for it.
     */
    private static void dropsAReadNobodyUses() {
        Assert.assertEquals("org 0x100\n\n$main:\n    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var drive: u8\n"
                        + "    movreg drive, dl\n"
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
     * itself and was dropped. And the value the sequence copies in lives in {@code dx} —
     * a register the multiply destroys — because the copy is its last read: what a
     * register is destroyed by is the instruction that runs, and this value is dead by
     * then ({@code docs/ir.md} §3.1, §11).
     *
     * <p>Which registers they are instead is not the test's business — the allocator
     * decides that by colouring a graph, and the names in it are the program's, not this
     * test's.
     */
    private static void multiplies() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov dx, cx\n"
                        + "    inc dx\n"
                        + "    add cx, 2\n"
                        + "    mov ax, dx\n"
                        + "    mul cx\n"
                        + "    mov [0x40], ax\n"
                        + "    ret\n",
                Compiler.compile("t.ir", twoValues("*")));
    }

    private static void divides() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov dx, cx\n"
                        + "    inc dx\n"
                        + "    add cx, 2\n"
                        + "    mov ax, dx\n"
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
                        + "    mov dx, cx\n"
                        + "    inc dx\n"
                        + "    add cx, 2\n"
                        + "    mov ax, dx\n"
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
     * The reason the {@code with} clause exists: a BIOS call wants its arguments in registers the
     * machine names, and the surface says so on the call itself. What comes out is a sequence — the
     * arguments, then the statement — which is exactly what an assembly author would write, and the
     * whole statement stays one item, so nothing is pinned ({@code docs/ir.md} §11).
     */
    private static void givesABiosCallItsRegisters() {
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$dap: times 0x10 db 0\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov ah, 0x42\n"
                        + "    mov dl, 0x80\n"
                        + "    mov si, $dap\n"
                        + "    int 0x13\n"
                        + "    jc $failed\n"
                        + "    ret\n"
                        + "\n"
                        + "$failed:\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n"
                        + "$dap: pad 16\n\n$main:\n"
                        + "    int 0x13 clobbers(ax, bx, cx, dx) with ah = 0x42, dl = 0x80, si = $dap\n"
                        + "    jc failed\n"
                        + "    ret\n"
                        + "\n"
                        + "$failed:\n"
                        + "    ret\n"));
    }

    /**
     * A clause operand may be a value, and then it is read where the statement is — which means it
     * is alive across everything between the two, and the copy into the register the clause names is
     * the compiler's to make ({@code docs/ir.md} §11).
     *
     * <p>Where the value is read and what the clause asks for are the same thing when the value can
     * live in the register being named: the copy is then a register moved into itself and there is no
     * instruction at all, because a value is read before the register it is read from is written
     * ({@code docs/ir.md} §5.1).
     */
    private static void handsTheNextStageItsRegisters() {
        Assert.assertEquals("org 0x100\n\n$main:\n"
                        + "    mov si, word [0x40]\n"
                        + "    int 0x10\n"
                        + "    mov word [0x42], si\n"
                        + "    jmp 0:0x7e00\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var count: u16\n"
                        + "    count = word [0x40]\n"
                        + "    int 0x10 clobbers(ax, bx, cx, dx)\n"
                        + "    word [0x42] = count\n"
                        + "    jmp 0:0x7e00 with si = count\n"));
    }

    /**
     * The other register {@code movreg} writes that is not a segment register: {@code bp} takes an
     * immediate, so there is no sequence and no scratch register, and a value put there is a plain
     * copy ({@code docs/ir.md} §8.1).
     */
    private static void writesTheBasePointer() {
        Assert.assertEquals("org 0x100\n\n$main:\n    mov bp, 0x1234\n    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    movreg bp, 0x1234\n"
                        + "    ret\n"));
        String fromValue = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n"
                + "$main:\n"
                + "    var x: u16\n"
                + "    x = word [0x40]\n"
                + "    movreg bp, x\n"
                + "    word [0x42] = x\n"
                + "    ret\n");
        Assert.assertTrue(fromValue.contains("    mov bp, "),
                "a value goes into bp with one move: " + fromValue);
        Assert.assertTrue(fromValue.contains("    mov word [0x42], "),
                "and the value it was copied from is untouched afterwards: " + fromValue);
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

    /**
     * {@code y = byte x}: the low byte of a value, which the machine already has where the value is,
     * so the narrowing is one move and never arithmetic ({@code docs/ir.md} §3.5).
     *
     * <p>This is the shape a program wants when it takes the low half of something: what an assembly
     * author would write as {@code mov al, cl}, and what the surface asks for with the word
     * {@code byte}.
     */
    private static void narrowsToTheLowByte() {
        Assert.assertEquals("org 0x100\n\n$main:\n"
                        + "    mov cx, word [0x40]\n"
                        + "    mov al, cl\n"
                        + "    mov byte [0x42], al\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var x: u16\n"
                        + "    var y: u8\n"
                        + "    x = word [0x40]\n"
                        + "    y = byte x\n"
                        + "    byte [0x42] = y\n"
                        + "    ret\n"));
    }

    /**
     * The same narrowing with the wide value still to be read afterwards, so the two live in
     * different registers and the copy is the one instruction that is left.
     */
    private static void narrowsAValueItStillNeeds() {
        Assert.assertEquals("org 0x100\n\n$main:\n"
                        + "    mov cx, word [0x40]\n"
                        + "    mov al, cl\n"
                        + "    mov word [0x42], cx\n"
                        + "    mov byte [0x44], al\n"
                        + "    ret\n",
                Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var x: u16\n"
                        + "    var y: u8\n"
                        + "    x = word [0x40]\n"
                        + "    y = byte x\n"
                        + "    word [0x42] = x\n"
                        + "    byte [0x44] = y\n"
                        + "    ret\n"));
    }

    /**
     * A narrowing of a value that had to wait in its home across a call that destroys every
     * register. The low byte has to come out of the cell, not out of a register the call has already
     * overwritten — which is what the allocator gets wrong if its liveness does not see the operand
     * a narrowing reads ({@code docs/ir.md} §3.5, §8.1).
     */
    private static void narrowsFromAHome() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n"
                + "$cell: pad 2\n\n$main:\n"
                + "    var x: u16 in $cell\n"
                + "    var y: u8\n"
                + "    x = word [0x40]\n"
                + "    int 0x13 clobbers(ax, bx, cx, dx, si, di)\n"
                + "    y = byte x\n"
                + "    byte [0x42] = y\n"
                + "    ret\n");
        int stored = assembly.indexOf("mov word [$cell], ");
        int called = assembly.indexOf("int 0x13");
        int loaded = assembly.indexOf("word [$cell]", called);
        int narrowed = assembly.indexOf("mov byte [0x42], ");
        Assert.assertTrue(stored >= 0 && called > stored,
                "the value waits in its home across the call: " + assembly);
        Assert.assertTrue(loaded > called, "and comes back out of it afterwards: " + assembly);
        Assert.assertTrue(narrowed > loaded,
                "so the low byte is taken from what came back: " + assembly);
    }

    /**
     * {@code x = movzx y}: the extension this machine has no instruction for, so it is the sequence
     * the target declares — the byte into {@code al}, {@code ah} cleared, the answer copied out of
     * {@code ax} ({@code docs/ir.md} §3.5).
     */
    private static void widensWithZeroes() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var small: u8\n"
                + "    var wide: u16\n"
                + "    small = byte [0x40]\n"
                + "    wide = movzx small\n"
                + "    word [0x42] = wide\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("xor ah, ah"),
                "the top half is cleared with the one instruction this machine has: " + assembly);
        Assert.assertTrue(assembly.contains("mov al, "),
                "and the byte goes into al, which is what xor ah, ah extends: " + assembly);
        Assert.assertFalse(assembly.contains("movzx"),
                "there is no such instruction on this machine: " + assembly);
    }

    /** {@code x = movsx y}: {@code cbw} is the machine's own sign extension of al into ax. */
    private static void widensWithTheSign() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var small: i8\n"
                + "    var wide: i16\n"
                + "    small = byte [0x40]\n"
                + "    wide = movsx small\n"
                + "    word [0x42] = wide\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("    cbw\n"),
                "sign extension is cbw: " + assembly);
        Assert.assertFalse(assembly.contains("movsx"),
                "which is what a 386 calls movsx: " + assembly);
    }

    /**
     * The sequence works on {@code ax} and nowhere else, so it says so: a value alive across the
     * widening is kept out of that register, and the instruction that destroys it is the one the
     * sequence wrote ({@code docs/ir.md} §3.5, §11).
     */
    private static void keepsAValueOutOfTheWideningRegister() {
        String assembly = Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    var small: u8\n"
                + "    var wide: u16\n"
                + "    var keep: u16\n"
                + "    keep = word [0x44]\n"
                + "    small = byte [0x40]\n"
                + "    wide = movzx small\n"
                + "    word [0x42] = keep\n"
                + "    word [0x46] = wide\n"
                + "    ret\n");
        Assert.assertTrue(assembly.contains("xor ah, ah"), "the widening is there: " + assembly);
        Assert.assertFalse(assembly.contains("mov word [0x42], ax"),
                "and the value that lives across it did not end up in the register it destroys: "
                        + assembly);
    }

    /**
     * A widening into a double word would need two registers to hold the answer, and this back end
     * can name one — so it is refused rather than done halfway.
     */
    private static void refusesAWideningToADoubleWord() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var small: u8\n"
                        + "    var wide: u32\n"
                        + "    small = byte [0x40]\n"
                        + "    wide = movzx small\n"
                        + "    cmp wide, 0\n"
                        + "    jz done\ndone:\n"
                        + "    ret\n"));
        Assert.assertTrue(refused.getMessage().contains("widening to a u32"),
                refused.getMessage());
    }

    /**
     * A conversion reads a register's half, and which register a load would land in is not decided
     * at the point the sequence is written — so the value goes into a variable first, which is what
     * the surface says about every other operand that has to be computed
     * ({@code docs/ir.md} §3.5).
     */
    private static void refusesAWideningOfALoad() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> Compiler.compile("t.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    var p: u16\n"
                        + "    var wide: u16\n"
                        + "    p = 0x1000\n"
                        + "    wide = movzx byte [p]\n"
                        + "    word [0x42] = wide\n"
                        + "    ret\n"));
        Assert.assertTrue(refused.getMessage().contains("put the value in a variable first"),
                refused.getMessage());
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
