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
 * <p>The <em>version</em> a name is replaced by is whatever the caller says it is,
 * which is what lets one renamer serve both directions. Building SSA asks for the
 * version in force at that point; tearing it down asks for the variable the
 * version belongs to, and gets the mutable name back. The one place they differ is
 * the place an item writes: for a definition the version in force is not the one
 * being written, so SSA construction names that place itself once the version
 * exists, with {@link #define}.
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
        if (item instanceof Item.MovReg) {
            // The state being written is the machine's and is not renamed; what is put there is a
            // value like any other.
            Item.MovReg movreg = (Item.MovReg) item;
            if (movreg.value() == null) {
                return item;
            }
            return Item.MovReg.fromValue(item.position(), movreg.name(),
                    rename(movreg.value(), versions));
        }
        if (item instanceof Item.MovRegRead) {
            // The register is the machine's and is not renamed; what the statement defines is a
            // value like any other (docs/ir.md §8.1).
            Item.MovRegRead read = (Item.MovRegRead) item;
            String version = versions.of(read.variable());
            return version == null ? item
                    : new Item.MovRegRead(item.position(), version, read.register());
        }
        if (item instanceof Item.Machine) {
            // A statement's clause reads values, so they are renamed like any other read
            // ({@code docs/ir.md} §11).
            Item.Machine machine = (Item.Machine) item;
            return new Item.Machine(item.position(), machine.mnemonic(), machine.operands(),
                    machine.clobbers(), rename(machine.arguments(), versions));
        }
        if (item instanceof Item.FarJump) {
            Item.FarJump far = (Item.FarJump) item;
            return new Item.FarJump(item.position(), far.segment(), far.offset(),
                    rename(far.arguments(), versions));
        }
        if (item instanceof Item.InlineAsm) {
            Item.InlineAsm block = (Item.InlineAsm) item;
            return new Item.InlineAsm(item.position(), block.clobbers(), block.body(),
                    rename(block.arguments(), versions));
        }
        return item;
    }

    /** The arguments of a clause, with the values in them renamed. */
    private static List<Item.Argument> rename(List<Item.Argument> arguments, Versions versions) {
        List<Item.Argument> renamed = new ArrayList<Item.Argument>();
        for (Item.Argument argument : arguments) {
            renamed.add(new Item.Argument(argument.position(), argument.register(),
                    rename(argument.value(), versions)));
        }
        return renamed;
    }

    /**
     * Names the value a definition defines, now that its version is known.
     *
     * <p>Two items define a value: an assignment, whose place is a name — memory is not renamed,
     * so a store defines nothing — and a {@code movreg} reading a register, whose value is the name
     * on its left ({@code docs/ir.md} §8.1).
     */
    static Item define(Item item, String version) {
        if (item instanceof Item.MovRegRead) {
            Item.MovRegRead read = (Item.MovRegRead) item;
            return new Item.MovRegRead(read.position(), version, read.register());
        }
        Item.Assign assign = (Item.Assign) item;
        Place.Name place = (Place.Name) assign.place();
        return new Item.Assign(assign.position(), new Place.Name(place.position(), version),
                assign.value());
    }

    static Place rename(Place place, Versions versions) {
        if (place instanceof Place.Memory) {
            return new Place.Memory(place.position(),
                    rename(((Place.Memory) place).operand(), versions));
        }
        Place.Name named = (Place.Name) place;
        String version = versions.of(named.name());
        return version == null ? place : new Place.Name(named.position(), version);
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
        if (expression instanceof Expression.Unary) {
            Expression.Unary unary = (Expression.Unary) expression;
            return new Expression.Unary(expression.position(), unary.operator(),
                    rename(unary.operand(), versions));
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
