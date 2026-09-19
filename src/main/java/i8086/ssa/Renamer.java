package i8086.ssa;

import i8086.ir.Expression;
import i8086.ir.Item;
import i8086.ir.MemoryOperand;
import i8086.ir.Operation;
import i8086.ir.Place;
import i8086.ir.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites the variables in an item into the versions they are at that point.
 *
 * <p>A name that has a version is a variable and is replaced; a name that does
 * not is a label, and a label names a place rather than holding a value, so it is
 * left alone. That one rule is the whole of the renaming, and it is why a memory
 * operand needs no special case: {@code [p]} is rewritten because {@code p} is a
 * variable, and {@code [msg]} is not because {@code msg} is not.
 *
 * <p>What an item <em>writes</em> is the exception. {@code x = x + 1} reads the
 * old version on the right and defines a new one on the left, so the place is
 * named by the caller once the version exists rather than looked up in the
 * versions in force.
 */
final class Renamer {

    private Renamer() {
    }

    static Item rename(Item item, Versions versions) {
        if (item instanceof Item.Assign) {
            Item.Assign assign = (Item.Assign) item;
            return new Item.Assign(item.position(), rename(assign.place(), versions),
                    rename(assign.value(), versions));
        }
        if (item instanceof Item.Compare) {
            Item.Compare compare = (Item.Compare) item;
            return new Item.Compare(item.position(), compare.kind(),
                    rename(compare.left(), versions), rename(compare.right(), versions));
        }
        if (item instanceof Item.Eval) {
            return new Item.Eval(item.position(),
                    rename(((Item.Eval) item).operation(), versions));
        }
        return item;
    }

    /** Names the place an assignment writes, now that its version is known. */
    static Item define(Item.Assign assign, String version) {
        Place.Name place = (Place.Name) assign.place();
        return new Item.Assign(assign.position(), new Place.Name(place.position(), version),
                assign.value());
    }

    private static Place rename(Place place, Versions versions) {
        if (place instanceof Place.Memory) {
            return new Place.Memory(place.position(),
                    rename(((Place.Memory) place).operand(), versions));
        }
        return place;
    }

    private static Value rename(Value value, Versions versions) {
        if (value instanceof Value.Name) {
            Value.Name name = (Value.Name) value;
            String version = versions.of(name.name());
            return version == null ? value : new Value.Name(name.position(), version);
        }
        if (value instanceof Value.Memory) {
            return new Value.Memory(value.position(),
                    rename(((Value.Memory) value).operand(), versions));
        }
        if (value instanceof Value.Eval) {
            return new Value.Eval(value.position(),
                    rename(((Value.Eval) value).operation(), versions));
        }
        if (value instanceof Value.Expr) {
            return new Value.Expr(value.position(),
                    rename(((Value.Expr) value).expression(), versions));
        }
        if (value instanceof Value.Convert) {
            Value.Convert convert = (Value.Convert) value;
            return new Value.Convert(value.position(), convert.conversion(),
                    rename(convert.operand(), versions));
        }
        return value;
    }

    private static Operation rename(Operation operation, Versions versions) {
        List<Value> operands = new ArrayList<Value>();
        for (Value operand : operation.operands()) {
            operands.add(rename(operand, versions));
        }
        return new Operation(operation.position(), operation.operator(), operands);
    }

    private static Expression rename(Expression expression, Versions versions) {
        if (expression instanceof Expression.Leaf) {
            Expression.Leaf leaf = (Expression.Leaf) expression;
            return new Expression.Leaf(expression.position(), rename(leaf.value(), versions));
        }
        if (expression instanceof Expression.Complement) {
            Expression.Complement complement = (Expression.Complement) expression;
            return new Expression.Complement(expression.position(),
                    rename(complement.operand(), versions));
        }
        Expression.Apply apply = (Expression.Apply) expression;
        return new Expression.Apply(expression.position(), apply.operator(),
                rename(apply.left(), versions), rename(apply.right(), versions));
    }

    private static MemoryOperand rename(MemoryOperand operand, Versions versions) {
        String base = operand.base();
        String version = base == null ? null : versions.of(base);
        if (version == null) {
            return operand;
        }
        return new MemoryOperand(operand.position(), operand.size(), operand.segment(), version,
                operand.displacement());
    }

    /**
     * The versions in force, which is what a renamer has to ask.
     *
     * <p>It is a question because the answer changes as a block is walked and is
     * unwound when the walk leaves it, and because a place with no version behind
     * it has none in force at all.
     */
    interface Versions {

        /** The version of this name, or null when the name is not a variable. */
        String of(String name);
    }
}
