package i8086.ssa;

import i8086.ir.Item;
import i8086.ir.IrParser;
import i8086.ir.Module;
import i8086.testing.Assert;
import i8086.testing.Suite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What each kind of statement does to the flags: the three states, and who gets to say which
 * ({@code docs/ir.md} §4.2).
 *
 * <p>The three are the whole of what tells a compiler whether the flags in force after a statement
 * are the ones it computed, nothing at all, or still the ones from before it — and the last of the
 * three is the one no statement can say for itself. A statement that computes flags leaves a value
 * behind it; a statement that gives them up leaves none; everything else leaves the <em>old</em>
 * value standing, which is a different thing from making a new one, and a compiler that confused
 * them would be free to delete a comparison the program still reads ({@code MachineTest} is the
 * end-to-end half of this file, and it is where that confusion was caught).
 *
 * <p>What a machine statement does cannot be read off its clobber list, because the list only says
 * what is destroyed: {@code cli} and {@code int} both leave the flags unnamed and mean opposite
 * things. So the target answers for the mnemonic, and the list overrules it when the author knows
 * better ({@link i8086.target.Target#machineWritesFlags}).
 */
public final class EffectsTest {

    private static final String HEAD = "target 8086\norg 0x100\nentry $main\n\n$main:\n";

    private EffectsTest() {
    }

    public static void register(Suite suite) {
        suite.add("A statement leaves the flags, destroys them, or neither",
                EffectsTest::threeStates);
        suite.add("A machine statement's flags are the target's to describe",
                EffectsTest::theTargetSays);
        suite.add("No statement both makes the flags and gives them up",
                EffectsTest::oneStateEach);
    }

    /** The statements of a body, in order, without the declarations and the entry label. */
    private static List<Item> statements(String body) {
        Module module = IrParser.parse("t.ir", HEAD + body);
        List<Item> statements = new ArrayList<Item>();
        for (Item item : module.items()) {
            if (!(item instanceof Item.Label) && !(item instanceof Item.Var)) {
                statements.add(item);
            }
        }
        return statements;
    }

    /** How a statement leaves the flags, as one word: what a failure message should say. */
    private static String leaves(Item item) {
        if (Effects.writesFlags(item)) {
            return "leaves a value of its own";
        }
        if (Effects.killsFlags(item)) {
            return "leaves nothing";
        }
        return "leaves what was there";
    }

    private static List<String> leaves(String body) {
        List<String> answers = new ArrayList<String>();
        for (Item item : statements(body)) {
            answers.add(leaves(item));
        }
        return answers;
    }

    private static void threeStates() {
        // A move computes nothing, so what is in force after it is what was in force before;
        // an operation computes flags; 'expr' and a conversion give them up.
        Assert.assertEquals(Arrays.asList(
                        "leaves what was there",
                        "leaves a value of its own",
                        "leaves nothing",
                        "leaves nothing",
                        "leaves a value of its own",
                        "leaves what was there"),
                leaves("    var x: u16\n    var y: u16\n"
                        + "    y = x\n"
                        + "    y = eval(x + 1)\n"
                        + "    y = expr(x + 1)\n"
                        + "    y = movzx x\n"
                        + "    cmp x, 1\n"
                        + "    ret\n"));

        // And a branch reads them, which is a thing it does and not a state it leaves them in.
        Item branch = statements("    jc $main\n").get(0);
        Assert.assertTrue(Effects.readsFlags(branch), "a branch reads the flags");
        Assert.assertFalse(Effects.writesFlags(branch), "a branch writes nothing");
        Assert.assertFalse(Effects.killsFlags(branch), "a branch destroys nothing");
    }

    private static void theTargetSays() {
        Assert.assertEquals(Arrays.asList(
                        "leaves what was there",
                        "leaves what was there",
                        "leaves what was there",
                        "leaves what was there",
                        "leaves a value of its own",
                        "leaves nothing",
                        "leaves nothing"),
                leaves("    nop\n"
                        + "    cli\n"
                        + "    sti\n"
                        + "    hlt\n"
                        + "    int 0x10 clobbers(ax, bx, cx, dx)\n"
                        + "    int 0x10 clobbers(flags)\n"
                        + "    int 0x10\n"));

        // A block is the author's promise instead, and the same rule reads it: what the
        // list names is destroyed, and what it does not name is left standing.
        Assert.assertEquals(Arrays.asList("leaves what was there", "leaves nothing"),
                leaves("    asm clobbers(ax) {\n        nop\n    }\n"
                        + "    asm clobbers(ax, flags) {\n        nop\n    }\n"));
    }

    /**
     * The three states are one answer each, and this is the property that keeps them so: a state
     * that claimed both would let a pass read either half and mean different things by it.
     */
    private static void oneStateEach() {
        String body = "    var x: u16\n    var y: u16\n"
                + "    x = word [0x40]\n"
                + "    y = x\n"
                + "    y = eval(x + 1)\n"
                + "    y = expr(x + 1)\n"
                + "    y = movzx x\n"
                + "    cmp x, 1\n"
                + "    test x, x\n"
                + "    add x, 1\n"
                + "    jc $main\n"
                + "    jmp $main\n"
                + "    nop\n"
                + "    cli\n"
                + "    int 0x10\n"
                + "    int 0x10 clobbers(ax)\n"
                + "    iret\n"
                + "    asm clobbers(ax) {\n        nop\n    }\n"
                + "    asm clobbers(flags) {\n        nop\n    }\n"
                + "    movreg ds, 0\n"
                + "    word [0x50] = x\n"
                + "    ret\n";
        int at = 0;
        for (Item item : statements(body)) {
            at++;
            Assert.assertFalse(Effects.writesFlags(item) && Effects.killsFlags(item),
                    "statement " + at + " claims both, and one of the two answers decides whether "
                            + "the definition before it is still wanted");
        }
    }
}
