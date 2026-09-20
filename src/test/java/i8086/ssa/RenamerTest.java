package i8086.ssa;

import i8086.ir.IrParser;
import i8086.ir.IrPrinter;
import i8086.ir.IrVerifier;
import i8086.ir.Item;
import i8086.ir.MemoryOperand;
import i8086.ir.Module;
import i8086.ir.Names;
import i8086.ir.Value;
import i8086.target.Target;
import i8086.target.Targets;
import i8086.testing.Assert;
import i8086.testing.Suite;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Renaming a name to itself changes nothing.
 *
 * <p>Every pass that touches a form <b>rebuilds</b> the items it keeps: an item is immutable, so
 * renaming one means constructing another, and the construction has to carry over everything the
 * pass is not changing. Twice in this compiler's history it did not — a memory operand came back
 * without its {@code volatile} mark, which let a load the program needed be deleted
 * ({@code AGENTS.md}, invariant 3), and a machine statement came back without its prefix, which
 * turned {@code rep movsb} into {@code movsb} and a copy loop into one byte of one. Both were found
 * by a person reading the output, and the same class is what this asks of the whole compiler
 * instead: <b>rename every name to itself, and the program has to print exactly as it did.</b>
 *
 * <p>Printing is what makes it a check rather than a second copy of the fields: what the printer
 * writes is what a field is for, so a field that no longer survives a rebuild shows up as text that
 * changed. A field the printer does not write is a field nothing downstream can see either.
 *
 * <p>What it cannot check is a rebuild that changes something on purpose: a pass may drop a field
 * it means to drop, and this would stay quiet. That is what the pass's own tests are for, and it is
 * the difference between "the rebuild is faithful" and "the transformation is right".
 */
