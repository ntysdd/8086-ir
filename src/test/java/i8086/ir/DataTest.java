package i8086.ir;

import i8086.CompileError;
import i8086.testing.Assert;
import i8086.testing.Suite;
import i8086.target.Targets;

/**
 * Tests for a label in a list of data: its value is its address, which is how a
 * pointer table, a vector table or a jump table is written.
 *
 * <p>The value is not known to the front end — it depends on where everything
 * lands — so this is the same kind of thing as {@code pad to} and as an operand
 * naming a label: the IR states it and the assembler resolves it. That is also why
 * the names are checked by the verifier rather than while reading: a label may be
 * defined after the data that names it.
 */
public final class DataTest {

    private static final String HEAD = "target 8086\norg 0x100\nentry $main\n\n$main:\n";

    private DataTest() {
    }

    public static void register(Suite suite) {
        suite.add("A dw list may name labels", DataTest::namesLabels);
        suite.add("A label in data may be defined later", DataTest::mayBeDefinedLater);
        suite.add("A label in data is marked when it looks like a word",
                DataTest::marksAWord);
        suite.add("A label in data goes to the assembler as an address",
                DataTest::goesToTheAssembler);
        suite.add("Data refuses a label where its address would not fit",
                DataTest::refusesNarrow);
        suite.add("Data refuses a variable", DataTest::refusesAVariable);
        suite.add("Data refuses a name that is not a label", DataTest::refusesAStranger);
        suite.add("Data refuses the offset spelling", DataTest::refusesOffset);
    }

    private static Module parse(String body) {
        return verify(IrParser.parse("test.ir", HEAD + body));
    }

    private static Module verify(Module module) {
        IrVerifier.verify(module, Targets.byName("8086"));
        return module;
    }

    private static String printed(String body) {
        return IrPrinter.print(parse(body));
    }

    private static String became(String body) {
        String text = printed(body);
        return text.substring(text.indexOf("\n$main:\n") + "\n$main:\n".length());
    }

    private static void namesLabels() {
        Assert.assertEquals("\n$tbl: dw $h1, 0x1234, $h2\n\n$h1:\n    ret\n\n$h2:\n    ret\n",
                became("tbl: dw h1, 0x1234, h2\n"
                        + "h1:\n    ret\nh2:\n    ret\n"));
        // Numbers and labels mix freely, and the round trip holds with both.
        String body = "tbl: dw h1, 0x1234, h2\nh1:\n    ret\nh2:\n    ret\n";
        String once = printed(body);
        Assert.assertEquals(once, IrPrinter.print(verify(IrParser.parse("test.ir", once))));
    }

    private static void mayBeDefinedLater() {
        // The other way round on purpose: the data comes first, so the name cannot be
        // resolved while reading the line.
        Assert.assertEquals("\n$tbl: dw $h1\n\n$h1:\n    ret\n",
                became("tbl: dw h1\nh1:\n    ret\n"));
    }

    private static void marksAWord() {
        // A label called 'shl' is a label like any other (docs/ir.md §3.1), so data
        // naming it says '$shl' — and that re-reads as the same label.
        Assert.assertEquals("\n$shl: dw 1, $shl\n",
                became("shl: dw 1, shl\n"));
    }

    private static void goesToTheAssembler() {
        String assembly = i8086.Compiler.compile("t.ir", HEAD
                + "    asm clobbers(ax, bx) {\n        mov bx, tbl\n        mov ax, [bx]\n"
                + "        jmp ax\n    }\n"
                + "    ret\n\nh1:\n    ret\nh2:\n    ret\ntbl: dw h1, h2, 0x1234\n");
        Assert.assertTrue(assembly.contains("$tbl: dw $h1, $h2, 0x1234\n"), assembly);
        Assert.assertTrue(assembly.contains("    mov bx, $tbl\n"), assembly);
    }

    private static void refusesNarrow() {
        for (String directive : new String[] {"db", "dd"}) {
            CompileError refused = refusal(directive + " h1\nh1:\n    ret\n");
            Assert.assertTrue(refused.getMessage().contains("one word wide"),
                    refused.getMessage());
        }
    }

    private static void refusesAVariable() {
        CompileError refused = refusal("var v: i16\n    tbl: dw v\n");
        Assert.assertTrue(refused.getMessage().contains("variable"), refused.getMessage());
    }

    private static void refusesAStranger() {
        CompileError refused = refusal("tbl: dw nosuch\n");
        Assert.assertTrue(refused.getMessage().contains("not a label"), refused.getMessage());
    }

    private static void refusesOffset() {
        CompileError refused = refusal("tbl: dw offset h1\nh1:\n    ret\n");
        Assert.assertTrue(refused.getMessage().contains("plainly"), refused.getMessage());
    }

    private static CompileError refusal(String body) {
        return Assert.assertThrows(CompileError.class, () -> parse(body));
    }
}
