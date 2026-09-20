package i8086.ssa;

import i8086.ir.Item;
import i8086.ir.IrParser;
import i8086.ir.Module;
import i8086.ir.Names;
import i8086.testing.Assert;
import i8086.testing.Suite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What each kind of statement does to the flags: the three states, per flag, and who gets to say
 * which ({@code docs/ir.md} §4.2).
 *
 * <p>The three are the whole of what tells a compiler whether the flag in force after a statement is
 * the one it computed, nothing at all, or still the one from before it — and the last of those is
 * the one no statement can say for itself. A statement that computes a flag leaves a value behind
 * it; a statement that gives one up leaves none; everything else leaves the <em>old</em> value
 * standing, which is a different thing from making a new one, and a compiler that confused them
 * would be free to delete a comparison the program still reads ({@code MachineTest} is the
 * end-to-end half of this file, and it is where that confusion was caught).
 *
 * <p>Two flags and not one ({@code docs/ir.md} §4.1), and this file is where the difference shows:
 * {@code cld} destroys every claim about where the next copy goes and leaves every comparison
 * standing, while {@code int} is the other way round — the handler's arithmetic flags are what a
 * branch behind it reads, and the handler returns through {@code iret}, which gives the interrupted
 * program its direction flag back.
 */
public final class EffectsTest {

    private static final String HEAD = "target 8086\norg 0x100\nentry $main\n\n$main:\n";

    private EffectsTest() {
    }

    public static void register(Suite suite) {
        suite.add("A statement leaves each flag, destroys it, or neither",
                EffectsTest::threeStates);
        suite.add("A machine statement's flags are the target's to describe",
                EffectsTest::theTargetSays);
        suite.add("No statement both makes a flag and gives it up", EffectsTest::oneStateEach);
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

    /** What this statement leaves in this flag: what a failure message should say. */
    private static String leaves(Item item, String flag) {
        if (Effects.flagsDefined(item).contains(flag)) {
            return "a value of its own";
        }
        if (Effects.flagsKilled(item).contains(flag)) {
            return "nothing";
        }
        return "what was there";
    }

    /** Every flag for each statement, in the order the flags are asked in. */
    private static List<String> leaves(String body) {
        List<String> answers = new ArrayList<String>();
        for (Item item : statements(body)) {
            List<String> perFlag = new ArrayList<String>();
            for (String flag : Names.flagNames()) {
                perFlag.add(leaves(item, flag));
            }
            answers.add(String.join(" / ", perFlag));
        }
        return answers;
    }

    private static void threeStates() {
        // A move computes nothing, so what is in force after it is what was in force before; an
        // operation computes the conditions and the carry; 'expr' and a conversion give both up —
        // and none of that has any business with an address, so the direction flag comes through.
        // The order is the flags, the carry, the direction flag.
        Assert.assertEquals(Arrays.asList(
                        "what was there / what was there / what was there",
                        "a value of its own / a value of its own / what was there",
                        "nothing / nothing / what was there",
                        "nothing / nothing / what was there",
                        "a value of its own / a value of its own / what was there",
                        "what was there / what was there / what was there"),
                leaves("    var x: u16\n    var y: u16\n"
                        + "    y = x\n"
                        + "    y = eval(x + 1)\n"
                        + "    y = expr(x + 1)\n"
                        + "    y = movzx x\n"
                        + "    cmp x, 1\n"
                        + "    ret\n"));

        // And a branch reads them, which is a thing it does and not a state it leaves them in.
        // It is taken to read both kinds, because nothing here reads the table that says which
        // flag a condition tests.
        Item branch = statements("    jc $main\n").get(0);
        Assert.assertEquals("[carry]", Effects.flagsRead(branch).toString());
        Assert.assertTrue(Effects.flagsDefined(branch).isEmpty(), "a branch writes nothing");
        Assert.assertTrue(Effects.flagsKilled(branch).isEmpty(), "a branch destroys nothing");

        Item copy = statements("    rep movsb\n").get(0);
        Assert.assertEquals("[direction]", Effects.flagsRead(copy).toString());
        Assert.assertTrue(Effects.flagsDefined(copy).isEmpty(), "a copy computes no flag");
        Assert.assertTrue(Effects.flagsKilled(copy).isEmpty(), "and destroys none of them");

        // And the increment, which is why the carry is a name: it changes the conditions and
        // leaves the carry exactly as it was.
        Item increment = statements("    inc x\n").get(0);
        Assert.assertEquals("[flags]", Effects.flagsDefined(increment).toString());
        Assert.assertTrue(Effects.flagsKilled(increment).isEmpty(), "it destroys nothing");
        Assert.assertTrue(Effects.flagsRead(increment).isEmpty(), "and reads nothing");
    }

    private static void theTargetSays() {
        Assert.assertEquals(Arrays.asList(
                        "what was there / what was there / what was there",
                        "what was there / what was there / what was there",
                        "what was there / what was there / what was there",
                        "what was there / what was there / what was there",
                        "a value of its own / a value of its own / what was there",
                        "nothing / a value of its own / what was there",
                        "nothing / a value of its own / what was there",
                        "what was there / what was there / a value of its own",
                        "what was there / what was there / a value of its own",
                        "a value of its own / a value of its own / a value of its own"),
                leaves("    nop\n"
                        + "    cli\n"
                        + "    sti\n"
                        + "    hlt\n"
                        + "    int 0x10 clobbers(ax, bx, cx, dx)\n"
                        + "    int 0x10 clobbers(flags)\n"
                        + "    int 0x10\n"
                        + "    cld\n"
                        + "    std\n"
                        + "    iret clobbers(ax)\n"));

        // A block is not asked, because there is nobody to ask: it destroys the flags whatever
        // its list says, and what it left behind is not something a program may read.
        Assert.assertEquals(Arrays.asList(
                        "nothing / nothing / nothing",
                        "nothing / nothing / nothing"),
                leaves("    asm clobbers(ax) {\n        nop\n    }\n"
                        + "    asm clobbers(ax, flags) {\n        nop\n    }\n"));
    }

    /**
     * The three states are one answer each, and this is the property that keeps them so: a flag
     * that were both would let a pass read either half and mean different things by it.
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
                + "    cld\n"
                + "    std\n"
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
            for (String flag : Names.flagNames()) {
                Assert.assertFalse(
                        Effects.flagsDefined(item).contains(flag)
                                && Effects.flagsKilled(item).contains(flag),
                        "statement " + at + " claims both for '" + flag + "', and one of the two "
                                + "answers decides whether the definition before it is still "
                                + "wanted");
            }
        }
    }
}
