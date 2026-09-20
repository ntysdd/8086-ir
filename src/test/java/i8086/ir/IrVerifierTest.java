package i8086.ir;

import i8086.CompileError;
import i8086.Warning;
import i8086.Warnings;
import i8086.testing.Assert;
import i8086.testing.Suite;
import i8086.target.Targets;

/**
 * Tests for what the verifier refuses, and for what it must not refuse.
 *
 * <p>Both halves matter. A rule that fires on a correct program makes the
 * language unusable, so every rule here has a case that has to pass as well as
 * one that has to fail ({@code AGENTS.md}, invariant 4).
 *
 * <p>The body of a test program starts on line six, because a module begins
 * with three header lines, a blank line and a label.
 */
public final class IrVerifierTest {

    private IrVerifierTest() {
    }

    public static void register(Suite suite) {
        suite.add("Ir verifier accepts a variable read and written", IrVerifierTest::acceptsVariables);
        suite.add("Ir verifier takes a width from the place", IrVerifierTest::takesWidthFromPlace);
        suite.add("Ir verifier accepts a label as an address", IrVerifierTest::acceptsLabelAsAddress);
        suite.add("Ir verifier accepts a same-width signedness change",
                IrVerifierTest::acceptsSignednessChange);
        suite.add("Ir verifier accepts a register read into a value of its width",
                IrVerifierTest::acceptsRegisterReads);
        suite.add("Ir verifier refuses a register read into the wrong width",
                IrVerifierTest::refusesReadIntoTheWrongWidth);
        suite.add("Ir verifier refuses a register read into something that is not a variable",
                IrVerifierTest::refusesReadIntoWhatIsNotAVariable);
        suite.add("Ir verifier accepts the flag set in a clobber list",
                IrVerifierTest::acceptsFlagsClobber);
        suite.add("Ir verifier refuses an unknown name", IrVerifierTest::refusesUnknownName);
        suite.add("Ir verifier refuses a volatile access inside an expression",
                IrVerifierTest::refusesVolatileInAnExpression);
        suite.add("Ir verifier refuses a label as a destination",
                IrVerifierTest::refusesLabelAsDestination);
        suite.add("Ir verifier refuses a label defined twice", IrVerifierTest::refusesDuplicateLabel);
        suite.add("Ir verifier refuses one name for two things", IrVerifierTest::refusesNameClash);
        suite.add("Ir verifier refuses a declaration of the flag set",
                IrVerifierTest::refusesFlagsDeclaration);
        suite.add("Ir verifier refuses a width mismatch", IrVerifierTest::refusesWidthMismatch);
        suite.add("Ir verifier refuses a literal too wide for its place",
                IrVerifierTest::refusesWideLiteral);
        suite.add("Ir verifier refuses an assignment with no width at all",
                IrVerifierTest::refusesUnknownWidth);
        suite.add("Ir verifier refuses a prefix that disagrees with the value",
                IrVerifierTest::refusesDisagreeingPrefix);
        suite.add("Ir verifier refuses an entry label that is never defined",
                IrVerifierTest::refusesMissingEntry);
        suite.add("Ir verifier refuses a name in an address that is nothing",
                IrVerifierTest::refusesUnknownAddressBase);
        suite.add("Ir verifier refuses a segment override that is not a segment",
                IrVerifierTest::refusesBadSegment);
        suite.add("Ir verifier refuses a clobber that is not a register",
                IrVerifierTest::refusesBadClobber);
        suite.add("Ir verifier accepts a branch after a comparison",
                IrVerifierTest::acceptsBranchAfterCompare);
        suite.add("Ir verifier refuses a branch across an inline block",
                IrVerifierTest::refusesFlagsAcrossABlock);
        suite.add("Ir verifier refuses a branch with no flags behind it",                IrVerifierTest::refusesBranchWithoutFlags);
        suite.add("Ir verifier clears the flags at a label", IrVerifierTest::refusesBranchAfterLabel);
        suite.add("Ir verifier refuses a branch after a block that clobbers flags",
                IrVerifierTest::refusesBranchAfterClobber);
        suite.add("Ir verifier accepts a jump without any flags", IrVerifierTest::acceptsJumpWithoutFlags);
        suite.add("Ir verifier refuses a branch to a label that is never defined",
                IrVerifierTest::refusesBranchToNothing);
        suite.add("Ir verifier refuses a branch to a variable", IrVerifierTest::refusesBranchToVariable);
        suite.add("Ir verifier refuses a comparison of two widths",
                IrVerifierTest::refusesCompareWidths);
        suite.add("Ir verifier refuses a comparison against nothing",
                IrVerifierTest::refusesCompareUnknown);
        suite.add("Ir verifier accepts arithmetic and conversions",
                IrVerifierTest::acceptsArithmeticAndConversions);
        suite.add("Ir verifier accepts a carry add after a comparison",
                IrVerifierTest::acceptsCarryAddAfterCompare);
        suite.add("Ir verifier refuses a load inside expr", IrVerifierTest::refusesLoadInExpr);
        suite.add("Ir verifier refuses a label inside expr", IrVerifierTest::refusesLabelInExpr);
        suite.add("Ir verifier refuses a carry operation inside expr",
                IrVerifierTest::refusesCarryInExpr);
        suite.add("Ir verifier refuses a wide division", IrVerifierTest::refusesWideDivision);
        suite.add("Ir verifier refuses mixed signedness in a symbol operator",
                IrVerifierTest::refusesMixedSignedness);
        suite.add("Ir verifier refuses mixed widths in one expression",
                IrVerifierTest::refusesMixedWidths);
        suite.add("Ir verifier refuses a widening that does not widen",
                IrVerifierTest::refusesPointlessWidening);
        suite.add("Ir verifier refuses a narrowing of something already narrow",
                IrVerifierTest::refusesPointlessNarrowing);
        suite.add("Ir verifier refuses converting a literal", IrVerifierTest::refusesConversionOfLiteral);
        suite.add("Ir verifier refuses a widening with nowhere to widen to",
                IrVerifierTest::refusesWideningWithNoDestination);
        suite.add("Ir verifier refuses a carry operation with nothing behind it",
                IrVerifierTest::refusesCarryWithNothingBehindIt);
        suite.add("Ir verifier refuses a branch after expr gave the flags up",
                IrVerifierTest::refusesBranchAfterExpr);
        suite.add("Ir verifier refuses two block labels with one name",
                IrVerifierTest::refusesRepeatedBlockLabel);
        suite.add("Ir verifier refuses a block label that is already a name",
                IrVerifierTest::refusesBlockLabelClash);
        suite.add("Ir verifier accepts homes, shared and wide enough",
                IrVerifierTest::acceptsHomes);
        suite.add("Ir verifier refuses a home narrower than the variable",
                IrVerifierTest::refusesNarrowHome);
        suite.add("Ir verifier refuses a home that names a place in the code",
                IrVerifierTest::refusesCodeLabelAsHome);
        suite.add("Ir verifier refuses a home whose length only the assembler knows",
                IrVerifierTest::refusesPadToAsHome);
        suite.add("Ir verifier refuses a home that names nothing",
                IrVerifierTest::refusesUnknownHome);
        suite.add("Ir verifier refuses a variable as a home",
                IrVerifierTest::refusesVariableAsHome);
        suite.add("Ir verifier refuses a second variable on a writethrough cell",
                IrVerifierTest::refusesSharedWritethroughHome);
        suite.add("Ir verifier refuses a writethrough home until it is honoured",
                IrVerifierTest::refusesWritethroughUntilBuilt);
        suite.add("Ir verifier refuses a label as segmentation state",
                IrVerifierTest::refusesALabelAsSegmentationState);
        suite.add("Ir verifier refuses a value too wide for segmentation state",
                IrVerifierTest::refusesAWideSegmentationValue);
        suite.add("Ir verifier refuses a byte value as an address",
                IrVerifierTest::refusesAByteAddress);
        suite.add("Ir verifier warns about a store into a shared cell",
                IrVerifierTest::warnsAboutSavesIntoSharedCells);
        suite.add("Ir verifier keeps quiet where a save is promised to stay",
                IrVerifierTest::isQuietAboutSavesThatArePromised);
    }

