package i8086;

import i8086.emit.AsmEmitter;
import i8086.ir.IrParser;
import i8086.ir.IrPrinter;
import i8086.ir.IrVerifier;
import i8086.ir.Module;
import i8086.isel.InstructionSelector;
import i8086.isel.Selection;
import i8086.pass.Pipeline;
import i8086.regalloc.RegisterAllocator;
import i8086.ssa.OutOfSsa;
import i8086.ssa.SsaBuilder;
import i8086.ssa.SsaForm;
import i8086.ssa.SsaPrinter;
import i8086.ssa.SsaVerifier;
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
 * <p>The order is the pipeline of {@code README.md}: parse, verify, build SSA,
 * optimize, select instructions, allocate registers, emit. A stage that rewrites what
 * it was given runs on verified input and has its output verified in turn, which is
 * the first invariant — and the reason the verifications are named here rather than
 * hidden inside the stages that produce what they check. The pass list itself is in
 * {@link Pipeline}, which verifies around each pass.
 *
 * <p>What is not in that order any more is leaving SSA. Selection and allocation read
 * the form the passes produced, so the allocator can tell which definition each use
 * reads and which names a φ puts in one register ({@code docs/ssa.md}); the only thing
 * left of the old step is the dump a person reads.
 *
 * <p>Nothing here is target-specific. The SSA form and the passes are all in the IR's
 * own vocabulary; selection and allocation are the only stages that ask a target
 * anything ({@code AGENTS.md}, invariant 2).
 */
public final class Compiler {

    /**
     * Which stage of the pipeline to stop after.
     *
     * <p>The stages are in the order they run, and `--emit` stops after one: the
     * IR as the transformations left it, the SSA form, or the assembly. Naming
     * them is what lets one command show any of the three, and what keeps "which
     * stage is this" out of the command line's own vocabulary.
     */
    public enum Stage {
        /** The IR, printed. */
        IR,
        /** The SSA form, printed. */
        SSA,
        /**
         * The assembly text, in the dialect NASM reads. Named after the assembler
         * rather than after "assembly", because a dump a tool cannot read is a dump
         * and not a program: what comes out of here is what {@code nasm -f bin} turns
         * into the image.
         */
        NASM
    }

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
        return compile(file, source, Stage.NASM);
    }

    /**
     * Runs the pipeline as far as a stage and returns what that stage produces.
     *
     * <p>Every stage after the first is only reached by way of the verifications
     * before it, so a stage's output is something the compiler accepted, whichever
     * stage was asked for. That matters most for the two dumps: they are the only
     * way to see the middle of the pipeline, and a dump of a form that would not
     * have been compiled would be worse than no dump.
     */
    public static String compile(String file, String source, Stage stage) {
        Module module = IrParser.parse(file, source);
        SsaForm optimized = Pipeline.run(verify(module));
        if (stage == Stage.SSA) {
            return SsaPrinter.print(optimized);
        }
        if (stage == Stage.IR) {
            return IrPrinter.print(lowered(optimized, module));
        }
        Target target = targetOf(module);
        Selection selected = InstructionSelector.select(optimized, target);
        Selection allocated = RegisterAllocator.allocate(selected, target);
        return AsmEmitter.emit(optimized.module(), allocated);
    }

    /**
     * The form with its versions back to the variables they are versions of, printed
     * as the surface a person wrote.
     *
     * <p>Leaving SSA is a transformation like any other, so what it produces is checked
     * like any input before anything reads it — which here means before it is printed,
     * because the back end no longer needs it: selection reads the form, and what the
     * allocator has to be told is which names a φ puts in one register
     * ({@link Selection#registerGroups}).
     */
    private static Module lowered(SsaForm optimized, Module module) {
        Module lowered = OutOfSsa.module(optimized);
        IrVerifier.verify(lowered, targetOf(module));
        return lowered;
    }

    /**
     * The SSA form of a module, for a dump or for a pass that will read one.
     *
     * <p>It is the same module the compiler would compile, and it has survived the
     * same verifications in the same order before this returns anything.
     */
    public static SsaForm ssa(String file, String source) {
        return verify(IrParser.parse(file, source));
    }

    /** The SSA form, printed. This is what {@code --emit ssa} writes. */
    public static String printSsa(String file, String source) {
        return compile(file, source, Stage.SSA);
    }

    /**
     * Everything the input has to survive before anything acts on it, and the SSA
     * form it survived into.
     *
     * <p>Both verifications are here, in order, so that one place says what "this
     * compiler accepts" means: the surface's own rules first, and then the SSA
     * property of what renaming that produced. The form is handed back rather than
     * dropped, so that a caller wanting one does not build it twice.
     */
    private static SsaForm verify(Module module) {
        Target target = targetOf(module);
        IrVerifier.verify(module, target);
        return SsaBuilder.build(module);
    }

    /** The target a module names, which the parser has already checked it names. */
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
