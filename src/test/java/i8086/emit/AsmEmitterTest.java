package i8086.emit;

import i8086.CompileError;
import i8086.ir.IrParser;
import i8086.ir.Module;
import i8086.isel.InstructionSelector;
import i8086.isel.Selection;
import i8086.regalloc.RegisterAllocator;
import i8086.target.Target;
import i8086.target.Targets;
import i8086.testing.Assert;
import i8086.testing.Suite;

/**
 * Tests for the assembly that comes out.
 *
 * <p>The expected text is written out in full rather than built from pieces,
 * because the point of the test is what a person will read in the output file.
 */
public final class AsmEmitterTest {

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

    private static final String HELLO_ASM =
            "org 0x100\n"
                    + "\n"
                    + "main:\n"
                    + "    mov ah, 9\n"
                    + "    mov dx, offset msg\n"
                    + "    int 0x21\n"
                    + "    ret\n"
                    + "\n"
                    + "msg: db \"Hello, world!$\"\n";

    private AsmEmitterTest() {
    }

    public static void register(Suite suite) {
        suite.add("Asm emitter writes the whole program", AsmEmitterTest::writesWholeProgram);
        suite.add("Asm emitter writes arithmetic", AsmEmitterTest::writesArithmetic);
        suite.add("Asm emitter refuses a branch it cannot select yet",
                AsmEmitterTest::refusesBranch);
        suite.add("Asm emitter drops the target and the entry point",
                AsmEmitterTest::dropsModuleMetadata);
        suite.add("Asm emitter flattens an inline block", AsmEmitterTest::flattensInlineBlock);
        suite.add("Asm emitter keeps items in source order", AsmEmitterTest::keepsSourceOrder);
        suite.add("Asm emitter spells numbers the same way the IR printer does",
                AsmEmitterTest::spellsNumbersTheSameWay);
        suite.add("Asm emitter is deterministic", AsmEmitterTest::isDeterministic);
    }

    private static String emit(String source) {
        Module module = IrParser.parse("test.ir", source);
        Target target = Targets.byName(module.target());
        Selection selected = InstructionSelector.select(module, target);
        return AsmEmitter.emit(module, RegisterAllocator.allocate(selected, target));
    }

    private static void writesWholeProgram() {
        Assert.assertEquals(HELLO_ASM, emit(HELLO));
    }

    private static void dropsModuleMetadata() {
        String assembly = emit(HELLO);
        Assert.assertFalse(assembly.contains("target"), "no target directive");
        Assert.assertFalse(assembly.contains("entry"), "no entry directive");
        Assert.assertFalse(assembly.contains("clobbers"), "no clobber list");
        Assert.assertTrue(assembly.startsWith("org 0x100\n"), "the origin is kept");
    }

    private static void flattensInlineBlock() {
        String assembly = emit(HELLO);
        Assert.assertFalse(assembly.contains("asm"), "the block wrapper is gone");
        Assert.assertTrue(assembly.contains("    mov ah, 9\n"), "its instructions remain");
    }

    private static void keepsSourceOrder() {
        String assembly = emit("target 8086\norg 0\nentry a\n"
                + "a:\n"
                + "    ret\n"
                + "one: db 1\n"
                + "two: db 2\n");
        int first = assembly.indexOf("one:");
        int second = assembly.indexOf("two:");
        Assert.assertTrue(first >= 0 && second > first, "data keeps the order it was written in");
    }

    private static void spellsNumbersTheSameWay() {
        String assembly = emit("target 8086\norg 0\nentry a\n"
                + "a:\n"
                + "    asm clobbers() {\n"
                + "        mov ah, 9\n"
                + "        mov cx, 0x100\n"
                + "    }\n"
                + "bytes: db 9, 0x0A, 0xFF\n");
        Assert.assertTrue(assembly.contains("mov ah, 9\n"), "one digit stays decimal");
        Assert.assertTrue(assembly.contains("mov cx, 0x100\n"), "wider values are hexadecimal");
        Assert.assertTrue(assembly.contains("db 9, 0xa, 0xff\n"), "data too");
    }

    private static void isDeterministic() {
        Assert.assertEquals(emit(HELLO), emit(HELLO));
    }

    /**
     * The whole path: instructions chosen, registers assigned, text written.
     *
     * <p>{@code add ax, 1} and not {@code inc ax}, because the operation came from
     * {@code eval}: its flags can be read, and {@code inc} does not touch the
     * carry. The variable {@code x} lives in {@code ax} and the temporary the
     * multiply needs in {@code cx}.
     */
    private static void writesArithmetic() {
        String assembly = emit("target 8086\norg 0x100\nentry main\n\nmain:\n"
                + "    var x: i16\n"
                + "    var y: i16\n"
                + "    var t: i16\n"
                + "    x = 1\n"
                + "    x = eval(x + 1)\n"
                + "    t = expr(x * 4)\n"
                + "    y = eval(x + t)\n"
                + "    ret\n");
        Assert.assertEquals("org 0x100\n"
                + "\n"
                + "main:\n"
                + "    mov ax, 1\n"
                + "    add ax, 1\n"
                + "    mov cx, ax\n"
                + "    shl cx, 1\n"
                + "    shl cx, 1\n"
                + "    mov dx, ax\n"
                + "    add dx, cx\n"
                + "    ret\n", assembly);
    }

    /**
     * A comparison is not selectable yet, so the whole path refuses it — and the
     * refusal comes from the selector, which is where the missing work is.
     */
    private static void refusesBranch() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> emit("target 8086\norg 0\nentry a\na:\n    var x: u16\n    jmp a\n"));
        Assert.assertEquals("test.ir:6:5", refused.position().toString());
        Assert.assertTrue(refused.getMessage().contains("instruction selection cannot emit"),
                "the refusal says which stage is missing: " + refused.getMessage());
    }
}