    // --- homes (§3.1.2) ----------------------------------------------------

    /**
     * A home is bytes that are wide enough, and one cell may be two variables' home,
     * because a cell is handed out the way a register is. What is measured is the bytes
     * the item holds, which for a list of data is the elements counted rather than the
     * directive's width: `db "hello"` is five and `dw a, b` is four.
     */
    private static void acceptsHomes() {
        verify("    var left: u16 in cell\n"
                + "    var hand: u16 in cell\n"
                + "    word [0x40] = left\n"
                + "    word [0x40] = hand\n"
                + "    var four: u32 in msg\n"
                + "    dword [0x40] = four\n"
                + "    var pair: u32 in table\n"
                + "    dword [0x40] = pair\n"
                + "cell: pad 2\n"
                + "msg: db \"hello\"\n"
                + "table: dw 0x1234, 0x5678\n");
    }

    /**
     * A home narrower than the variable is a program that would read the byte after it, so
     * it is refused, and the refusal is at the declaration that asked for it.
     */
    private static void refusesNarrowHome() {
        CompileError refused = Assert.assertRefused("test.ir:6:5",
                () -> verify("    var wide: u32 in cell\ncell: pad 2\n"));
        Assert.assertTrue(refused.getMessage().contains("2 byte(s)"),
                "the refusal says how wide the home is: " + refused.getMessage());
    }

