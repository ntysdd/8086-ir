package i8086;

/**
 * A position in a source file: the file name, and a 1-based line and column.
 *
 * <p>Every diagnostic the compiler produces carries one of these. A position is
 * a value: two positions are equal when they name the same place, and it prints
 * as {@code file:line:column}, which is the form the command line tool and the
 * tests both expect.
 */
public final class SourcePos {

    private final String file;
    private final int line;
    private final int column;

    public SourcePos(String file, int line, int column) {
        if (file == null) {
            throw new NullPointerException("file");
        }
        if (line < 1) {
            throw new IllegalArgumentException("line is 1-based, got " + line);
        }
        if (column < 1) {
            throw new IllegalArgumentException("column is 1-based, got " + column);
        }
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public String file() {
        return file;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    @Override
    public String toString() {
        return file + ":" + line + ":" + column;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SourcePos)) {
            return false;
        }
        SourcePos that = (SourcePos) other;
        return line == that.line && column == that.column && file.equals(that.file);
    }

    @Override
    public int hashCode() {
        int hash = file.hashCode();
        hash = 31 * hash + line;
        hash = 31 * hash + column;
        return hash;
    }
}
