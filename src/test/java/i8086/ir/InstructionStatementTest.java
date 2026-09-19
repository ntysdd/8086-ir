package i8086.ir;

import i8086.CompileError;
import i8086.testing.Assert;
import i8086.testing.Suite;

/**
 * Tests for instruction-shaped statements, the spelling {@code docs/ir.md} §7.3
 * allows.
 *
 * <p>The first half checks that it is a spelling and nothing more: a statement
 * written the machine's way is the same IR as the {@code eval} form and prints as
 * that form, so nothing downstream — the verifier, the passes, the printer — ever
 * sees it. The second half is the refusals, which are the interesting half: a word
 * is accepted only when the operation it names on this machine is the operation the
 * surface already has, so {@code inc} — one byte, and shorter precisely because it
 * leaves the carry alone — is not a spelling of {@code d = eval(d + 1)}.
 */
public final class InstructionStatementTest {

    private static final String HEAD = "target 8086\norg 0x100\nentry main\n\nmain:\n";

    private InstructionStatementTest() {
    }

    public static void register(Suite suite) {
        suite.add("An instruction statement is the eval form it spells",
                InstructionStatementTest::spellsTheOperation);
        suite.add("An instruction statement names its own destination",
                InstructionStatementTest::namesItsDestination);
        suite.add("An instruction statement works on memory too",
                InstructionStatementTest::writesMemory);
        suite.add("mov is the assignment", InstructionStatementTest::movesAreAssignments);
        suite.add("A word stays an ordinary name inside expressions",
                InstructionStatementTest::wordsStayNames);
        suite.add("An instruction statement refuses inc and dec",
                InstructionStatementTest::refusesIncAndDec);
        suite.add("An instruction statement refuses the implicit registers",
                InstructionStatementTest::refusesImplicitRegisters);
        suite.add("An instruction statement refuses a register operand",
                InstructionStatementTest::refusesRegisters);
        suite.add("An instruction statement refuses a tree",
                InstructionStatementTest::refusesTrees);
        suite.add("An instruction statement refuses the wrong operand count",
                InstructionStatementTest::refusesWrongOperandCount);
        suite.add("An instruction statement refuses a literal destination",
                InstructionStatementTest::refusesLiteralDestination);
        suite.add("An instruction statement refuses a word it does not know",
                InstructionStatementTest::refusesUnknownWord);
    }

    private static Module parse(String body) {
        return IrParser.parse("test.ir", HEAD + body);
    }

    private static String printed(String body) {
        return IrPrinter.print(parse(body));
    }

    /** What the written statement became, which is what everything else sees. */
    private static String became(String body) {
        String text = printed(body);
        return text.substring(text.indexOf("\nmain:\n") + "\nmain:\n".length());
    }

    private static String refusal(String body) {
        return Assert.assertThrows(CompileError.class, () -> parse(body)).getMessage();
    }

    private static void spellsTheOperation() {
        Assert.assertEquals("    var s: i16\n"
                        + "    var t: i16\n"
                        + "    s = eval(s + 1)\n"
                        + "    s = eval(s - 2)\n"
                        + "    s = eval(s & 3)\n"
                        + "    s = eval(s adc 4)\n"
                        + "    s = eval(s shl 5)\n"
                        + "    s = eval(s mul t)\n"
                        + "    s = eval(-s)\n"
                        + "    s = eval(~s)\n",
                became("    var s: i16\n"
                        + "    var t: i16\n"
                        + "    add s, 1\n"
                        + "    sub s, 2\n"
                        + "    and s, 3\n"
                        + "    adc s, 4\n"
                        + "    shl s, 5\n"
                        + "    mul s, t\n"
                        + "    neg s\n"
                        + "    not s\n"));
    }

    private static void namesItsDestination() {
        // The first operand is where the result goes, so this is s + t and not t + s:
        // the spelling is shorter than the eval form, not looser.
        Assert.assertEquals("    var s: i16\n"
                        + "    var t: i16\n"
                        + "    s = eval(s + t)\n",
                became("    var s: i16\n"
                        + "    var t: i16\n"
                        + "    add s, t\n"));
    }

