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
                    + "        mov dx, msg\n"
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
                    + "    mov dx, msg\n"
                    + "    int 0x21\n"
                    + "    ret\n"
                    + "\n"
                    + "msg: db \"Hello, world!$\"\n";

    private AsmEmitterTest() {
    }

    public static void register(Suite suite) {
        suite.add("Asm emitter writes the whole program", AsmEmitterTest::writesWholeProgram);
        suite.add("Asm emitter writes a far jump", AsmEmitterTest::writesAFarJump);
        suite.add("Asm emitter writes arithmetic", AsmEmitterTest::writesArithmetic);
        suite.add("Asm emitter writes a loop",
                AsmEmitterTest::writesALoop);
        suite.add("Asm emitter drops the target and the entry point",
                AsmEmitterTest::dropsModuleMetadata);
        suite.add("Asm emitter flattens an inline block", AsmEmitterTest::flattensInlineBlock);
        suite.add("Asm emitter keeps items in source order", AsmEmitterTest::keepsSourceOrder);
        suite.add("Asm emitter spells numbers the same way the IR printer does",
                AsmEmitterTest::spellsNumbersTheSameWay);
        suite.add("Asm emitter is deterministic", AsmEmitterTest::isDeterministic);
        suite.add("Asm emitter writes the dialect NASM reads",
                AsmEmitterTest::writesNasmDialect);
        suite.add("The IR printer keeps our own dialect", AsmEmitterTest::keepsOurDialect);
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
     * The four substitutions, each with a reason rather than a preference
     * ({@link i8086.asm.Dialect}). Nothing else about the text differs, which is the
     * point of having chosen a NASM-family syntax in the first place.
     */
    private static void writesNasmDialect() {
        // A label used as an address: NASM has no 'offset'.
        String addressing = emit("target 8086\norg 0x100\nentry main\n\nmain:\n"
                + "    var p: u16\n"
                + "    asm clobbers(ax, bx) {\n        mov bx, offset msg\n"
                + "        mov ax, [bx]\n    }\n"
                + "    p = ax\n    word [0x40] = p\n    ret\n\nmsg: dw 1\n");
        Assert.assertTrue(addressing.contains("    mov bx, msg\n"), addressing);
        Assert.assertFalse(addressing.contains("offset "), addressing);

        // A segment override goes inside the brackets, and 'pad' is 'times'.
        String boot = emit("target 8086\norg 0x7c00\nentry main\n\nmain:\n"
                + "    asm clobbers(ax, bx, es) {\n        mov ax, 0xB800\n        mov es, ax\n"
                + "        mov byte es:[bx], 0x41\n    }\n"
                + "    pad 32, 0x90\n    pad to 510\n    dw 0xAA55\n    ret\n");
        Assert.assertTrue(boot.contains("mov byte [es:bx], 0x41\n"), boot);
        Assert.assertTrue(boot.contains("times 0x20 db 0x90\n"), boot);
        Assert.assertTrue(boot.contains("times 0x1fe-($-$$) db 0\n"), boot);
        Assert.assertFalse(boot.contains("pad "), boot);
    }

    /**
     * And the other direction, which is why the dialect is a parameter rather than a
     * change to our own grammar: an inline block prints back the way it was written,
     * so an IR module round-trips in the language its author used.
     */
    private static void keepsOurDialect() {
        String source = "target 8086\norg 0x100\nentry main\n\nmain:\n"
                + "    asm clobbers(ax, bx) {\n        mov bx, offset msg\n    }\n"
                + "    pad 32\n    ret\n\nmsg: dw 1\n";
        Module module = IrParser.parse("test.ir", source);
        String printed = i8086.ir.IrPrinter.print(module);
        Assert.assertTrue(printed.contains("        mov bx, offset msg\n"), printed);
        Assert.assertTrue(printed.contains("pad 0x20\n"), printed);
    }

    /**
     * A far jump, which is how a boot loader hands control to a kernel: two numbers
     * and a colon, in both dialects, so there is nothing to translate.
     */
    private static void writesAFarJump() {
        String source = "target 8086\norg 0x7c00\nentry main\n\nmain:\n"
                + "    asm clobbers(ax, dx, si, flags) {\n        mov si, offset text\n"
                + "        mov ah, 0x0E\n        lodsb\n        int 0x10\n"
                + "        jmp 0x0000:0x7E00\n    }\n"
                + "    ret\ntext: db \"K\"\n";
        String assembly = emit(source);
        Assert.assertTrue(assembly.contains("    jmp 0:0x7e00\n"), assembly);
        Assert.assertTrue(assembly.contains("    mov si, text\n"), assembly);
        // And the IR printer writes the same operand, so the block round-trips.
        String printed = i8086.ir.IrPrinter.print(IrParser.parse("test.ir", source));
        Assert.assertTrue(printed.contains("        jmp 0:0x7e00\n"), printed);
        Assert.assertTrue(printed.contains("        mov si, offset text\n"), printed);
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
     * A loop, from the sugar to the text, with nothing in between but the pieces
     * that were already there. The two variables live across a label, so they get a
     * register each and keep it.
     */
    private static void writesALoop() {
        String assembly = emit("target 8086\norg 0x100\nentry main\n\nmain:\n"
                + "    var i: u16\n"
                + "    var n: u16\n"
                + "    i = 0\n"
                + "    n = 3\n"
                + "    .while i < n\n"
                + "        i = eval(i + 1)\n"
                + "    .endw\n"
                + "    ret\n");
        Assert.assertEquals("org 0x100\n"
                + "\n"
                + "main:\n"
                + "    mov ax, 0\n"
                + "    mov cx, 3\n"
                + "    jmp ..@lbl1\n"
                + "\n"
                + "..@lbl0:\n"
                + "    add ax, 1\n"
                + "\n"
                + "..@lbl1:\n"
                + "    cmp ax, cx\n"
                + "    jc ..@lbl0\n"
                + "    ret\n", assembly);
    }
}