    private static void refusesCodeLabelAsHome() {
        CompileError refused = Assert.assertRefused("test.ir:6:5",
                () -> verify("    var x: u16 in main\n"));
        Assert.assertTrue(refused.getMessage().contains("place in the code"),
                "a label is a place, not storage: " + refused.getMessage());
    }

    private static void refusesPadToAsHome() {
        CompileError refused = Assert.assertRefused("test.ir:6:5",
                () -> verify("    var x: u16 in lay\nlay: pad to 0x100\n"));
        Assert.assertTrue(refused.getMessage().contains("assembler"),
                "only the assembler knows how long a 'pad to' is: " + refused.getMessage());
    }

    private static void refusesUnknownHome() {
        Assert.assertRefused("test.ir:6:5", () -> verify("    var x: u16 in nowhere\n"));
    }

    private static void refusesVariableAsHome() {
        CompileError refused = Assert.assertRefused("test.ir:7:5",
                () -> verify("    var other: u16\n    var x: u16 in other\n"));
        Assert.assertTrue(refused.getMessage().contains("register"),
                "a variable is a register, not bytes: " + refused.getMessage());
    }

    /**
     * A cell one variable keeps current is that variable's alone, whichever of the two
     * declarations comes first: a reader of those bytes has to be able to tell whose
     * value it is looking at ({@code docs/ir.md} §3.1.2). Both orders are the same
     * mistake, and both are refused at the declaration that comes second.
     */
    private static void refusesSharedWritethroughHome() {
        Assert.assertRefused("test.ir:7:5",
                () -> verify("    var a: u16 in cell\n"
                        + "    var b: u16 in cell writethrough\ncell: pad 2\n"));
        Assert.assertRefused("test.ir:7:5",
                () -> verify("    var b: u16 in cell writethrough\n"
                        + "    var a: u16 in cell\ncell: pad 2\n"));
    }

    /**
     * An address is a near pointer, and a pointer is two bytes: a byte value that tried to be one
     * would be half an address, and this machine has no eight-bit register that can go inside the
     * brackets either ({@code docs/ir.md} §3.3, §3.4).
     */
    private static void refusesAByteAddress() {
        CompileError refused = Assert.assertRefused("test.ir:7:5",
                () -> verify("    var c: u8\n    word [c] = 2\n"));
        Assert.assertTrue(refused.getMessage().contains("near pointer"),
                refused.getMessage());
    }

