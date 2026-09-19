package i8086.ir;

import i8086.CompileError;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.asm.Size;
import i8086.testing.Assert;
import i8086.testing.Suite;

/**
 * Tests for reading the IR and writing it back.
 *
 * <p>A module written canonically must print back byte for byte, which is what
 * shows the parser and the printer agree; anything else must at least reach a
 * fixed point, since a printed module has to parse to the same module it came
 * from ({@code AGENTS.md}, invariant 5).
 */
public final class IrParserTest {

    private static final String HELLO =
            "target 8086\n"
                    + "org 0x100\n"
                    + "entry main\n"
                    + "\n"
                    + "main:\n"
                    + "    asm clobbers(ax, dx, flags) {\n"
                    + "        mov ah, 9\n"
                    + "        mov dx, offset msg\n"
                    + "        int 0x21\n"
                    + "    }\n"
                    + "    ret\n"
                    + "\n"
                    + "msg: db \"Hello, world!$\"\n";

    private IrParserTest() {
    }

    public static void register(Suite suite) {
        suite.add("Ir parser reads the module header", IrParserTest::readsHeader);
        suite.add("Ir parser reads an inline assembly block", IrParserTest::readsInlineAsm);
        suite.add("Ir parser reads data definitions", IrParserTest::readsData);
        suite.add("Ir parser reads memory operands", IrParserTest::readsMemoryOperands);
        suite.add("Ir parser reads variable declarations and assignments",
                IrParserTest::readsVariablesAndAssignments);
        suite.add("Ir printer round-trips variables and assignments",
                IrParserTest::roundTripsVariablesAndAssignments);
        suite.add("Ir printer reproduces canonical input exactly", IrParserTest::reproducesCanonical);
        suite.add("Ir printer reaches a fixed point on loose input", IrParserTest::reachesFixedPoint);
        suite.add("Ir printer is deterministic", IrParserTest::printsDeterministically);
        suite.add("Ir parser refuses an unknown target", IrParserTest::refusesUnknownTarget);
        suite.add("Ir parser refuses a missing header word", IrParserTest::refusesMissingHeader);
        suite.add("Ir parser refuses a repeated header word", IrParserTest::refusesRepeatedHeader);
        suite.add("Ir parser refuses an origin beyond a segment", IrParserTest::refusesBigOrigin);
        suite.add("Ir parser refuses a label before an instruction", IrParserTest::refusesLabelBeforeCode);
        suite.add("Ir parser refuses a value too wide for the data", IrParserTest::refusesWideData);
        suite.add("Ir parser refuses a string outside db", IrParserTest::refusesStringOutsideDb);
        suite.add("Ir parser refuses an unclosed inline block", IrParserTest::refusesUnclosedBlock);
        suite.add("Ir parser refuses a register clobbered twice", IrParserTest::refusesDuplicateClobber);
        suite.add("Ir parser refuses a size prefix without a memory operand",
                IrParserTest::refusesSizeWithoutMemory);
        suite.add("Ir parser refuses an empty bracket", IrParserTest::refusesEmptyBracket);
        suite.add("Ir parser refuses an unknown type", IrParserTest::refusesUnknownType);
        suite.add("Ir parser refuses a declaration without a colon", IrParserTest::refusesBareVar);
        suite.add("Ir parser refuses two names in an address", IrParserTest::refusesTwoNamesInAddress);
        suite.add("Ir parser refuses a value that is not a value", IrParserTest::refusesBadValue);
        suite.add("Ir parser refuses a word of the syntax as a name",
                IrParserTest::refusesKeywordAsName);
        suite.add("Ir parser refuses an unterminated statement", IrParserTest::refusesTrailingToken);
        suite.add("Ir parser names the constructs it does not implement yet",
                IrParserTest::namesUnimplemented);
    }

    private static Module parse(String source) {
        return IrParser.parse("test.ir", source);
    }

    private static void readsHeader() {
        Module module = parse(HELLO);
        Assert.assertEquals("8086", module.target());
        Assert.assertEquals(0x100L, module.origin());
        Assert.assertEquals("main", module.entry());
        Assert.assertEquals(4L, module.items().size());
    }

    private static void readsInlineAsm() {
        Module module = parse(HELLO);
        Item.InlineAsm block = (Item.InlineAsm) module.items().get(1);
        Assert.assertEquals(3L, block.clobbers().size());
        Assert.assertEquals("ax", block.clobbers().get(0));
        Assert.assertEquals("dx", block.clobbers().get(1));
        Assert.assertEquals("flags", block.clobbers().get(2));
        Assert.assertEquals(3L, block.body().size());
        Assert.assertEquals("mov", block.body().get(0).mnemonic());
        Assert.assertEquals("int", block.body().get(2).mnemonic());
        Assert.assertTrue(block.body().get(1).operands().get(1) instanceof Operand.Offset,
                "the second operand of the second instruction is 'offset msg'");
    }

