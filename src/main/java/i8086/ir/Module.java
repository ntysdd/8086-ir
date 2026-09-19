package i8086.ir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A parsed module: what the file said, and nothing else.
 *
 * <p>This is the input to everything downstream and the output of the parser.
 * It is not SSA, and it is not a control flow graph; it is the surface a person
 * wrote ({@code docs/ir.md}).
 */
public final class Module {

    private final String target;
    private final int origin;
    private final String entry;
    private final List<Item> items;

    public Module(String target, int origin, String entry, List<Item> items) {
        this.target = target;
        this.origin = origin;
        this.entry = entry;
        this.items = Collections.unmodifiableList(new ArrayList<Item>(items));
    }

    /** The target the module is written for, as written, lower-cased. */
    public String target() {
        return target;
    }

    /** Where the image is loaded ({@code org}). */
    public int origin() {
        return origin;
    }

    /** The label execution begins at ({@code entry}). */
    public String entry() {
        return entry;
    }

    public List<Item> items() {
        return items;
    }
}
