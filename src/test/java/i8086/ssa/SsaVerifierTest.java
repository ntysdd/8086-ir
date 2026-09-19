package i8086.ssa;

import i8086.CompileError;
import i8086.SourcePos;
import i8086.ir.Item;
import i8086.ir.Module;
import i8086.ir.Names;
import i8086.ir.Operator;
import i8086.ir.Operation;
import i8086.ir.Place;
import i8086.ir.Type;
import i8086.ir.Value;
import i8086.testing.Assert;
import i8086.testing.Suite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests for the SSA verifier.
 *
 * <p>Two kinds of test, and the difference between them is the point of the
 * verifier. The first kind builds a form with the builder and asks whether it is
 * accepted — that is the check that runs on every program this compiler compiles.
 *
 * <p>The second kind <b>puts a form together by hand</b> with one thing wrong with
 * it, because a verifier can only be tested by what it refuses
 * ({@code AGENTS.md}, invariant 4). The malformations are the ones that matter:
 * a use that two versions of one variable reach with no φ to merge them, which is
 * what a φ placed in the wrong block looks like from the outside; and a name that
 * was never renamed, which is a use that would silently read whichever definition
 * happened to be live.
 *
 * <p>Every test here uses the same program, so that the malformation is the only
 * thing that differs between them:
 *
 * <pre>
 *   main:
 *       var x: u16
 *       var y: u16
 *       x = 1
 *       cmp y, 0
 *       jnc l0
 *       x = 2
 *   l0:
 *       y = eval(x + 1)
 *       ret
 * </pre>
 *
 * <p>which is three blocks: one with the assignment and the test, one with
 * {@code x = 2}, and the join — which both of them reach — where {@code x} is
 * read.
 */
public final class SsaVerifierTest {

    /** Where every item a test builds by hand sits, so that a refusal can be checked. */
    private static final SourcePos AT = new SourcePos("test.ir", 7, 5);

    private static final String PROGRAM = "    var x: u16\n    var y: u16\n"
            + "    x = 1\n"
            + "    cmp y, 0\n"
            + "    jnc l0\n"
            + "    x = 2\n"
            + "l0:\n"
            + "    y = eval(x + 1)\n"
            + "    ret\n";

    private SsaVerifierTest() {
    }

    public static void register(Suite suite) {
        suite.add("Ssa verifier accepts what the builder produces", SsaVerifierTest::acceptsBuilt);
        suite.add("Ssa verifier accepts a read before any write", SsaVerifierTest::acceptsUndef);
        suite.add("Ssa verifier accepts a form built by hand", SsaVerifierTest::acceptsHandMade);
        suite.add("Ssa verifier refuses a use two versions reach with no φ",
                SsaVerifierTest::refusesMissingPhi);
        suite.add("Ssa verifier refuses a φ with the wrong number of operands",
                SsaVerifierTest::refusesWrongOperandCount);
        suite.add("Ssa verifier refuses a φ operand the path does not arrive with",
                SsaVerifierTest::refusesWrongOperand);
        suite.add("Ssa verifier refuses the undefined value of another variable",
                SsaVerifierTest::refusesSomeoneElsesUndef);
        suite.add("Ssa verifier refuses a name that was never renamed",
                SsaVerifierTest::refusesUnrenamed);
        suite.add("Ssa verifier refuses a version defined twice",
                SsaVerifierTest::refusesTwoDefinitions);
        suite.add("Ssa verifier refuses a version nothing defines",
                SsaVerifierTest::refusesInventedVersion);
        suite.add("Ssa verifier refuses a statement that disagrees about the flags",
                SsaVerifierTest::refusesFlagsDisagreement);
    }

    // --- the forms that should be accepted ---------------------------------

    private static void acceptsBuilt() {
        SsaVerifier.verify(SsaBuilder.build(CfgTest.parse(PROGRAM)));
    }

