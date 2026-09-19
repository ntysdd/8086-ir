package i8086.ssa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The version each variable has reached at the point being walked.
 *
 * <p>Renaming walks the dominator tree, so a version pushed inside a block is
 * still in force while the blocks it dominates are walked and stops being in
 * force when the walk leaves it. A stack per variable is exactly that, and the
 * push and pop order is the tree's own shape.
 *
 * <p>A name that is not tracked is not a variable — it is a label, which names a
 * place rather than holding a value — and answers null. A tracked name whose
 * stack is empty has no version at all, and answers the variable's undefined
 * value: that is how a block nothing reaches is renamed, with a fresh set of empty
 * stacks, so that the values inside it stay inside it.
 */
final class VersionStacks implements Renamer.Versions {

    private final Map<String, List<String>> stacks = new LinkedHashMap<String, List<String>>();

    /** Starts tracking a variable, with no version in force yet. */
    void track(String variable) {
        stacks.put(variable, new ArrayList<String>());
    }

    void push(String variable, String version) {
        stacks.get(variable).add(version);
    }

    void pop(String variable) {
        List<String> stack = stacks.get(variable);
        stack.remove(stack.size() - 1);
    }

    @Override
    public String of(String name) {
        List<String> stack = stacks.get(name);
        if (stack == null) {
            return null;
        }
        return stack.isEmpty() ? SsaForm.undefined(name) : stack.get(stack.size() - 1);
    }

    /** The version in force, or the variable's undefined value — never null. */
    String current(String variable) {
        String version = of(variable);
        return version == null ? SsaForm.undefined(variable) : version;
    }
}