    /**
     * A label is an address, so it is not something a segment register can be set to: the two are
     * different kinds of thing, and the refusal says so rather than letting the assembler find out.
     */
    private static void refusesALabelAsSegmentationState() {
        CompileError refused = Assert.assertRefused("test.ir:6:16",
                () -> verify("    movreg ds, msg\nmsg: db 1\n"));
        Assert.assertTrue(refused.getMessage().contains("is a label, which is an address"),
                refused.getMessage());
    }

    /**
     * Segmentation state is one word wide, so a value of another width does not fit — the same
     * question the two sides of an assignment are asked, asked of a place that is the machine's.
     */
    private static void refusesAWideSegmentationValue() {
        CompileError refused = Assert.assertRefused("test.ir:8:16",
                () -> verify("    var wide: u32\n    var x: u16\n    movreg ds, wide\n"));
        Assert.assertTrue(refused.getMessage().contains("4-byte value does not fit"),
                refused.getMessage());
    }

    /**
     * A home in the default mode is accepted and not used, which costs the program
     * nothing it was promised. {@code writethrough} is a promise that would be broken
     * silently, so it is refused until the allocator honours a home.
     */
    private static void refusesWritethroughUntilBuilt() {
        CompileError refused = Assert.assertRefused("test.ir:6:5",
                () -> verify("    var x: u16 in cell writethrough\ncell: pad 2\n"));
        Assert.assertTrue(refused.getMessage().startsWith("not implemented yet:"),
                "it says so plainly: " + refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("docs/ir.md"),
                "and points at the section that specifies it: " + refused.getMessage());
    }

    /**
     * A store into bytes that more than one variable calls its home is not promised to
     * stay there, and that is the warning ({@code docs/ir.md} §3.1.2). It is said at every
     * store, because what the author has to read is the line the store is on, and it names
     * the variables that declared the cell so that they can be found.
     */
    private static void warnsAboutSavesIntoSharedCells() {
        Warnings said = warnings("    var kept: u16 in cell\n"
                + "    var other: u16 in cell\n"
                + "    kept = word [0x40]\n"
                + "    word [cell] = kept\n"
                + "    word [cell] = 1\n"
                + "cell: pad 2\n");
        Assert.assertEquals(2L, said.all().size());
        Warning first = said.all().get(0);
        Assert.assertEquals("test.ir:9:5", first.position().toString());
        Assert.assertTrue(first.message().contains("'cell' is the home of 'kept' and 'other'"),
                first.message());
        Assert.assertEquals("test.ir:10:5", said.all().get(1).position().toString());
        Assert.assertTrue(first.format().startsWith("test.ir:9:5: warning: "), first.format());
    }

    /**
     * The other half of the warning: where the bytes are promised to stay, there is
     * nothing to say. A cell one variable holds is that variable's by the rule above; a
     * cell no variable declares belongs to the program; and a store that is not written as
     * the cell — past its start, or through another segment — is not a store into it.
     */
    private static void isQuietAboutSavesThatArePromised() {
        Assert.assertEquals(0L, warnings("    var kept: u16 in cell\n"
                + "    word [cell] = kept\n"
                + "cell: pad 2\n").all().size());
        Assert.assertEquals(0L, warnings("    var kept: u16 in cell\n"
                + "    word [free] = kept\n"
                + "cell: pad 2\nfree: pad 2\n").all().size());
        Assert.assertEquals(0L, warnings("    var a: u16 in cell\n"
                + "    var b: u16 in cell\n"
                + "    word [cell + 2] = a\n"
                + "    es:[cell] = a\n"
                + "    word [0x40] = a\n"
                + "cell: pad 2\n").all().size());
    }