    private static void acceptsUndef() {
        // Reading a variable nothing writes is allowed (docs/ir.md §3.1), and the
        // form says so with that variable's undefined value rather than a guess at
        // zero.
        SsaVerifier.verify(SsaBuilder.build(
                CfgTest.parse("    var x: u16\n    var y: u16\n    y = eval(x + 1)\n    ret\n")));
    }

    private static void acceptsHandMade() {
        SsaVerifier.verify(valid().form());
    }

    // --- the forms that should be refused ----------------------------------

    private static void refusesMissingPhi() {
        // No φ, and the read names the version from before the branch. Two
        // versions of x arrive at that read and nothing merges them, which is the
        // failure a dominance check would happily accept: x#1 does dominate the
        // read, it is just not the only value that arrives.
        Form form = valid();
        form.phis(2).clear();
        form.forget("x#4");
        form.statements(2).set(0, statement(evalAdd("y#5", "x#1"), "flags#6"));
        expectRefusal(form, "two versions of 'x' arrive with no φ to merge them");
    }

    private static void refusesWrongOperandCount() {
        Form form = valid();
        Phi phi = new Phi(AT, "x", Type.U16, 3);
        phi.setName("x#4");
        form.phis(2).set(0, phi);
        expectRefusal(form, "a φ takes one operand per path");
    }

    private static void refusesWrongOperand() {
        // The φ claims both edges carry the same version. Both operands are
        // versions of x, so only the comparison with what each predecessor
        // actually leaves behind finds it.
        Form form = valid();
        Phi phi = new Phi(AT, "x", Type.U16, 2);
        phi.setName("x#4");
        phi.setOperand(0, "x#3");
        phi.setOperand(1, "x#3");
        form.phis(2).set(0, phi);
        expectRefusal(form, "but that path arrives with 'x#1'");
    }

    private static void refusesSomeoneElsesUndef() {
        // The undefined value names its variable, so a φ that takes another
        // variable's is caught rather than read as "something unknown".
        Form form = valid();
        Phi phi = new Phi(AT, "x", Type.U16, 2);
        phi.setName("x#4");
        phi.setOperand(0, SsaForm.undefined("y"));
        phi.setOperand(1, "x#3");
        form.phis(2).set(0, phi);
        expectRefusal(form, "is the undefined value of 'y'");
    }

    private static void refusesUnrenamed() {
        // The use still says 'x'. That is not a wrong version but a name that
        // means every version at once, which is what renaming exists to remove.
        Form form = valid();
        form.statements(2).set(0, statement(evalAdd("y#5", "x"), "flags#6"));
        expectRefusal(form, "was never renamed");
    }

    private static void refusesTwoDefinitions() {
        Form form = valid();
        form.statements(1).add(statement(assign("x#3", 9)));
        expectRefusal(form, "is defined 2 times");
    }

    private static void refusesInventedVersion() {
        Form form = valid();
        form.statements(1).set(0, statement(assign("x#9", 2)));
        expectRefusal(form, "which is not a version of anything");
    }

    private static void refusesFlagsDisagreement() {
        // A 'cmp' defines the flags. A statement that claims it defines none is a
        // form disagreeing with itself, whatever the flags check would make of it.
        Form form = valid();
        form.statements(0).set(1, statement(compareThose("y#undef")));
        expectRefusal(form, "disagrees with itself about the flags");
    }

    /** Verifies the form, insists it was refused at the position, and says why. */
    private static void expectRefusal(Form form, String expected) {
        CompileError refused = Assert.assertRefused(AT.toString(),
                () -> SsaVerifier.verify(form.form()));
        Assert.assertTrue(refused.getMessage().contains(expected),
                "the refusal says what was wrong: " + refused.getMessage());
    }

    // --- the program, and the form that is right for it ---------------------