    private static void readsData() {
        Module module = parse("target 8086\norg 0\nentry start\n"
                + "start:\n    ret\n"
                + "text: db \"ab\"\n"
                + "table: dw 0x1234, 0x5678\n"
                + "wide: dd 0x12345\n"
                + "db 0x55\n");
        Assert.assertEquals(6L, module.items().size());

        Item.Data text = (Item.Data) module.items().get(2);
        Assert.assertEquals("text", text.label());
        Assert.assertEquals(Size.BYTE, text.elementSize());
        Assert.assertEquals("ab", text.atoms().get(0).text());

        Item.Data table = (Item.Data) module.items().get(3);
        Assert.assertEquals(2L, table.atoms().size());
        Assert.assertEquals(0x1234L, table.atoms().get(0).number());
        Assert.assertEquals(0x5678L, table.atoms().get(1).number());

        Item.Data bare = (Item.Data) module.items().get(5);
        Assert.assertEquals(null, bare.label());
        Assert.assertEquals(0x55L, bare.atoms().get(0).number());
    }

    private static void readsMemoryOperands() {
        Module module = parse("target 8086\norg 0\nentry start\n"
                + "start:\n"
                + "    asm clobbers() {\n"
                + "        mov ax, [msg]\n"
                + "        mov word [bp-2], 1\n"
                + "        mov al, es:[bx+si+0x10]\n"
                + "        mov al, byte [bx]\n"
                + "    }\n");
        Item.InlineAsm block = (Item.InlineAsm) module.items().get(1);

        Operand.Memory direct = (Operand.Memory) block.body().get(0).operands().get(1);
        Assert.assertNull(direct.size(), "'[msg]' states no size, and needs none");
        Assert.assertNull(direct.segment(), "nothing overrides the segment");
        Assert.assertEquals("msg", direct.atoms().get(0).name());

        Operand.Memory sized = (Operand.Memory) block.body().get(1).operands().get(0);
        Assert.assertEquals(Size.WORD, sized.size());
        Assert.assertEquals("bp", sized.atoms().get(0).name());
        Assert.assertTrue(sized.atoms().get(1).isSubtracted(), "the displacement is subtracted");
        Assert.assertEquals(2L, sized.atoms().get(1).number());

        Operand.Memory overridden = (Operand.Memory) block.body().get(2).operands().get(1);
        Assert.assertEquals("es", overridden.segment());
        Assert.assertEquals(3L, overridden.atoms().size());

        Assert.assertEquals(4L, block.body().size());
    }

    private static void readsVariablesAndAssignments() {
        Module module = parse("target 8086\norg 0\nentry main\n"
                + "main:\n"
                + "    var count: u16\n"
                + "    var p: u32\n"
                + "    count = 0\n"
                + "    p = msg\n"
                + "    count = word [p + 2]\n"
                + "    byte [p - 1] = 1\n"
                + "    es:[0x1234] = count\n");

        Item.Var count = (Item.Var) module.items().get(1);
        Assert.assertEquals("count", count.name());
        Assert.assertEquals(Type.U16, count.type());
        Assert.assertEquals(Type.U32, ((Item.Var) module.items().get(2)).type());

        Item.Assign literal = (Item.Assign) module.items().get(3);
        Assert.assertEquals("count", ((Place.Name) literal.place()).name());
        Assert.assertEquals(0L, ((Value.Number) literal.value()).value());

        Item.Assign address = (Item.Assign) module.items().get(4);
        Assert.assertEquals("msg", ((Value.Name) address.value()).name());

        MemoryOperand load = ((Value.Memory) ((Item.Assign) module.items().get(5)).value())
                .operand();
        Assert.assertEquals(Size.WORD, load.size());
        Assert.assertEquals("p", load.base());
        Assert.assertEquals(2L, load.displacement());

        MemoryOperand store = ((Place.Memory) ((Item.Assign) module.items().get(6)).place())
                .operand();
        Assert.assertEquals(Size.BYTE, store.size());
        Assert.assertEquals(-1L, store.displacement());

        MemoryOperand overridden = ((Place.Memory) ((Item.Assign) module.items().get(7)).place())
                .operand();
        Assert.assertEquals("es", overridden.segment());
        Assert.assertNull(overridden.base(), "a bare displacement names no base");
        Assert.assertEquals(0x1234L, overridden.displacement());
    }

    private static void roundTripsVariablesAndAssignments() {
        String program = "target 8086\n"
                + "org 0x100\n"
                + "entry main\n"
                + "\n"
                + "main:\n"
                + "    var count: u16\n"
                + "    var p: u16\n"
                + "    p = msg\n"
                + "    count = word [p]\n"
                + "    word [p + 2] = count\n"
                + "    byte [p] = 1\n"
                + "    ret\n"
                + "\n"
                + "msg: dw 0x1234\n";
        Assert.assertEquals(program, IrPrinter.print(parse(program)));
    }

