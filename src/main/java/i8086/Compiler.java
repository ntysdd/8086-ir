package i8086;

import i8086.emit.AsmEmitter;
import i8086.ir.IrParser;
import i8086.ir.Module;

/**
 * The front door: IR text in, assembly text out.
 *
 * <p>All of the work lives here rather than in the command line entry point, so
 * that a test can compile a program without spawning a process and without
 * touching the file system ({@code AGENTS.md}, "keep command-line entry points
 * thin").
 *
 * <p>The order is the pipeline of {@code README.md}. Only its ends exist so far:
 * a module is parsed and emitted, and there are no passes to run between them
 * yet. A program whose body is entirely inline assembly has nothing to
 * optimise, which is why the first end-to-end program can be built before the
 * middle of the pipeline exists.
 */
public final class Compiler {

    private Compiler() {
    }

    /**
     * Compiles one module.
     *
     * @param file   the name to put in diagnostics, normally the path it was read from
     * @param source the IR text
     * @return the assembly text
     * @throws CompileError if the input is not something this compiler accepts
     */
    public static String compile(String file, String source) {
        Module module = IrParser.parse(file, source);
        return AsmEmitter.emit(module);
    }
}