    /**
     * A label inside a block is local in what can read it and not in what it is
     * called: the emitted text is one flat assembly file, so two of anything cannot
     * share a name ({@code docs/ir.md} §9, {@code docs/asm.md} §3).
     */
    private static void refusesRepeatedBlockLabel() {
        String twice = "    asm clobbers(ax, flags) {\n    again:\n        dec ax\n"
                + "        jnz again\n    again:\n        ret\n    }\n    ret\n";
        Assert.assertTrue(Assert.assertThrows(CompileError.class, () -> verify(twice))
                .getMessage().contains("already a label inside a block"), "one block, twice");
        String twoBlocks = "    asm clobbers(ax, flags) {\n    again:\n        dec ax\n    }\n"
                + "    asm clobbers(ax, flags) {\n    again:\n        inc ax\n    }\n    ret\n";
        Assert.assertTrue(Assert.assertThrows(CompileError.class, () -> verify(twoBlocks))
                .getMessage().contains("already a label inside a block"), "two blocks");
    }

    private static void refusesBlockLabelClash() {
        String withLabel = "    asm clobbers(ax, flags) {\n    same:\n        dec ax\n    }\n"
                + "    ret\nsame:\n    ret\n";
        Assert.assertTrue(Assert.assertThrows(CompileError.class, () -> verify(withLabel))
                .getMessage().contains("already a label"), "a module label");
        String withVariable = "    var again: i16\n"
                + "    asm clobbers(ax, flags) {\n    again:\n        dec ax\n    }\n    ret\n";
        Assert.assertTrue(Assert.assertThrows(CompileError.class, () -> verify(withVariable))
                .getMessage().contains("variable"), "a variable");
    }

    private static void verify(String body) {
        IrVerifier.verify(parse(body), Targets.byName("8086"));
    }

    /** Verifies a body and hands back what the verifier had to say about it. */
    private static Warnings warnings(String body) {
        Warnings warnings = new Warnings();
        IrVerifier.verify(parse(body), Targets.byName("8086"), warnings);
        return warnings;
    }

    private static Module parse(String body) {
        return IrParser.parse("test.ir", program(body));
    }

    private static String program(String body) {
        return "target 8086\norg 0x100\nentry $main\n\n$main:\n" + body;
    }

    private static void refuses(String position, String body) {
        Assert.assertRefused(position, () -> verify(body));
    }

    // --- what has to pass --------------------------------------------------

    private static void acceptsVariables() {
        verify("    var x: u16\n    x = 1\n    x = 2\n    var y: u8\n    y = 3\n");
    }

    private static void takesWidthFromPlace() {
        verify("    var x: u16\n    var p: u16\n    x = [p]\n    [p] = x\n    word [p] = x\n");
    }

    private static void acceptsLabelAsAddress() {
        verify("    var x: u16\n    var p: u16\n    p = msg\n    x = [msg]\nmsg: db 0\n");
    }

    private static void acceptsSignednessChange() {
        verify("    var signed: i16\n    var unsigned: u16\n    unsigned = 1\n    signed = unsigned\n");
    }

    /**
     * A register is read into a value of its own width ({@code docs/ir.md} §8.1): a byte into a
     * byte, a word into a word, whichever register of the machine it is — the registers a value
     * cannot live in are read the same way as the ones it can.
     */
    private static void acceptsRegisterReads() {
        verify("    var drive: u8\n    movreg drive, dl\n    movreg drive, ah\n"
                + "    var seg: u16\n    movreg seg, ds\n    movreg seg, sp\n"
                + "    var same: u16\n    movreg same, bx\n");
    }

    /**
     * The width rule is the one both sides of an assignment follow, and the refusal says which of
     * the two is which ({@code docs/ir.md} §8.1, §3.2).
     */
    private static void refusesReadIntoTheWrongWidth() {
        Assert.assertTrue(Assert.assertThrows(CompileError.class,
                () -> verify("    var x: u16\n    movreg x, dl\n"))
                .getMessage().contains("byte"), "a word read out of a byte register");
        Assert.assertTrue(Assert.assertThrows(CompileError.class,
                () -> verify("    var y: u8\n    movreg y, bx\n"))
                .getMessage().contains("byte"), "a byte read out of a word register");
    }

