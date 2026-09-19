package i8086.ir;

import i8086.SourcePos;

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
    private final SourcePos entryPosition;
    private final List<Item> items;

    public Module(String target, int origin, String entry, SourcePos entryPosition,
                  List<Item> items) {
        this.target = target;
        this.origin = origin;
        this.entry = entry;
        this.entryPosition = entryPosition;
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

    /**
     * Where {@code entry} was written.
     *
     * <p>Kept so that "that label is never defined" can be reported against the
     * declaration rather than somewhere in the middle of the program.
     */
    public SourcePos entryPosition() {
        return entryPosition;
    }

    public List<Item> items() {
        return items;
    }
}
