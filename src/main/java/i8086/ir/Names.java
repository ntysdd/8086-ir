package i8086.ir;

import i8086.CompileError;
import i8086.SourcePos;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the names in a module are.
 *
 * <p>The syntax does not say whether a name stands for a variable or for a
 * label: {@code y = x} and {@code p = msg} are written the same way, and
 * {@code [x]} is a load through a register while {@code [msg]} is a load from a
 * fixed address. Deciding it once, here, is what lets one parser serve the whole
 * surface, and it is the first question the verifier asks
 * ({@code docs/ir.md} §3.1).
 *
 * <p>Collecting is also where a module can contradict itself: two variables with
 * one name, a variable and a label with one name, or a declaration of the flag
 * set, which no module declares (§4.1). Those are errors with a position, so
 * they are reported here rather than left for a reader further down to trip
 * over. Nothing here is a guess about how wide something is; that stays with the
 * verifier and the question it is asked.
 */
public final class Names {

    /** The flag set: the one name a module uses without declaring it (§4.1). */
    public static final String FLAGS = "flags";

    private final Map<String, Type> variables;
    private final Set<String> labels;

    private Names(Map<String, Type> variables, Set<String> labels) {
        this.variables = Collections.unmodifiableMap(variables);
        this.labels = Collections.unmodifiableSet(labels);
    }

    /**
     * The names of a module, in declaration order, or a refusal.
     *
     * <p>Declaration order is kept because it is observable: it is the order the
     * things a pass iterates over come out in, and output may not depend on hash
     * order ({@code AGENTS.md}, invariant 6).
     */
    public static Names of(Module module) {
        Map<String, Type> variables = new LinkedHashMap<String, Type>();
        Set<String> labels = new LinkedHashSet<String>();
        for (Item item : module.items()) {
            if (item instanceof Item.Label) {
                declareLabel(labels, variables, ((Item.Label) item).name(), item.position());
            } else if (Item.labelOf(item) != null) {
                // A data definition and a piece of padding are both a named place:
                // the name is an address either way (docs/ir.md §3.1, §10.2).
                declareLabel(labels, variables, Item.labelOf(item), item.position());
            } else if (item instanceof Item.Var) {
                declareVariable(labels, variables, (Item.Var) item);
            }
        }
        return new Names(variables, labels);
    }

    private static void declareLabel(Set<String> labels, Map<String, Type> variables, String name,
                                     SourcePos where) {
        require(!labels.contains(name), where, "the label '" + name + "' is already defined");
        require(!variables.containsKey(name), where,
                "'" + name + "' is already a variable, so it cannot also be a label");
        labels.add(name);
    }

    private static void declareVariable(Set<String> labels, Map<String, Type> variables,
                                       Item.Var variable) {
        require(!variable.name().equals(FLAGS), variable.position(),
                "'" + FLAGS + "' is the flag set, which every module already has, so it cannot "
                        + "be declared");
        require(!variables.containsKey(variable.name()), variable.position(),
                "the variable '" + variable.name() + "' is already declared");
        require(!labels.contains(variable.name()), variable.position(),
                "'" + variable.name() + "' is already a label, so it cannot also be a variable");
        variables.put(variable.name(), variable.type());
    }

    /** The variables, in declaration order. */
    public Set<String> variables() {
        return variables.keySet();
    }

    /** The labels, in the order they were declared. */
    public Set<String> labels() {
        return labels;
    }

    public boolean isVariable(String name) {
        return variables.containsKey(name);
    }

    public boolean isLabel(String name) {
        return labels.contains(name);
    }

    /** The type of a variable, or null when the name is not one. */
    public Type typeOf(String name) {
        return variables.get(name);
    }

    private static void require(boolean condition, SourcePos where, String message) {
        if (!condition) {
            throw new CompileError(where, message);
        }
    }
}
