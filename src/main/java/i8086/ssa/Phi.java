package i8086.ssa;

import i8086.SourcePos;
import i8086.ir.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A φ: the value a variable has when two paths meet.
 *
 * <p>A φ belongs to a block and has one operand per predecessor, in that order:
 * arriving from the third predecessor makes the variable the third operand. That
 * order is why the predecessors of a block are part of the SSA form rather than a
 * detail of how the graph was built.
 *
 * <p>A φ is <em>placed</em> before it is named. Where one is needed follows from
 * dominance alone; the name and the operands come from the walk that renames the
 * module. The fields are therefore filled in that order and read only afterwards,
 * which is why they are not final: one pass, one write, and no second writer to
 * disagree with. An operand no path ever filled is the variable's undefined
 * value, which is what an edge that is never taken carries.
 */
public final class Phi {

    private final SourcePos position;
    private final String variable;
    private final Type type;
    private final String[] operands;
    private String name;

    Phi(SourcePos position, String variable, Type type, int predecessors) {
        this.position = position;
        this.variable = variable;
        this.type = type;
        this.operands = new String[predecessors];
    }

    /** Where the block that holds this φ begins. */
    public SourcePos position() {
        return position;
    }

    /** The variable this φ merges, or {@link i8086.ir.Names#FLAGS} for the flags. */
    public String variable() {
        return variable;
    }

    /** The type of the merged value, or null when the merged value is the flags. */
    public Type type() {
        return type;
    }

    /** The version this φ defines. */
    public String name() {
        return name;
    }

    /** The operands, one per predecessor, in predecessor order. */
    public List<String> operands() {
        List<String> all = new ArrayList<String>(operands.length);
        for (int i = 0; i < operands.length; i++) {
            all.add(operand(i));
        }
        return Collections.unmodifiableList(all);
    }

    /** The operand for the given predecessor, or the variable's undefined value. */
    public String operand(int predecessor) {
        String value = operands[predecessor];
        return value == null ? SsaForm.undefined(variable) : value;
    }

    void setName(String version) {
        this.name = version;
    }

    void setOperand(int predecessor, String version) {
        this.operands[predecessor] = version;
    }

    @Override
    public String toString() {
        return name + " = phi(" + operands() + ")";
    }
}
