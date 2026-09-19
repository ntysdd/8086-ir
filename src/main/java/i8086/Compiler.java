package i8086;

import i8086.emit.AsmEmitter;
import i8086.ir.IrParser;
import i8086.ir.IrVerifier;
import i8086.ir.Module;
import i8086.isel.InstructionSelector;
import i8086.isel.Selection;
import i8086.regalloc.RegisterAllocator;
import i8086.target.Target;
import i8086.target.Targets;

/**
 * The front door: IR text in, assembly text out.
 *
 * <p>All of the work lives here rather than in the command line entry point, so
 * that a test can compile a program without spawning a process and without
 * touching the file system ({@code AGENTS.md}, "keep command-line entry points
 * thin").
 *
 * <p>The order is the pipeline of {@code README.md}: parse, verify, select
 * instructions, allocate registers, emit. What is not here yet is the middle of
 * the optimiser — SSA and the passes — which goes between verifying and
 * selecting, and which is why a program can be compiled today but not yet
 * improved.
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
        Target target = targetOf(module);
        IrVerifier.verify(module, target);
        Selection selected = InstructionSelector.select(module, target);
        Selection allocated = RegisterAllocator.allocate(selected, target);
        return AsmEmitter.emit(module, allocated);
    }

    private static Target targetOf(Module module) {
        Target target = Targets.byName(module.target());
        if (target == null) {
            // The parser already refuses a target nobody knows, so reaching here
            // would mean the two disagree about what exists.
            throw new IllegalStateException("no description for target " + module.target());
        }
        return target;
    }
}