    /**
     * What a register is read into is a value of the program's, so it has to be a variable: a label
     * is an address, and a name nobody declared is nothing at all.
     */
    private static void refusesReadIntoWhatIsNotAVariable() {
        Assert.assertTrue(Assert.assertThrows(CompileError.class,
                () -> verify("    var drive: u8\n    movreg drive, dl\nmsg: db 0\n"
                        + "    movreg msg, dl\n"))
                .getMessage().contains("label"), "a label");
        Assert.assertTrue(Assert.assertThrows(CompileError.class,
                () -> verify("    var drive: u8\n    movreg drive, dl\n    movreg other, dl\n"))
                .getMessage().contains("unknown name"), "a name nobody declared");
    }

    private static void acceptsFlagsClobber() {
        verify("    asm clobbers(ax, dx, flags) {\n        int 0x21\n    }\n");
    }

    // --- what has to be refused --------------------------------------------

    private static void refusesUnknownName() {
        refuses("test.ir:6:5", "    nothing = 1\n");
    }

    /**
     * A volatile access must happen exactly once, and an expression is exactly what a
     * compiler is allowed to take apart: reorder, share, duplicate. So the two are
     * kept apart by the surface rather than by every optimisation remembering
     * ({@code docs/ir.md} §3.4).
     */
    private static void refusesVolatileInAnExpression() {
        refuses("test.ir:8:18", "    var x: u16\n    var p: u16\n"
                + "    x = eval(x + volatile [p])\n");
        verify("    var x: u16\n    var p: u16\n    x = volatile [p]\n");
    }

    private static void refusesLabelAsDestination() {
        refuses("test.ir:6:5", "    msg = 1\nmsg: db 0\n");
    }

    private static void refusesDuplicateLabel() {
        refuses("test.ir:9:1", "    ret\nhere:\n    ret\nhere:\n");
    }

    private static void refusesNameClash() {
        refuses("test.ir:7:5", "    var x: u16\n    x:\n");
    }

    private static void refusesFlagsDeclaration() {
        refuses("test.ir:6:5", "    var flags: u16\n");
    }

    private static void refusesWidthMismatch() {
        refuses("test.ir:8:14", "    var narrow: u8\n    var wide: u16\n    narrow = wide\n");
    }

    private static void refusesWideLiteral() {
        refuses("test.ir:7:14", "    var narrow: u8\n    narrow = 0x100\n");
    }

    private static void refusesUnknownWidth() {
        refuses("test.ir:8:5", "    var p: u16\n    var q: u16\n    [p] = [q]\n");
    }

    private static void refusesDisagreeingPrefix() {
        refuses("test.ir:7:16", "    var x: u16\n    byte [x] = x\n");
    }

    private static void refusesMissingEntry() {
        Module module = IrParser.parse("test.ir",
                "target 8086\norg 0x100\nentry nowhere\n\n$main:\n    ret\n");
        Assert.assertRefused("test.ir:3:1",
                () -> IrVerifier.verify(module, Targets.byName("8086")));
    }

    private static void refusesUnknownAddressBase() {
        refuses("test.ir:7:9", "    var x: u16\n    x = [nothing]\n");
    }

    private static void refusesBadSegment() {
        refuses("test.ir:7:9", "    var x: u16\n    x = ax:[x]\n");
    }

    private static void refusesBadClobber() {
        refuses("test.ir:6:5", "    asm clobbers(zz) {\n        int 0x21\n    }\n");
    }

    // --- flags (§4.3) ------------------------------------------------------\n
    private static void acceptsBranchAfterCompare() {
        verify("    var x: u16\n    cmp x, 0\n    jz done\n    test x, 1\n    jnz done\n"
                + "done:\n    ret\n");
    }

    private static void refusesFlagsAcrossABlock() {
        // A block destroys the flags whatever its list says: the list is about registers,
        // and the only honest answer about code this pass cannot read is the worst one.
        refuses("test.ir:11:5", "    var x: u16\n    cmp x, 0\n    asm clobbers(ax, dx) {\n"
                + "        mov ah, 9\n    }\n    jz done\ndone:\n    ret\n");
    }

