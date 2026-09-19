package i8086;

import i8086.emit.AsmEmitter;
import i8086.ir.IrParser;
import i8086.ir.IrPrinter;
import i8086.ir.IrVerifier;
import i8086.ir.Module;
import i8086.isel.InstructionSelector;
import i8086.isel.Selection;
import i8086.regalloc.RegisterAllocator;
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
 * verify the SSA, select instructions, allocate registers, emit. A stage that
 * rewrites what it was given runs on verified input and has its output verified
 * in turn, which is the first invariant — and the reason the verifications are
 * named here rather than hidden inside the stages that produce what they check.
 *
 * <p>SSA construction is on this path even though no pass consumes its result
 * yet, and deliberately: a module this compiler accepts is one that survives
 * being renamed, so the renaming and the SSA verifier between them have every
 * program the tests compile as evidence. The passes that read the form come next.
 *
 * <p>Nothing here is target-specific. The SSA form is built and checked in the
 * IR's own vocabulary; selection and allocation are the only stages that ask a
 * target anything ({@code AGENTS.md}, invariant 2).
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
        /** The assembly text. */
        ASM
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
        return compile(file, source, Stage.ASM);
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
        SsaForm form = verify(module);
        if (stage == Stage.IR) {
            return IrPrinter.print(module);
        }
        if (stage == Stage.SSA) {
            return SsaPrinter.print(form);
        }
        Target target = targetOf(module);
        Selection selected = InstructionSelector.select(module, target);
        Selection allocated = RegisterAllocator.allocate(selected, target);
        return AsmEmitter.emit(module, allocated);
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
        SsaForm form = SsaBuilder.build(module);
        SsaVerifier.verify(form);
        return form;
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
