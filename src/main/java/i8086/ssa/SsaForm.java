package i8086.ssa;

import i8086.SourcePos;
import i8086.ir.Module;
import i8086.ir.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A module in SSA form: every value has one name, one definition, and every use
 * is the definition that reaches it.
 *
 * <p>The graph is the one in {@link Cfg}; what is added is the content of the
 * blocks. A block holds its φ's first and then its statements, each of which is an
 * item from the module with every variable renamed, together with the flags
 * version it defines if it defines one.
 *
 * <p>Beyond the renamed items, the form holds the bookkeeping a pass cannot read
 * off them: which variable a name belongs to — a name is a string, and
 * {@code x#3} only means something next to the variable it is a version of — and
 * what type it has, which is the declared type of that variable, or nothing at
 * all when the name is a version of the flags, which are not a number and have no
 * width ({@code docs/ir.md} §4.1).
 *
 * <h3>The value with no definition</h3>
 *
 * <p>The module lets a variable be read before anything writes it, and reading
 * one is reading whatever happens to be there ({@code docs/ir.md} §3.1,
 * {@code [open]}). Rather than invent a zero, the form names that: {@code x#undef}
 * is <b>the undefined value of {@code x}</b>, and it is a name for "x, with no
 * value" rather than a definition of one.
 *
 * <p>It is per variable on purpose. A single anonymous {@code undef} would say
 * that something is unknown but not what, and the verifier's whole question is
 * per variable — "exactly one version of <em>this</em> variable reaches this use"
 * — so an answer that does not name the variable cannot be checked. The name
 * itself is never taken apart to find the variable back: the mapping is recorded
 * here, which is the same reason a version's variable is recorded rather than
 * derived from its spelling.
 */
public final class SsaForm {

    /** How the undefined value of a variable is spelled, after the variable's name. */
    private static final String UNDEFINED = "#undef";

    private final Module module;
    private final Cfg cfg;
    private final List<List<Phi>> phis;
    private final List<List<SsaStatement>> statements;
    private final Map<String, Type> types;
    private final Map<String, String> variables;
    private final Set<String> undefined;
    private final Map<String, SourcePos> positions;

    SsaForm(Module module, Cfg cfg, List<List<Phi>> phis,
            List<List<SsaStatement>> statements, Map<String, Type> types,
            Map<String, String> variables, Set<String> undefined,
            Map<String, SourcePos> positions) {
        this.module = module;
        this.cfg = cfg;
        List<List<Phi>> copiedPhis = new ArrayList<List<Phi>>();
        List<List<SsaStatement>> copiedStatements = new ArrayList<List<SsaStatement>>();
        for (List<Phi> block : phis) {
            copiedPhis.add(Collections.unmodifiableList(new ArrayList<Phi>(block)));
        }
        for (List<SsaStatement> block : statements) {
            copiedStatements.add(
                    Collections.unmodifiableList(new ArrayList<SsaStatement>(block)));
        }
        this.phis = Collections.unmodifiableList(copiedPhis);
        this.statements = Collections.unmodifiableList(copiedStatements);
        this.types = Collections.unmodifiableMap(new LinkedHashMap<String, Type>(types));
        this.variables = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(variables));
        this.undefined = Collections.unmodifiableSet(
                new LinkedHashSet<String>(undefined));
        this.positions = Collections.unmodifiableMap(
                new LinkedHashMap<String, SourcePos>(positions));
    }

    /** The name of the value a variable has before anything defines it. */
    public static String undefined(String variable) {
        return variable + UNDEFINED;
    }

    /** The module this form was built from. Building a form does not modify it. */
    public Module module() {
        return module;
    }

    /** The control flow graph the form was built over. */
    public Cfg cfg() {
        return cfg;
    }

    /** The φ's of a block, in the order they were placed; empty for most blocks. */
    public List<Phi> phis(Block block) {
        return phis.get(block.index());
    }

    /** What a block does, after its φ's. */
    public List<SsaStatement> statements(Block block) {
        return statements.get(block.index());
    }

    /** Whether this name is a version, that is, whether something defines it. */
    public boolean isVersion(String name) {
        return variables.containsKey(name) && !undefined.contains(name);
    }

    /** Whether this name is the undefined value of a variable. */
    public boolean isUndef(String name) {
        return undefined.contains(name);
    }

    /** Whether the name is a version or the undefined value of a variable. */
    public boolean isValue(String name) {
        return variables.containsKey(name);
    }

    /** Every version, in the order it was created. */
    public Set<String> versions() {
        Set<String> versions = new LinkedHashSet<String>();
        for (String name : variables.keySet()) {
            if (!undefined.contains(name)) {
                versions.add(name);
            }
        }
        return Collections.unmodifiableSet(versions);
    }

    /** The type of a value, or null when it is a version of the flags. */
    public Type typeOf(String name) {
        return types.get(name);
    }

    /** The variable this name is a version of, or null when the name is not one. */
    public String variableOf(String name) {
        return variables.get(name);
    }

    /** Where a version was defined, for a refusal that has to point at one. */
    public SourcePos positionOf(String version) {
        return positions.get(version);
    }

    /**
     * The same module and graph with different block contents.
     *
     * <p>This exists for the verifier, which has to be able to be handed a form a
     * builder would not produce ({@code AGENTS.md} invariant 4), and for a pass
     * that rewrites φ's without touching anything else. Nothing here checks the
     * result: that is what the verifier is for.
     */
    public SsaForm replacing(List<List<Phi>> newPhis, List<List<SsaStatement>> newStatements) {
        return new SsaForm(module, cfg, newPhis, newStatements, types, variables, undefined,
                positions);
    }
}