    private static void refusesBranchWithoutFlags() {
        refuses("test.ir:7:5", "    var x: u16\n    jz main\n");
    }

    private static void refusesBranchAfterLabel() {
        refuses("test.ir:9:5", "    var x: u16\n    cmp x, 0\nhere:\n    jnz here\n");
    }

    private static void refusesBranchAfterClobber() {
        refuses("test.ir:11:5", "    var x: u16\n    cmp x, 0\n    asm clobbers(flags) {\n"
                + "        int 0x21\n    }\n    jz main\n");
    }

    private static void acceptsJumpWithoutFlags() {
        verify("    var x: u16\n    jmp done\ndone:\n    ret\n");
    }

    private static void refusesBranchToNothing() {
        refuses("test.ir:8:5", "    var x: u16\n    cmp x, 0\n    jz nowhere\n");
    }

    private static void refusesBranchToVariable() {
        refuses("test.ir:8:5", "    var x: u16\n    cmp x, 0\n    jz x\n");
    }

    private static void refusesCompareWidths() {
        refuses("test.ir:8:17", "    var narrow: u8\n    var wide: u16\n    cmp narrow, wide\n");
    }

    private static void refusesCompareUnknown() {
        refuses("test.ir:6:9", "    cmp nothing, 0\n");
    }

    // --- expressions and conversions (§3.5, §5) ----------------------------\n
    private static void acceptsArithmeticAndConversions() {
        verify("    var a: u16\n    var b: u16\n    var small: u8\n    var p: u16\n"
                + "    a = eval(a + b)\n    eval(a * b)\n    a = expr(a + b * a)\n"
                + "    small = byte a\n    a = movzx small\n    a = movsx small\n"
                + "    a = movzx byte [p]\n    a = eval(a / b)\n    a = eval(a % b)\n");
    }

    private static void acceptsCarryAddAfterCompare() {
        verify("    var a: u16\n    var b: u16\n    cmp a, 0\n    a = eval(a adc b)\n");
    }

    private static void refusesLoadInExpr() {
        refuses("test.ir:8:14", "    var a: u16\n    var p: u16\n    a = expr([p] + a)\n");
    }

    private static void refusesLabelInExpr() {
        refuses("test.ir:7:14", "    var a: u16\n    a = expr(msg + a)\nmsg: db 0\n");
    }

    private static void refusesCarryInExpr() {
        refuses("test.ir:7:16", "    var a: u16\n    a = expr(a adc a)\n");
    }

    private static void refusesWideDivision() {
        refuses("test.ir:7:16", "    var a: u32\n    a = eval(a / a)\n");
    }

    private static void refusesMixedSignedness() {
        refuses("test.ir:8:16", "    var u: u16\n    var i: i16\n    u = eval(u + i)\n");
    }

    private static void refusesMixedWidths() {
        refuses("test.ir:8:18", "    var a: u16\n    var b: u8\n    a = eval(b + a)\n");
    }

    private static void refusesPointlessWidening() {
        refuses("test.ir:7:9", "    var a: u8\n    a = movzx a\n");
    }

    private static void refusesPointlessNarrowing() {
        refuses("test.ir:8:13", "    var small: u8\n    var tiny: u8\n    small = byte tiny\n");
    }

    private static void refusesConversionOfLiteral() {
        refuses("test.ir:7:15", "    var a: u16\n    a = movzx 5\n");
    }

    private static void refusesWideningWithNoDestination() {
        refuses("test.ir:7:9", "    var a: u8\n    cmp movzx a, 0\n");
    }

    private static void refusesCarryWithNothingBehindIt() {
        refuses("test.ir:7:9", "    var a: u16\n    a = eval(a adc a)\n");
    }

    private static void refusesBranchAfterExpr() {
        refuses("test.ir:8:5", "    var a: u16\n    a = expr(a + a)\n    jz main\n");
    }
}
