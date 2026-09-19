package i8086.ir;

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
        suite.add("Ir verifier accepts the flag set in a clobber list",
                IrVerifierTest::acceptsFlagsClobber);
        suite.add("Ir verifier refuses an unknown name", IrVerifierTest::refusesUnknownName);
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
        suite.add("Ir verifier keeps flags across an inline block that spares them",
                IrVerifierTest::acceptsFlagsAcrossSparedBlock);
        suite.add("Ir verifier refuses a branch with no flags behind it",
                IrVerifierTest::refusesBranchWithoutFlags);
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
    }

    private static void verify(String body) {
        IrVerifier.verify(parse(body), Targets.byName("8086"));
    }

    private static Module parse(String body) {
        return IrParser.parse("test.ir", program(body));
    }

    private static String program(String body) {
        return "target 8086\norg 0x100\nentry main\n\nmain:\n" + body;
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

    private static void acceptsFlagsClobber() {
        verify("    asm clobbers(ax, dx, flags) {\n        int 0x21\n    }\n");
    }

    // --- what has to be refused --------------------------------------------

    private static void refusesUnknownName() {
        refuses("test.ir:6:5", "    nothing = 1\n");
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
                "target 8086\norg 0x100\nentry nowhere\n\nmain:\n    ret\n");
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

    private static void acceptsFlagsAcrossSparedBlock() {
        verify("    var x: u16\n    cmp x, 0\n    asm clobbers(ax, dx) {\n        mov ah, 9\n    }\n"
                + "    jz done\ndone:\n    ret\n");
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