    /**
     * The form the builder would produce for {@link #PROGRAM}, written out by hand
     * so that each test can bend exactly one thing about it.
     */
    private static Form valid() {
        Form form = new Form();
        form.version("x#1", "x", Type.U16);
        form.version("flags#2", Names.FLAGS, null);
        form.version("x#3", "x", Type.U16);
        form.version("x#4", "x", Type.U16);
        form.version("y#5", "y", Type.U16);
        form.version("flags#6", Names.FLAGS, null);
        form.undef("y");

        form.statements(0).add(statement(assign("x#1", 1)));
        form.statements(0).add(statement(compareThose("y#undef"), "flags#2"));
        form.statements(0).add(new SsaStatement(branch("jnc", "l0"), null));
        form.statements(1).add(statement(assign("x#3", 2)));

        Phi phi = new Phi(AT, "x", Type.U16, 2);
        phi.setName("x#4");
        phi.setOperand(0, "x#1");
        phi.setOperand(1, "x#3");
        form.phis(2).add(phi);
        form.statements(2).add(statement(evalAdd("y#5", "x#4"), "flags#6"));
        form.statements(2).add(new SsaStatement(new Item.Return(AT), null));
        return form;
    }

    // --- the pieces an item is built from -----------------------------------

    private static Item.Assign assign(String place, long number) {
        return new Item.Assign(AT, new Place.Name(AT, place),
                new Value.Number(AT, number, String.valueOf(number)));
    }

    private static Item.Compare compareThose(String name) {
        return new Item.Compare(AT, Item.Compare.Kind.CMP, name(name),
                new Value.Number(AT, 0, "0"));
    }

    private static Item.Branch branch(String condition, String target) {
        return new Item.Branch(AT, condition, target);
    }

    private static Item.Assign evalAdd(String place, String operand) {
        Operation operation = new Operation(AT, Operator.ADD,
                Arrays.asList(name(operand), new Value.Number(AT, 1, "1")));
        return new Item.Assign(AT, new Place.Name(AT, place), new Value.Eval(AT, operation));
    }

    private static Value name(String name) {
        return new Value.Name(AT, name);
    }

    private static SsaStatement statement(Item item) {
        return new SsaStatement(item, null);
    }

    private static SsaStatement statement(Item item, String definedFlags) {
        return new SsaStatement(item, definedFlags);
    }

    /**
     * A form put together by hand.
     *
     * <p>The builder is not used, because a verifier test has to be able to hand
     * the verifier something a builder would never produce. Everything the form
     * needs beyond its blocks is registered as the test goes, which is also the
     * only way to see that a version's variable and its type are part of the form
     * rather than worked out from the way its name is spelled.
     */
    private static final class Form {

        private final Module module = CfgTest.parse(PROGRAM);
        private final Names names = Names.of(module);
        private final Cfg cfg = Cfg.of(module);
        private final List<List<Phi>> phis = new ArrayList<List<Phi>>();
        private final List<List<SsaStatement>> statements =
                new ArrayList<List<SsaStatement>>();
        private final Map<String, Type> types = new LinkedHashMap<String, Type>();
        private final Map<String, String> variables = new LinkedHashMap<String, String>();
        private final Set<String> undefined = new LinkedHashSet<String>();
        private final Map<String, SourcePos> positions =
                new LinkedHashMap<String, SourcePos>();

        private Form() {
            for (int i = 0; i < cfg.blocks().size(); i++) {
                phis.add(new ArrayList<Phi>());
                statements.add(new ArrayList<SsaStatement>());
            }
        }

        private Form version(String name, String variable, Type type) {
            types.put(name, type);
            variables.put(name, variable);
            positions.put(name, AT);
            return this;
        }

        private Form undef(String variable) {
            String name = SsaForm.undefined(variable);
            types.put(name, names.typeOf(variable));
            variables.put(name, variable);
            undefined.add(name);
            positions.put(name, AT);
            return this;
        }

        /** Drops a version from the tables, so that a test can leave one undefined. */
        private Form forget(String version) {
            types.remove(version);
            variables.remove(version);
            positions.remove(version);
            return this;
        }

        private List<SsaStatement> statements(int block) {
            return statements.get(block);
        }

        private List<Phi> phis(int block) {
            return phis.get(block);
        }

        private SsaForm form() {
            return new SsaForm(module, cfg, phis, statements, types, variables, undefined,
                    positions);
        }
    }
}
