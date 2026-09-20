package i8086.ir;

import i8086.CompileError;
import i8086.testing.Assert;
import i8086.testing.Suite;
import i8086.target.I8086;
import i8086.target.Targets;

/**
 * Tests for machine statements: {@code int 0x13}, {@code hlt}, {@code cli}
 * ({@code docs/ir.md} §11).
 *
 * <p>What makes them worth having over an inline block is that the compiler
 * understands them: a block is opaque in both directions, and these say what they do
 * — an effect, and a list of what they destroy. The list is the interesting part,
 * because only the program knows what a handler keeps, so saying nothing means
 * "everything" and the compiler refuses a value that has to live across it.
 */
public final class MachineTest {

    private static final String HEAD = "target 8086\norg 0x100\nentry $main\n\n$main:\n";

    private MachineTest() {
    }

    public static void register(Suite suite) {
        suite.add("A machine statement is written as it is on the machine",
                MachineTest::writesItself);
        suite.add("A machine statement says what it destroys",
                MachineTest::saysWhatItDestroys);
        suite.add("Saying nothing means everything", MachineTest::sayingNothingMeansEverything);
        suite.add("The flags survive what does not touch them", MachineTest::flagsSurvive);
        suite.add("The flags are the handler's after an interrupt",
                MachineTest::flagsAreTheHandlersAfterAnInterrupt);
        suite.add("The direction flag is a flag of its own",
                MachineTest::theDirectionFlagIsItsOwn);
        suite.add("A repeated copy says where its count and pointers come from",
                MachineTest::copiesAStretch);
        suite.add("A statement about where a copy goes writes itself",
                MachineTest::writesItselfToo);
        suite.add("The flags do not survive an interrupt", MachineTest::flagsDieAtAnInterrupt);
        suite.add("The target says which statements it has", MachineTest::theTargetSays);
        suite.add("A machine statement refuses an immediate that does not fit",
                MachineTest::refusesWideImmediate);
        suite.add("A machine statement refuses a missing immediate",
                MachineTest::refusesMissingImmediate);
        suite.add("A machine statement round-trips through the printer",
                MachineTest::roundTrips);
    }

    private static Module parse(String body) {
        return IrParser.parse("test.ir", HEAD + body);
    }

    private static String printed(String body) {
        return IrPrinter.print(parse(body));
    }

    private static String became(String body) {
        String text = printed(body);
        return text.substring(text.indexOf("\n$main:\n") + "\n$main:\n".length());
    }

    private static String assembly(String body) {
        return i8086.Compiler.compile("t.ir", HEAD + body);
    }

    private static void writesItself() {
        Assert.assertEquals("    cli\n"
                        + "    int 0x10 clobbers(ax, cx, dx, bx, si, di, flags)\n"
                        + "    sti\n"
                        + "    hlt\n"
                        + "    nop\n"
                        + "    iret clobbers(flags)\n",
                became("    cli\n"
                        + "    int 0x10\n"
                        + "    sti\n"
                        + "    hlt\n"
                        + "    nop\n"
                        + "    iret\n"));
    }

    /**
     * And two more write themselves with nothing after them: their list is empty, and the
     * canonical form writes what the compiler assumes, so a statement that destroys nothing says
     * nothing.
     */
    private static void writesItselfToo() {
        Assert.assertEquals("    cld\n"
                        + "    std\n",
                became("    cld\n"
                        + "    std\n"));
    }

    /**
     * A repeated copy, which is what the string operations and their prefix are for: the machine
     * moves a stretch of memory itself, and the surface says where the count and the two pointers
     * come from with the same clause an interrupt uses for its arguments.
     *
     * <p>And the direction flag is read by it, which is why the {@code cld} has to be there: the
     * check is on the form, so the {@code cld} may be a stretch of code above the copy — including
     * across a label, which is where a source-order check would give up.
     */
    private static void copiesAStretch() {
        String body = "    var src: u16\n"
                + "    var dst: u16\n"
                + "    var count: u16\n"
                + "    src = 0x7E00\n"
                + "    dst = 0x8000\n"
                + "    count = 0x200\n"
                + "    cld\n"
                + "    jmp $chunk\n"
                + "$chunk:\n"
                + "    rep movsb with cx = count, si = src, di = dst\n"
                + "    ret\n";
        String assembly = assembly(body);
        Assert.assertTrue(assembly.contains("    rep movsb\n"), assembly);
        Assert.assertTrue(assembly.contains("    cld\n"), assembly);

        // The must-not: nothing set the direction flag, so which way the copy walks is whatever the
        // machine happened to be pointing, and that is a refusal rather than a guess.
        String wrong = "    var src: u16\n"
                + "    var dst: u16\n"
                + "    src = 0x7E00\n"
                + "    dst = 0x8000\n"
                + "    rep movsb with cx = 0x200, si = src, di = dst\n"
                + "    ret\n";
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> i8086.Compiler.compile("t.ir", HEAD + wrong));
        Assert.assertTrue(refused.getMessage().contains("direction"), refused.getMessage());

