package i8086.cli;

import i8086.CompileError;
import i8086.Compiler;
import i8086.Warning;
import i8086.Warnings;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * The command line entry point: argument handling and file reading, and nothing
 * else. Everything that decides what the program means lives in {@link Compiler}
 * and below, so that it can be tested without a process and without a file.
 *
 * <p>{@code build.bat run ...} passes its whole command line through, so a
 * leading {@code run} is dropped here rather than being reassembled by batch.
 */
public final class Main {

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILED = 1;
    private static final int EXIT_USAGE = 2;

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Runs the command line. Separated from {@link #main} so that it can be tested. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        int first = args.length > 0 && args[0].equals("run") ? 1 : 0;
        if (args.length - first == 0 || isHelp(args[first])) {
            printUsage(out);
            return args.length - first == 0 ? EXIT_USAGE : EXIT_OK;
        }

        String command = args[first];
        if (command.equals("assemble")) {
            err.println("error: 'assemble' is not implemented yet; only the IR can be compiled");
            return EXIT_FAILED;
        }
        if (!command.equals("optimize")) {
            err.println("error: unknown command '" + command + "'");
            printUsage(err);
            return EXIT_USAGE;
        }
        return optimize(args, first + 1, out, err);
    }

    /**
     * Compiles a module, or stops at a stage and prints what is there.
     *
     * <p>Without {@code -o} the result goes to standard output, which is what a
     * dump wants and what makes the three stages one command rather than three.
     */
    private static int optimize(String[] args, int from, PrintStream out, PrintStream err) {
        String input = null;
        String output = null;
        Compiler.Stage stage = Compiler.Stage.NASM;
        for (int i = from; i < args.length; i++) {
            if (args[i].equals("-o")) {
                if (i + 1 >= args.length) {
                    err.println("error: '-o' needs a file name after it");
                    return EXIT_USAGE;
                }
                output = args[++i];
            } else if (args[i].equals("--emit")) {
                if (i + 1 >= args.length) {
                    err.println("error: '--emit' needs one of ir, ssa or nasm after it");
                    return EXIT_USAGE;
                }
                stage = stage(args[++i]);
                if (stage == null) {
                    err.println("error: unknown --emit '" + args[i]
                            + "'; expected ir, ssa or nasm");
                    return EXIT_USAGE;
                }
            } else if (input == null) {
                input = args[i];
            } else {
                err.println("error: unexpected argument '" + args[i] + "'");
                return EXIT_USAGE;
            }
        }
        if (input == null) {
            err.println("error: expected an input file");
            printUsage(err);
            return EXIT_USAGE;
        }

        String source = read(input, err);
        if (source == null) {
            return EXIT_FAILED;
        }

        try {
            Warnings warnings = new Warnings();
            String text = Compiler.compile(input, source, stage, warnings);
            report(warnings, err);
            if (output == null) {
                out.print(text);
                return EXIT_OK;
            }
            return write(output, text, err) ? EXIT_OK : EXIT_FAILED;
        } catch (CompileError refused) {
            err.println(refused.format());
            return EXIT_FAILED;
        }
    }

    /**
     * What the compiler had to say about the program without refusing it, on standard
     * error.
     *
     * <p>Standard error and not standard output, because the two streams carry two
     * different things: {@code optimize ... > x.asm} is how a program is written to a
     * file, and a warning in the middle of it would be an assembler error. A warning is
     * also not a failure — the program compiled — so nothing about the exit status
     * changes.
     */
    private static void report(Warnings warnings, PrintStream err) {
        for (Warning warning : warnings.all()) {
            err.println(warning.format());
        }
    }

    /** The stage a word names, or null when it names none. */
    private static Compiler.Stage stage(String word) {
        switch (word) {
            case "ir":
                return Compiler.Stage.IR;
            case "ssa":
                return Compiler.Stage.SSA;
            case "nasm":
                return Compiler.Stage.NASM;
            default:
                return null;
        }
    }

    /** Reads a file, saying what went wrong when it cannot. */
    private static String read(String input, PrintStream err) {
        try {
            return new String(Files.readAllBytes(new File(input).toPath()), UTF_8);
        } catch (IOException failure) {
            err.println("error: cannot read " + input + ": " + failure.getMessage());
            return null;
        }
    }

    /** Writes the assembly text, saying whether it worked. */
    private static boolean write(String output, String text, PrintStream err) {
        try {
            Files.write(new File(output).toPath(), text.getBytes(UTF_8));
            return true;
        } catch (IOException failure) {
            err.println("error: cannot write " + output + ": " + failure.getMessage());
            return false;
        }
    }

    private static boolean isHelp(String argument) {
        return argument.equals("-h") || argument.equals("--help") || argument.equals("help");
    }

    private static void printUsage(PrintStream out) {
        out.println("usage: 8086-ir optimize INPUT.ir [-o OUTPUT] [--emit ir|ssa|nasm]");
        out.println("       without -o the result goes to standard output");
        out.println("       8086-ir assemble INPUT.asm -o OUTPUT.bin   ; not implemented yet");
    }
}
