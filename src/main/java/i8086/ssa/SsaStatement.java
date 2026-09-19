package i8086.ssa;

import i8086.ir.Item;

/**
 * One item in SSA form, and the flags version it defines.
 *
 * <p>The item is the one from the module with every variable renamed: what it
 * writes carries the version it defines, so a statement says what it defines just
 * by being read ({@code x#3 = 0}). The one thing an item cannot say is that it
 * defined the flags, because the surface never mentions them
 * ({@code docs/ir.md} §4.1), so that is recorded alongside it.
 */
public final class SsaStatement {

    private final Item item;
    private final String definedFlags;

    SsaStatement(Item item, String definedFlags) {
        this.item = item;
        this.definedFlags = definedFlags;
    }

    /** The item, with its variables renamed. */
    public Item item() {
        return item;
    }

    /** The flags version this statement defines, or null when it defines none. */
    public String definedFlags() {
        return definedFlags;
    }

    @Override
    public String toString() {
        return String.valueOf(item);
    }
}