    private static void reproducesCanonical() {
        Assert.assertEquals(HELLO, IrPrinter.print(parse(HELLO)));
    }

    private static void reachesFixedPoint() {
        String loose = "; a comment\nTARGET 8086\nORG 100\nENTRY Start\n"
                + "Start:\n"
                + "\n"
                + "    asm clobbers(AX, DX, Flags) {\n"
                + "        mov AH, 9\n"
                + "    }\n"
                + "    ret\n"
                + "\n"
                + "msg: db \"x\"\n";
        String once = IrPrinter.print(parse(loose));
        String twice = IrPrinter.print(parse(once));
        Assert.assertEquals(once, twice);
        Assert.assertTrue(once.contains("    asm clobbers(ax, dx, flags) {\n"),
                "the fixed point is canonical: lower-case, one instruction per line");
    }

    private static void printsDeterministically() {
        Assert.assertEquals(IrPrinter.print(parse(HELLO)), IrPrinter.print(parse(HELLO)));
    }

    private static void refusesUnknownTarget() {
        Assert.assertRefused("test.ir:1:8",
                () -> parse("target 6502\norg 0\nentry a\n"));
    }

    private static void refusesMissingHeader() {
        Assert.assertRefused("test.ir:1:1",
                () -> parse("org 0\nentry a\n"));
    }

    private static void refusesRepeatedHeader() {
        Assert.assertRefused("test.ir:3:1",
                () -> parse("target 8086\norg 0\norg 0\nentry a\n"));
    }

    private static void refusesBigOrigin() {
        Assert.assertRefused("test.ir:2:5",
                () -> parse("target 8086\norg 0x10000\nentry a\n"));
    }

    private static void refusesLabelBeforeCode() {
        Assert.assertRefused("test.ir:5:4",
                () -> parse("target 8086\norg 0\nentry a\n\na: ret\n"));
    }

    private static void refusesWideData() {
        Assert.assertRefused("test.ir:4:4",
                () -> parse("target 8086\norg 0\nentry a\ndb 0x100\n"));
    }

    private static void refusesStringOutsideDb() {
        Assert.assertRefused("test.ir:4:4",
                () -> parse("target 8086\norg 0\nentry a\ndw \"ab\"\n"));
    }

    private static void refusesUnclosedBlock() {
        Assert.assertRefused("test.ir:4:5",
                () -> parse("target 8086\norg 0\nentry a\n    asm clobbers(ax) {\n"));
    }

    private static void refusesDuplicateClobber() {
        Assert.assertRefused("test.ir:4:22",
                () -> parse("target 8086\norg 0\nentry a\n    asm clobbers(ax, ax) {\n    }\n"));
    }

    private static void refusesSizeWithoutMemory() {
        Assert.assertRefused("test.ir:5:17",
                () -> parse("target 8086\norg 0\nentry a\n    asm clobbers() {\n        mov ax, byte 1\n    }\n"));
    }

    private static void refusesEmptyBracket() {
        Assert.assertRefused("test.ir:5:18",
                () -> parse("target 8086\norg 0\nentry a\n    asm clobbers() {\n        mov ax, []\n    }\n"));
    }

    private static void refusesUnknownType() {
        Assert.assertRefused("test.ir:4:12",
                () -> parse("target 8086\norg 0\nentry a\n    var x: u64\n"));
    }

    private static void refusesBareVar() {
        Assert.assertRefused("test.ir:4:11",
                () -> parse("target 8086\norg 0\nentry a\n    var x u16\n"));
    }

    private static void refusesTwoNamesInAddress() {
        Assert.assertRefused("test.ir:5:14",
                () -> parse("target 8086\norg 0\nentry a\n    var x: u16\n    x = [p + q]\n"));
    }

    private static void refusesBadValue() {
        Assert.assertRefused("test.ir:5:9",
                () -> parse("target 8086\norg 0\nentry a\n    var x: u16\n    x = +\n"));
    }

    private static void refusesKeywordAsName() {
        Assert.assertRefused("test.ir:4:9",
                () -> parse("target 8086\norg 0\nentry a\n    var byte: u16\n"));
    }

    private static void refusesTrailingToken() {
        Assert.assertRefused("test.ir:4:5",
                () -> parse("target 8086\norg 0\nentry a\nret 1\n"));
    }

    private static void namesUnimplemented() {
        CompileError comparison = Assert.assertRefused("test.ir:4:1",
                () -> parse("target 8086\norg 0\nentry a\ncmp x, y\n"));
        Assert.assertTrue(comparison.getMessage().startsWith("not implemented yet:"),
                "a comparison says so: " + comparison.getMessage());

        CompileError sugar = Assert.assertRefused("test.ir:4:1",
                () -> parse("target 8086\norg 0\nentry a\n.if 1\n"));
        Assert.assertTrue(sugar.getMessage().contains("docs/ir.md"),
                "and points at the section that specifies it: " + sugar.getMessage());
    }
}
