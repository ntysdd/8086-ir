package i8086.cli;

import i8086.CompileError;
import i8086.Compiler;

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
        if (command.equals("ssa")) {
            return dumpSsa(args, first + 1, out, err);
        }
        if (!command.equals("optimize")) {
            err.println("error: unknown command '" + command + "'");
            printUsage(err);
            return EXIT_USAGE;
        }
        return optimize(args, first + 1, out, err);
    }

    /**
     * Writes the SSA form of a module, which is a dump rather than a file to keep:
     * it goes to standard output and takes no {@code -o}.
     */
    private static int dumpSsa(String[] args, int from, PrintStream out, PrintStream err) {
        if (args.length - from != 1) {
            err.println("error: expected one input file");
            return EXIT_USAGE;
        }
        String input = args[from];
        String source = read(input, err);
        if (source == null) {
            return EXIT_FAILED;
        }
        try {
            out.print(Compiler.printSsa(input, source));
            return EXIT_OK;
        } catch (CompileError refused) {
            err.println(refused.format());
            return EXIT_FAILED;
        }
    }

    private static int optimize(String[] args, int from, PrintStream out, PrintStream err) {
        String input = null;
        String output = null;
        for (int i = from; i < args.length; i++) {
            if (args[i].equals("-o")) {
                if (i + 1 >= args.length) {
                    err.println("error: '-o' needs a file name after it");
                    return EXIT_USAGE;
                }
                output = args[++i];
            } else if (input == null) {
                input = args[i];
            } else {
                err.println("error: unexpected argument '" + args[i] + "'");
                return EXIT_USAGE;
            }
        }
        if (input == null || output == null) {
            err.println("error: expected an input file and '-o OUTPUT'");
            printUsage(err);
            return EXIT_USAGE;
        }

        String source = read(input, err);
        if (source == null) {
            return EXIT_FAILED;
        }

        try {
            String assembly = Compiler.compile(input, source);
            return write(output, assembly, err) ? EXIT_OK : EXIT_FAILED;
        } catch (CompileError refused) {
            err.println(refused.format());
            return EXIT_FAILED;
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
        out.println("usage: 8086-ir optimize INPUT.ir -o OUTPUT.asm");
        out.println("       8086-ir ssa INPUT.ir              ; the SSA form, on standard output");
    }
}