    private static void writesMemory() {
        Assert.assertEquals("    word [0x40] = eval(word [0x40] + 1)\n",
                became("    add word [0x40], 1\n"));
        Assert.assertEquals("    var s: i16\n"
                        + "    s = eval(s + [0x1000])\n",
                became("    var s: i16\n"
                        + "    add s, [0x1000]\n"));
    }

    private static void movesAreAssignments() {
        Assert.assertEquals("    var s: i16\n"
                        + "    s = 1\n"
                        + "    s = [0x1000]\n"
                        + "    word [0x40] = s\n",
                became("    var s: i16\n"
                        + "    mov s, 1\n"
                        + "    mov s, [0x1000]\n"
                        + "    mov word [0x40], s\n"));
    }

    private static void wordsStayNames() {
        // The words are statements' words, not the surface's: a variable may be called
        // add, and it stays one wherever a name is expected (docs/ir.md §3.1, §7.3).
        // What comes back says '$add', because that is the canonical spelling of a name
        // that looks like a word.
        Assert.assertEquals("    var $add: i16\n"
                        + "    var s: i16\n"
                        + "    $add = 5\n"
                        + "    s = eval(s + $add)\n",
                became("    var add: i16\n"
                        + "    var s: i16\n"
                        + "    add = 5\n"
                        + "    s = eval(s + add)\n"));
    }

    private static void refusesIncAndDec() {
        // The whole point of the table being the target's: inc is not add 1, because
        // it leaves CF alone, and a reader would not see the difference.
        for (String word : new String[] {"inc", "dec"}) {
            String message = refusal("    var s: i16\n    " + word + " s\n");
            Assert.assertTrue(message.contains("CF"), message);
            Assert.assertTrue(message.contains("docs/ir.md"), message);
        }
    }

    private static void refusesImplicitRegisters() {
        // One operand, and ax and dx read and written behind the writer's back.
        Assert.assertTrue(refusal("    var s: i16\n    mul s\n").contains("dx"),
                refusal("    var s: i16\n    mul s\n"));
        Assert.assertTrue(refusal("    var s: i16\n    idiv s\n").contains("ax"),
                refusal("    var s: i16\n    idiv s\n"));
        Assert.assertTrue(refusal("    cwd\n").contains("ax"),
                refusal("    cwd\n"));
    }

    private static void refusesRegisters() {
        // A variable may be called ax, so accepting this would quietly mean a variable
        // rather than the register that was written.
        Assert.assertTrue(refusal("    var s: i16\n    mov ax, 1\n").contains("is a register"),
                refusal("    var s: i16\n    mov ax, 1\n"));
        Assert.assertTrue(refusal("    var s: i16\n    add s, bx\n").contains("is a register"),
                refusal("    var s: i16\n    add s, bx\n"));
    }

    private static void refusesTrees() {
        String message = refusal("    var s: i16\n    add s, 1 + 1\n");
        Assert.assertTrue(message.contains("structure"), message);
        Assert.assertTrue(message.contains("expr"), message);
    }

    private static void refusesWrongOperandCount() {
        Assert.assertTrue(refusal("    var s: i16\n    add s\n").contains("takes 2 operands"),
                refusal("    var s: i16\n    add s\n"));
        Assert.assertTrue(refusal("    var s: i16\n    neg s, s\n").contains("takes 1 operand"),
                refusal("    var s: i16\n    neg s, s\n"));
        // And the complaint says how the operation is written instead.
        Assert.assertTrue(refusal("    var s: i16\n    add s\n").contains("eval"),
                refusal("    var s: i16\n    add s\n"));
    }

    private static void refusesLiteralDestination() {
        String message = refusal("    var s: i16\n    add 1, s\n");
        Assert.assertTrue(message.contains("where the result goes"), message);
        Assert.assertTrue(refusal("    var s: i16\n    mov 1, s\n").contains("'mov'"),
                refusal("    var s: i16\n    mov 1, s\n"));
    }

    private static void refusesUnknownWord() {
        String message = refusal("    var s: i16\n    frobnicate s, 1\n");
        Assert.assertTrue(message.contains("does not begin with"), message);
        Assert.assertTrue(message.contains("inline block"), message);
    }
}
