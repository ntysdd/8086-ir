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
                    + "entry $main\n"
                    + "\n"
                    + "$main:\n"
                    + "    asm clobbers(ax, dx, flags) {\n"
                    + "        mov ah, 9\n"
                    + "        mov dx, offset $msg\n"
                    + "        int 0x21\n"
                    + "    }\n"
                    + "    ret\n"
                    + "\n"
                    + "$msg: db \"Hello, world!$\"\n";

    private IrParserTest() {
    }

    public static void register(Suite suite) {
        suite.add("Ir parser reads the module header", IrParserTest::readsHeader);
        suite.add("Ir parser reads an inline assembly block", IrParserTest::readsInlineAsm);
        suite.add("Ir parser reads a label inside a block", IrParserTest::readsBlockLabel);
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
        suite.add("Ir parser takes a word of the syntax as a name",
                IrParserTest::refusesKeywordAsName);
        suite.add("Ir parser refuses an unterminated statement", IrParserTest::refusesTrailingToken);
        suite.add("Ir parser names the constructs it does not implement yet",
                IrParserTest::namesUnimplemented);
        suite.add("Ir parser respects operator precedence", IrParserTest::readsExpressions);
        suite.add("Ir parser lets brackets change the tree", IrParserTest::bracketsChangeTheTree);
        suite.add("Ir parser reads mnemonic operators and conversions",
                IrParserTest::readsMnemonicOperatorsAndConversions);
        suite.add("Ir printer round-trips expressions", IrParserTest::roundTripsExpressions);
        suite.add("Ir parser refuses two operations in one eval",
                IrParserTest::refusesTwoOperationsInEval);
        suite.add("Ir parser refuses a tree in eval", IrParserTest::refusesTreeInEval);
        suite.add("Ir parser refuses an eval with no operation",
                IrParserTest::refusesEvalWithNoOperation);
        suite.add("Ir parser refuses nested eval and expr", IrParserTest::refusesNestedForms);
        suite.add("Ir parser refuses arithmetic without a form",
                IrParserTest::refusesArithmeticWithoutAForm);
        suite.add("Ir parser refuses a negated literal", IrParserTest::refusesNegatedLiteral);
        suite.add("Ir parser refuses converting a form", IrParserTest::refusesConversionOfAForm);
        suite.add("Ir parser reads comparisons and branches",
                IrParserTest::readsComparisonsAndBranches);
        suite.add("Ir parser normalises condition aliases",
                IrParserTest::normalisesConditionAliases);
        suite.add("Ir printer round-trips comparisons and branches",
                IrParserTest::roundTripsComparisonsAndBranches);
        suite.add("Ir parser refuses an unknown condition", IrParserTest::refusesUnknownCondition);
        suite.add("Ir parser refuses a branch without a target",
                IrParserTest::refusesBranchWithoutTarget);
        suite.add("Ir parser takes a condition word as a name",
                IrParserTest::refusesConditionAsName);
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

    /**
     * A label of a block's own, which is a line of its own where a mnemonic would be
     * ({@code docs/ir.md} §9). The colon is what says so, so nothing else has to.
     */
    private static void readsBlockLabel() {
        String block = "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + "    asm clobbers(ax, flags) {\n        mov ax, 1\n    retry:\n"
                + "        dec ax\n        jnz retry\n    }\n    ret\n";
        Item.InlineAsm parsed = (Item.InlineAsm) parse(block).items().get(1);
        Assert.assertEquals(4L, parsed.body().size());
        Assert.assertTrue(parsed.body().get(1).isLabel(), "the second line is a label");
        Assert.assertEquals("retry", parsed.body().get(1).mnemonic());
        Assert.assertEquals("jnz", parsed.body().get(3).mnemonic());
        // And it prints back at the block's own margin, colon and all.
        Assert.assertEquals("target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    asm clobbers(ax, flags) {\n        mov ax, 1\n        $retry:\n"
                        + "        dec ax\n        jnz $retry\n    }\n    ret\n",
                IrPrinter.print(parse(block)));
        // A register cannot be one: 'ax:' at the start of a line would be read as a
        // label, and a register is not a place.
        CompileError refused = Assert.assertThrows(CompileError.class, () -> parse(
                "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                        + "    asm clobbers(ax, flags) {\n    ax:\n        dec ax\n    }\n    ret\n"));
        Assert.assertTrue(refused.getMessage().contains("register"), refused.getMessage());
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
        Module module = parse("target 8086\norg 0\nentry $main\n"
                + "$main:\n"
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
                + "entry $main\n"
                + "\n"
                + "$main:\n"
                + "    var $count: u16\n"
                + "    var $p: u16\n"
                + "    $p = $msg\n"
                + "    $count = word [$p]\n"
                + "    word [$p + 2] = $count\n"
                + "    byte [$p] = 1\n"
                + "    ret\n"
                + "\n"
                + "$msg: dw 0x1234\n";
        Assert.assertEquals(program, IrPrinter.print(parse(program)));
    }

    private static void reproducesCanonical() {
        Assert.assertEquals(HELLO, IrPrinter.print(parse(HELLO)));
    }

    private static void roundTripsGeneratedLabels() {
        // A module that used the sugar prints labels the compiler made up. Printing it
        // and reading it back has to give the same module — it did not while those
        // labels began with '$' and the lexer refused '$', which broke the round trip
        // for every program with an .if or a .while in it (AGENTS.md, invariant 5).
        String withSugar = "    var n: u16\n"
                + "    .while n > 0\n"
                + "        n = eval(n - 1)\n"
                + "        .if n == 1\n"
                + "            n = 2\n"
                + "        .endif\n"
                + "    .endw\n";
        String header = "target 8086\norg 0x100\nentry $main\n\n$main:\n";
        String once = IrPrinter.print(parse(header + withSugar));
        Assert.assertTrue(once.contains("..@lbl"), once);
        Assert.assertEquals(once, IrPrinter.print(parse(once)));
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
        // A size word is a word, not a reservation: 'byte' says how wide a bracket is
        // and is a name everywhere else (docs/ir.md §3.1).
        Assert.assertEquals("target 8086\norg 0\nentry $a\n    var $byte: u16\n",
                        IrPrinter.print(parse("target 8086\norg 0\nentry a\n    var byte: u16\n")));
    }

    private static void refusesUnknownCondition() {
        Assert.assertRefused("test.ir:4:1",
                () -> parse("target 8086\norg 0\nentry a\njx a\n"));
    }

    private static void refusesBranchWithoutTarget() {
        Assert.assertRefused("test.ir:4:3",
                () -> parse("target 8086\norg 0\nentry a\njz\n"));
    }

    private static void refusesConditionAsName() {
        // A condition is a word, not a reservation: 'jc' is a branch word at the start
        // of a statement and a name everywhere else (docs/ir.md §3.1), so a variable
        // called 'jc' is accepted and printed back marked.
        Assert.assertEquals("target 8086\norg 0\nentry $a\n    var $jc: u16\n    $jc = 1\n",
                IrPrinter.print(parse("target 8086\norg 0\nentry a\n    var jc: u16\n"
                        + "    jc = 1\n")));
    }

    private static void refusesTrailingToken() {
        Assert.assertRefused("test.ir:4:5",
                () -> parse("target 8086\norg 0\nentry a\nret 1\n"));
    }

    private static void namesUnimplemented() {
        CompileError setcc = Assert.assertRefused("test.ir:4:1",
                () -> parse("target 8086\norg 0\nentry a\nsetc x\n"));
        Assert.assertTrue(setcc.getMessage().startsWith("not implemented yet:"),
                "the setcc family says so: " + setcc.getMessage());
        Assert.assertTrue(setcc.getMessage().contains("docs/ir.md"),
                "and points at the section that specifies it: " + setcc.getMessage());
    }

    private static void readsExpressions() {
        Module module = parse(program());
        Item.Assign assign = (Item.Assign) module.items().get(4);
        Expression.Apply sum =
                (Expression.Apply) ((Value.Expr) assign.value()).expression();
        Assert.assertEquals(Operator.ADD, sum.operator());
        Assert.assertEquals("a", ((Value.Name) ((Expression.Leaf) sum.left()).value()).name());

        Expression.Apply product = (Expression.Apply) sum.right();
        Assert.assertEquals(Operator.MULTIPLY, product.operator());
        Assert.assertEquals("b", ((Value.Name) ((Expression.Leaf) product.left()).value()).name());
        Assert.assertEquals("c", ((Value.Name) ((Expression.Leaf) product.right()).value()).name());
    }

    private static void bracketsChangeTheTree() {
        Module module = parse(program().replace("a + b * c", "(a + b) * c"));
        Item.Assign assign = (Item.Assign) module.items().get(4);
        Expression.Apply product =
                (Expression.Apply) ((Value.Expr) assign.value()).expression();
        Assert.assertEquals(Operator.MULTIPLY, product.operator());
        Assert.assertEquals(Operator.ADD,
                ((Expression.Apply) product.left()).operator());
    }

    private static void readsMnemonicOperatorsAndConversions() {
        Module module = parse("target 8086\norg 0\nentry $main\n"
                + "$main:\n"
                + "    var a: u16\n"
                + "    a = eval(a shl 2)\n"
                + "    a = eval(a adc a)\n"
                + "    a = eval(a idiv a)\n"
                + "    a = expr(a shl a)\n"
                + "    a = movzx a\n"
                + "    a = byte a\n");
        Assert.assertEquals(Operator.SHIFT_LEFT, operatorOf(module, 2));
        Assert.assertEquals(Operator.ADD_WITH_CARRY, operatorOf(module, 3));
        Assert.assertEquals(Operator.DIVIDE_SIGNED, operatorOf(module, 4));
        Assert.assertEquals(Operator.SHIFT_LEFT, operatorOf(module, 5));

        Value.Convert widen = (Value.Convert) ((Item.Assign) module.items().get(6)).value();
        Assert.assertEquals(Conversion.ZERO_EXTEND, widen.conversion());
        Value.Convert narrow = (Value.Convert) ((Item.Assign) module.items().get(7)).value();
        Assert.assertEquals(Conversion.LOW_BYTE, narrow.conversion());
    }

    private static Operator operatorOf(Module module, int index) {
        Value value = ((Item.Assign) module.items().get(index)).value();
        if (value instanceof Value.Eval) {
            return ((Value.Eval) value).operation().operator();
        }
        Expression expression = ((Value.Expr) value).expression();
        return ((Expression.Apply) expression).operator();
    }

    private static void roundTripsExpressions() {
        String program = "target 8086\n"
                + "org 0x100\n"
                + "entry $main\n"
                + "\n"
                + "$main:\n"
                + "    var $a: u16\n"
                + "    var $b: u16\n"
                + "    var $small: u8\n"
                + "    $a = eval($a + $b)\n"
                + "    $a = eval(~$a)\n"
                + "    $a = eval($a adc $b)\n"
                + "    $a = eval($a idiv $b)\n"
                + "    $a = eval($a + [$a])\n"
                + "    eval($a * $b)\n"
                + "    $a = expr($a + $b * $a)\n"
                + "    $a = expr(($a + $b) * $a)\n"
                + "    $a = expr($a - ($b - $a))\n"
                + "    $a = expr(~$a + $a & $a ^ $a | $a)\n"
                + "    $a = movzx byte [$a]\n"
                + "    $small = byte $a\n";
        Assert.assertEquals(program, IrPrinter.print(parse(program)));
    }

    private static void roundTripsNegation() {
        // Unary minus is a prefix like ~, so it binds tighter than everything that is
        // written between two operands, and the brackets the printer puts back are the
        // ones that keep the tree the same shape (docs/ir.md §5.5).
        String program = "target 8086\n"
                + "org 0x100\n"
                + "entry $main\n"
                + "\n"
                + "$main:\n"
                + "    var a: i16\n"
                + "    var b: i16\n"
                + "    a = eval(-a)\n"
                + "    a = expr(-a * b)\n"
                + "    a = expr(-a - b)\n"
                + "    a = expr(-(a + b))\n"
                + "    a = expr(-(-a))\n"
                + "    a = expr(-(~a))\n";
        Assert.assertEquals(program, IrPrinter.print(parse(program)));
    }

    private static void refusesTwoOperationsInEval() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> parse("target 8086\norg 0\nentry $main\n$main:\n    var a: u16\n"
                        + "    var b: u16\n    a = eval(a + b + a)\n"));
        Assert.assertEquals("test.ir:7:20", refused.position().toString());
        Assert.assertTrue(refused.getMessage().contains("exactly one operation"),
                refused.getMessage());
    }

    private static void refusesTreeInEval() {
        Assert.assertRefused("test.ir:7:20",
                () -> parse("target 8086\norg 0\nentry $main\n$main:\n    var a: u16\n"
                        + "    var b: u16\n    a = eval(a + b * a)\n"));
    }

    private static void refusesEvalWithNoOperation() {
        Assert.assertRefused("test.ir:6:15",
                () -> parse("target 8086\norg 0\nentry $main\n$main:\n    var a: u16\n"
                        + "    a = eval(a)\n"));
    }

    private static void refusesNestedForms() {
        Assert.assertRefused("test.ir:6:14",
                () -> parse("target 8086\norg 0\nentry $main\n$main:\n    var a: u16\n"
                        + "    a = eval(expr(a) + 1)\n"));
    }

    private static void refusesArithmeticWithoutAForm() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> parse("target 8086\norg 0\nentry $main\n$main:\n    var a: u16\n    a = a + 1\n"));
        Assert.assertEquals("test.ir:6:11", refused.position().toString());
        Assert.assertTrue(refused.getMessage().contains("eval(...) or expr(...)"),
                "the message says where arithmetic goes: " + refused.getMessage());
    }

    private static void refusesNegatedLiteral() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> parse("target 8086\norg 0\nentry $main\n$main:\n    var a: u16\n    a = -1\n"));
        Assert.assertEquals("test.ir:6:9", refused.position().toString());
        Assert.assertTrue(refused.getMessage().contains("0xFFFF"), refused.getMessage());
    }

    private static void refusesConversionOfAForm() {
        Assert.assertRefused("test.ir:6:15",
                () -> parse("target 8086\norg 0\nentry $main\n$main:\n    var a: u16\n"
                        + "    a = movzx eval(a + a)\n"));
    }

    private static String program() {
        return "target 8086\norg 0x100\nentry $main\n"
                + "$main:\n"
                + "    var a: u16\n"
                + "    var b: u16\n"
                + "    var c: u16\n"
                + "    a = expr(a + b * c)\n";
    }

    private static void readsComparisonsAndBranches() {
        Module module = parse("target 8086\norg 0\nentry $main\n"
                + "$main:\n"
                + "    var x: u16\n"
                + "    cmp x, 0\n"
                + "    test x, 1\n"
                + "    jmp main\n"
                + "    jz main\n");

        Item.Compare cmp = (Item.Compare) module.items().get(2);
        Assert.assertEquals(Item.Compare.Kind.CMP, cmp.kind());
        Assert.assertEquals("x", ((Value.Name) cmp.left()).name());
        Assert.assertEquals(0L, ((Value.Number) cmp.right()).value());

        Assert.assertEquals(Item.Compare.Kind.TEST,
                ((Item.Compare) module.items().get(3)).kind());
        Assert.assertEquals("main", ((Item.Jump) module.items().get(4)).target());
        Assert.assertEquals("main", ((Item.Branch) module.items().get(5)).target());
    }

    private static void normalisesConditionAliases() {
        Module module = parse("target 8086\norg 0\nentry $main\n"
                + "$main:\n"
                + "    jb main\n"
                + "    jnae main\n"
                + "    jnc main\n"
                + "    je main\n");
        Assert.assertEquals("jc", ((Item.Branch) module.items().get(1)).condition());
        Assert.assertEquals("jc", ((Item.Branch) module.items().get(2)).condition());
        Assert.assertEquals("jnc", ((Item.Branch) module.items().get(3)).condition());
        Assert.assertEquals("jz", ((Item.Branch) module.items().get(4)).condition());
    }

    private static void roundTripsComparisonsAndBranches() {
        String program = "target 8086\n"
                + "org 0x100\n"
                + "entry $main\n"
                + "\n"
                + "$main:\n"
                + "    var $x: u16\n"
                + "    cmp $x, 0\n"
                + "    jz $done\n"
                + "    jmp $main\n"
                + "\n"
                + "$done:\n"
                + "    ret\n";
        Assert.assertEquals(program, IrPrinter.print(parse(program)));
    }
}