        // And the second must-not, which is the one a source-order check cannot see: one path sets
        // the flag and the other does not, so on that path the copy has nothing to go by.
        String halfAPath = "    var x: u16\n"
                + "    var src: u16\n"
                + "    var dst: u16\n"
                + "    x = word [0x40]\n"
                + "    src = 0x7E00\n"
                + "    dst = 0x8000\n"
                + "    cmp x, 0\n"
                + "    jz there\n"
                + "    cld\n"
                + "there:\n"
                + "    rep movsb with cx = 0x200, si = src, di = dst\n"
                + "    ret\n";
        CompileError half = Assert.assertThrows(CompileError.class,
                () -> i8086.Compiler.compile("t.ir", HEAD + halfAPath));
        Assert.assertTrue(half.getMessage().contains("every way"), half.getMessage());
    }

    private static void saysWhatItDestroys() {
        // The declaration is what makes a value able to live across the statement:
        // here the allocator has an unclobbered register to put it in.
        String withDeclaration = "    var n: i16\n"
                + "    var p: i16\n"
                + "    p = 0x1000\n"
                + "    n = [p]\n"
                + "    int 0x10 clobbers(ax, bx, cx, dx, flags)\n"
                + "    word [0x40] = n\n"
                + "    ret\n";
        Assert.assertTrue(assembly(withDeclaration).contains("    int 0x10\n"),
                assembly(withDeclaration));
        Assert.assertFalse(assembly(withDeclaration).contains("clobbers"),
                "a declaration is for the optimiser, not for the machine");
    }

    private static void sayingNothingMeansEverything() {
        String silent = "    var n: i16\n"
                + "    var p: i16\n"
                + "    p = 0x1000\n"
                + "    n = [p]\n"
                + "    int 0x10\n"
                + "    word [0x40] = n\n"
                + "    ret\n";
        // The refusal names what got in the way, which is the compiler saying "say
        // what the handler keeps" rather than guessing on the program's behalf.
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> i8086.Compiler.compile("t.ir", HEAD + silent));
        Assert.assertTrue(refused.getMessage().contains("destroys"), refused.getMessage());
    }

    private static void flagsSurvive() {
        // 'cli' touches the interrupt flag, not the arithmetic ones, so a comparison
        // read after it still has its flags (docs/ir.md §4.3) — and that means the
        // comparison itself is what the branch reads, so it is still there. Believing
        // the flags were the statement's own is what let this comparison be deleted,
        // which leaves the branch reading whatever the machine happened to have.
        String body = "    var x: u16\n"
                + "    x = 1\n"
                + "    cmp x, 2\n"
                + "    cli\n"
                + "    jc there\n"
                + "there:\n"
                + "    ret\n";
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    mov ax, 1\n"
                        + "    cmp ax, 2\n"
                        + "    cli\n"
                        + "    jc $there\n"
                        + "\n"
                        + "$there:\n"
                        + "    ret\n",
                assembly(body));
    }

    /**
     * And the direction flag is a flag of its own, which is what makes {@code cld} writable at all:
     * it decides where the next copy goes and has nothing to say about the comparison in front of
     * it, so the comparison is still there and the branch behind it still reads it.
     */
    private static void theDirectionFlagIsItsOwn() {
        String body = "    var x: u16\n"
                + "    x = word [0x40]\n"
                + "    cmp x, 0x80\n"
                + "    cld\n"
                + "    std\n"
                + "    jb there\n"
                + "there:\n"
                + "    ret\n";
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    cmp word [0x40], 0x80\n"
                        + "    cld\n"
                        + "    std\n"
                        + "    jc $there\n"
                        + "\n"
                        + "$there:\n"
                        + "    ret\n",
                assembly(body));
    }

    /**
     * A machine statement takes its flags from the target, and the flags are not the list's to
     * describe: {@code cli} may not name the flags and still leave a comparison standing, while
     * {@code int 0x13 clobbers(ax, bx, cx, dx)} means the handler's carry is what the branch reads.
     */
    private static void flagsAreTheHandlersAfterAnInterrupt() {
        String body = "    var x: u16\n"
                + "    x = 1\n"
                + "    cmp x, 2\n"
                + "    int 0x10 clobbers(ax, bx, cx, dx)\n"
                + "    jc there\n"
                + "there:\n"
                + "    ret\n";
        Assert.assertEquals("org 0x100\n"
                        + "\n"
                        + "$main:\n"
                        + "    int 0x10\n"
                        + "    jc $there\n"
                        + "\n"
                        + "$there:\n"
                        + "    ret\n",
                assembly(body));
    }

    private static void flagsDieAtAnInterrupt() {
        // An interrupt goes into code this module has never seen, so what it leaves in
        // the flags is not something the surface can claim to know.
        String body = "    var x: u16\n"
                + "    x = 1\n"
                + "    cmp x, 2\n"
                + "    int 0x10 clobbers(flags)\n"
                + "    jc there\n"
                + "there:\n"
                + "    ret\n";
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> IrVerifier.verify(parse(body), Targets.byName("8086")));
        Assert.assertTrue(refused.getMessage().contains("flags"), refused.getMessage());
    }

    private static void theTargetSays() {
        I8086 target = (I8086) Targets.byName("8086");
        Assert.assertEquals("[int, hlt, cli, sti, nop, iret, cld, std, movsb, movsw, stosb, stosw, "
                        + "lodsb, lodsw]",
                target.machineStatements().keySet().toString());
        Assert.assertEquals(Integer.valueOf(1), target.machineStatements().get("int"));
        Assert.assertEquals(Integer.valueOf(0), target.machineStatements().get("hlt"));
        Assert.assertEquals("[ax, cx, dx, bx, si, di, flags]",
                target.machineClobbers("int").toString());
        Assert.assertEquals("[flags]", target.machineClobbers("iret").toString());
        Assert.assertEquals("[]", target.machineClobbers("cli").toString());
        // The flags are the ones the statement leaves for these two, and the ones from
        // before it for the rest: clearing an interrupt flag and doing nothing are not
        // ways of computing a flag. `cld` and `std` are the direction flag's own, which is
        // the whole reason the two are asked apart.
        Assert.assertEquals("defines [flags], reads []", target.machineFlags("int").toString());
        Assert.assertEquals("defines [flags, direction], reads []",
                target.machineFlags("iret").toString());
        Assert.assertEquals("defines [direction], reads []", target.machineFlags("cld").toString());
        Assert.assertEquals("defines [direction], reads []", target.machineFlags("std").toString());
        Assert.assertEquals("defines [], reads []", target.machineFlags("cli").toString());
        Assert.assertEquals("defines [], reads []", target.machineFlags("sti").toString());
        Assert.assertEquals("defines [], reads []", target.machineFlags("hlt").toString());
        Assert.assertEquals("defines [], reads []", target.machineFlags("nop").toString());
        // And the string operations are the first thing that *reads* a flag: where they walk
        // is decided by the direction flag, and nothing else here depends on one.
        Assert.assertEquals("defines [], reads [direction]",
                target.machineFlags("movsb").toString());
        Assert.assertEquals("defines [], reads [direction]",
                target.machineFlags("stosw").toString());
        Assert.assertEquals("[cx, si, di]", target.machineClobbers("movsb").toString());
        Assert.assertEquals("[cx, di]", target.machineClobbers("stosw").toString());
        Assert.assertEquals("[cx, si, di, ax]", target.machineClobbers("lodsb").toString());
    }

    private static void refusesWideImmediate() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> parse("    int 0x100\n"));
        Assert.assertTrue(refused.getMessage().contains("does not fit"), refused.getMessage());
    }

    private static void refusesMissingImmediate() {
        CompileError refused = Assert.assertThrows(CompileError.class, () -> parse("    int\n"));
        Assert.assertTrue(refused.getMessage().contains("immediate"), refused.getMessage());
    }

    private static void roundTrips() {        // A statement is written back with its prefix, which is part of it and not part of the
        // mnemonic: the machine applies it, and the printer is what a re-read statement gets.
        Assert.assertEquals("    rep movsb clobbers(cx, si, di)\n",
                became("    rep movsb clobbers(cx, si, di)\n"));
        Assert.assertEquals("    rep stosw clobbers(cx, di)\n", became("    rep stosw\n"));
        // The list is the target's answer when the author does not give one, and it is written
        // back: a copy is about the registers that point at what it walks.
        Assert.assertEquals("    movsb clobbers(cx, si, di)\n", became("    movsb\n"));
        Assert.assertEquals("    lodsb clobbers(cx, si, di, ax)\n", became("    lodsb\n"));

        String body = "    int 0x13 clobbers(ax, bx, flags)\n    hlt\n";
        String once = printed(body);
        Assert.assertEquals(once, IrPrinter.print(IrParser.parse("test.ir", once)));
        // A list may name nothing at all, which is what a statement that touches no
        // register means, and then the canonical form writes none.
        Assert.assertEquals("    nop\n", became("    nop clobbers()\n"));
    }
}