public final class RenamerTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** The shapes as well as the shipped examples: one per item kind the renamer rebuilds. */
    private static final List<String> SHAPES = Arrays.asList(
            "    var x: u16\n    var y: u16\n    x = word [0x40]\n    y = x\n    [0x42] = y\n    ret\n",
            "    var c: u8\n    var w: u16\n    c = byte [0x40]\n    w = movzx c\n    [0x42] = w\n"
                    + "    ret\n",
            // A volatile access, which is the mark that was lost: it has a base register, which is
            // the shape the rebuild went through, and a fixed address, which is the shape it did
            // not.
            "    var p: u16\n    var v: u16\n    p = 0x7E00\n    v = volatile word [p]\n"
                    + "    volatile word [0x40] = v\n    ret\n",
            // Every machine statement: an immediate, a prefix, a clause, and a clobber list.
            "    var d: u8\n    var src: u16\n    var dst: u16\n"
                    + "    movreg d, dl\n"
                    + "    int 0x13 clobbers(ax, bx, flags) with ah = 0x42, dl = d\n"
                    + "    cli\n    cld\n    std\n    hlt\n"
                    + "    src = 0x7E00\n    dst = 0x8000\n"
                    + "    rep movsb with cx = 0x200, si = src, di = dst\n"
                    + "    movreg ds, 0\n    ret\n",
            // An inline block, which carries a list, a body and a clause.
            "    var c: u8\n    c = byte [0x40]\n"
                    + "    asm clobbers(ax, dx) with al = c {\n        mov ah, 9\n    }\n    ret\n",
            // Control flow, a far jump and the comparisons that make branches.
            "    var x: u16\n    x = word [0x40]\n    cmp x, 1\n    jz $done\n    test x, x\n"
                    + "    jnz $done\n    jmp 0x0000:0x7E00\n$done:\n    ret\n",
            // Data, padding and labels, which are items too.
            "    var x: u16\n    x = word [0x40]\n$msg: db 1, 2, 3\n$tbl: dw $msg, 0\n"
                    + "    pad to 0x40\n    [0x42] = x\n    ret\n",
            // Every conversion and the flags forms.
            "    var a: u8\n    var w: u16\n    a = byte [0x40]\n    w = movzx a\n"
                    + "    w = eval(w + 1)\n    w = expr(w * 3)\n    a = byte w\n    [0x42] = a\n"
                    + "    ret\n");

    private RenamerTest() {
    }

    public static void register(Suite suite) {
        suite.add("Renaming a name to itself changes nothing", RenamerTest::renamesToItself);
        suite.add("The rename check notices a field that was left behind",
                RenamerTest::noticesAFieldLeftBehind);
    }

    private static void renamesToItself() {
        int checked = 0;
        for (String program : SHAPES) {
            checked += check("t.ir", HEAD + program);
        }
        for (String path : shipped()) {
            checked += check(path, read(path));
        }
        Assert.assertTrue(checked > 0, "there were items to rename");
    }

    /**
     * The check has teeth, and this is the field that was lost: a rebuild that forgets the volatile
     * mark prints differently, which is exactly what {@link #renamesToItself} compares.
     */
    private static void noticesAFieldLeftBehind() {
        String program = HEAD + "    var p: u16\n    var v: u16\n    p = 0x7E00\n"
                + "    v = volatile word [p]\n    [0x40] = v\n    ret\n";
        Module module = parse("t.ir", program);
        String printed = IrPrinter.print(module);
        Assert.assertTrue(printed.contains("volatile"), printed);
        // The same text with the mark taken off the item, which is what a lost field looks like.
        List<Item> stripped = new ArrayList<Item>();
        for (Item item : module.items()) {
            stripped.add(strip(item, module));
        }
        Module without = new Module(module.target(), module.origin(), module.entry(),
                module.entryPosition(), stripped);
        Assert.assertFalse(IrPrinter.print(without).contains("volatile"),
                "a load with its mark taken off prints without it");
    }

    /**
     * The item with the volatile mark off, for the test above and nothing else: this is the
     * construction that lost it, and the one that has to keep printing the same text once the mark
     * is put back.
     */
    private static Item strip(Item item, Module module) {
        if (!(item instanceof Item.Assign)) {
            return item;
        }
        Item.Assign assign = (Item.Assign) item;
        if (!(assign.value() instanceof Value.Memory)) {
            return item;
        }
        Value.Memory load = (Value.Memory) assign.value();
        MemoryOperand operand = load.operand();
        MemoryOperand plain = new MemoryOperand(operand.position(), operand.size(),
                operand.segment(), operand.base(), operand.displacement());
        return new Item.Assign(assign.position(), assign.place(),
                new Value.Memory(load.position(), plain));
    }

    // --- the check ---------------------------------------------------------

    private static final String HEAD = "target 8086\norg 0x100\nentry $main\n\n$main:\n";

    /**
     * Renames every name in a program to itself, and answers with how many items it renamed.
     *
     * <p>Comparing the printed text is the whole check. A name that is a variable keeps its name; a
     * name that is a label stays what it is, because a label is an address and not a value
     * ({@code docs/ir.md} §3.1) — so the identity function answers "this name, again" for a variable
     * and "not a value" for everything else, which is the same answer the renamer gets when it has
     * nothing to do.
     */
    private static int check(String name, String text) {
        Module module = parse(name, text);
        final Names names = Names.of(module);
        Renamer.Versions identity = new Renamer.Versions() {
            @Override
            public String of(String version) {
                return names.isVariable(version) ? version : null;
            }
        };
        String before = IrPrinter.print(module);
        List<Item> renamed = new ArrayList<Item>();
        for (Item item : module.items()) {
            renamed.add(Renamer.rename(item, identity));
        }
        Module after = new Module(module.target(), module.origin(), module.entry(),
                module.entryPosition(), renamed);
        Assert.assertEquals(before, IrPrinter.print(after));
        return renamed.size();
    }

    private static Module parse(String name, String text) {
        Target target = Targets.byName("8086");
        Module module = IrParser.parse(name, text);
        IrVerifier.verify(module, target);
        return module;
    }

    private static List<String> shipped() {
        List<String> paths = new ArrayList<String>();
        File directory = new File("examples");
        String[] names = directory.list();
        if (names != null) {
            Arrays.sort(names);
            for (String name : names) {
                if (name.endsWith(".ir")) {
                    paths.add("examples/" + name);
                }
            }
        }
        return paths;
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(new File(path).toPath()), UTF_8);
        } catch (IOException failure) {
            Assert.fail("cannot read " + path + ": " + failure.getMessage());
            return null; // unreachable: fail always throws
        }
    }
}
