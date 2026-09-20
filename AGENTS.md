# AGENTS.md

Instructions for AI coding agents working in this repository. Read this before
changing code. `README.md` describes what the project is and why; this file
describes how to work on it without breaking it.

---

## Non-negotiable invariants

These hold for every change, with no exceptions and no "just for now".

1. **SSA is verified, not assumed.**
   Every transformation runs `verify()` on its input and on its output. A pass
   that cannot state and check its own pre/postconditions is not finished. Never
   commit IR that has not been verified. The input IR is not SSA and has its own
   verifier (well-formedness, widths, definedness, positions); from SSA
   construction onward, every transformation verifies SSA on both sides.
2. **Target knowledge lives in the target layer, except in the last passes.**
   Instruction encodings, operand constraints, register classes, flag
   semantics, addressing modes and segmentation belong to the target
   description. A generic pass must never branch on a specific opcode or
   register name; when it has to talk about a target quirk, it does so through
   the generic vocabulary the target description provides — implicit and fixed
   registers, clobbers, operand constraints, flag-def and flag-use sets. Such a
   pass is expressible for any target because it *asks* the target, not because
   it already knows the answer.

   The exception is deliberate and bounded: at most three passes of the back end
   may be target-specific, and each of them runs where the facts it needs exist.
   For the 8086 that is *after* allocation rather than before instruction
   selection, because the fact cheapest to state there is about a register: a
   value has none until the allocator has run, and what an instruction costs can
   depend on which register it got. Late peephole and cleanup work is exactly
   where encoding-level knowledge is cheapest to state, and pretending otherwise
   buys an abstraction nobody needs. Such a pass must be registered as
   target-specific for exactly one target, must be visibly marked as such in the
   pipeline listing, and must not be reachable from a target it does not belong
   to. The rest of the pipeline — SSA, the analysis and transformation passes,
   isel, regalloc — stays generic.
3. **No side effect may be lost or reordered.**
   Memory, flags, segment registers, I/O ports and interrupts are observable.
   An optimization may only remove or move an effect when the IR proves that
   nothing can observe the difference. Volatile accesses are never removable,
   never duplicated, never reordered across other volatile accesses.
4. **Every optimization needs a "must not optimize" test.**
   A pass is defined as much by what it refuses to do as by what it does. If you
   cannot write a test where the transformation must *not* fire, the
   transformation is under-specified.
5. **Round-trips are invariants.**
   `parse(print(ir)) == ir`, which is the one that exists today and holds for every
   module in the tree. The other is a rule for the future: an assembler of our own would
   have an encoder and a decoder, and they would have to be inverses on every encoding
   the emitter can produce. Break either and the change is wrong. Such a decoder is a
   test aid, not a general 8086 disassembler, and no product path may depend on it.
6. **Output is deterministic.**
   Same input, same bytes, every run. Never let hash iteration order reach
   output, error messages, or pass scheduling. Use insertion- or
   sort-ordered collections wherever order is observable, and sort before
   printing.
7. **Unsupported input is a hard error.**
   No silent fallback, no best-effort downgrade, no "emit something plausible".
   Errors carry a source position (file, line) and a clear message.
8. **No new dependencies.**
   Not for runtime, not for tests, not for building. The JDK is the whole
   toolbox. See the Java 8 rules below.

---

## Java 8, and only Java 8

The source and target level is Java 8, pinned with `--release 8` so that the
language level *and* the platform API are both Java 8. Modern syntax is not
"nicer", it is a compile error and a broken build for users on older JDKs.
`--release` needs JDK 9 or newer to build; the classes it produces are Java 8
and must run on a Java 8 runtime.

Not available, and therefore not allowed:

* `var`
* records, sealed types, pattern matching, `instanceof` patterns
* switch expressions, text blocks, `yield`
* `List.of`, `Set.of`, `Map.of`, `Map.entry`, `Map.copyOf`
* `Optional.isEmpty`, `Stream.toList`, `Stream.ofNullable`
* `String.strip`, `isBlank`, `repeat`, `lines`, `formatted`
* `Files.readString`, `Files.writeString`, `Path.of`
* private interface methods, `List.copyOf`

Available and welcome: lambdas, method references, streams, `Optional`,
`java.time`, default and static interface methods, `try`-with-resources.

Other Java-level rules:

* No mutable global state. Analysis results are passed explicitly or cached
  inside a per-run context object, never in `static` fields.
* No reflection, no `Class.forName` dispatch, no annotation-based registries.
  Explicit registration keeps the pipeline readable and greppable.
* Keep command-line entry points thin. All real logic lives in library code
  that a test can call directly without spawning a process.
* **Strings are not data structures.** A set of names or characters is never
  packed into one string and searched with `contains`/`indexOf`, and fields are
  never recovered by splitting a string on a separator. Both look like a
  membership test and are not one:
  * a window taken from the input can span the packed string's separators and
    match text that is not an element, and
  * the empty string is a substring of every string, so an empty window always
    matches.
  Use a `switch`, an explicit comparison, or a typed collection instead. A
  `switch` is strictly better even for single characters: the compiler rejects a
  duplicated `case`, while a duplicated element in a packed string is invisible.
  (Iteration order of a collection is still subject to invariant 6.)

  This is not hypothetical. The first version of the tokenizer packed its
  two-character punctuation into `"<= >= == !="` and searched it, and so:
  `=` followed by a space matched the `= ` inside `<= ` and was lexed as one
  two-character token, and a closing `)` at the end of the input matched the
  empty string and swallowed the end of the input.

---

## Build, run, and test

`build.bat` is the single entry point. It compiles `src/main/java` and
`src/test/java`, runs the test suite, and exits non-zero on failure. Keep it
working; a change that leaves `build.bat` red is not done.

```
build.bat                                   # compile everything, run all tests
build.bat run optimize examples/hello.ir -o hello.asm
nasm -f bin hello.asm -o hello.bin          # not ours yet: see invariant 5
```

* Plain batch only. No PowerShell-only constructs, no downloaded tools, no
  network access during build.
* Build output goes to `build/` and is never edited by hand, never committed.
* Compile with `--release 8 -Xlint:all,-options`, and keep the build
  warning-clean.
  * `--release 8` pins the language level and the API together, so a newer JDK
    cannot leak a newer library method into Java 8 code, and it avoids the
    bootstrap-classpath warning that `-source 8 -target 8` produces.
  * `options` is the only lint that is off, and it is off deliberately. JDK 9
    and later warn that source/target 8 is obsolete; that warning is about the
    choice above rather than about the code, and leaving it on would make
    "warning-clean" unattainable. `build.bat` repeats the reason next to the
    flag.
  * Suppress a warning in source only for the line it applies to, and only with
    a comment explaining why.

### The test harness is ours

There is no JUnit and no other test framework. Tests are plain classes with
static methods, collected by a small hand-written runner:

* One runner entry point that reports per-test results and returns a non-zero
  exit code if anything failed.
* Assertions are small hand-written helpers (`assertEquals`,
  `assertRefused`, ...). Keep them dependency-free and readable.
* Goldens live in the tests as strings, next to the case they belong to, and a
  mismatch says what was expected and what was found. There is no golden-file
  directory: an IR sample worth a file of its own belongs in `examples/`, which is
  where `mbr7.ir` sits next to the hand-written `mbr7.hand.asm` kept for the size
  comparison.

Adding a test framework is a change to the project's dependency policy, not a
convenience refactor. Ask first.

### What a change is expected to ship with

* A new or changed pass: SSA verification on both sides, at least one case
  where it fires and one where it must not, and a pipeline registration.
* A new instruction or addressing form: the assembly the emitter writes for it,
  pinned in a golden, and the size `nasm` gives it, written down where the size is
  claimed. The byte-level encoder test and the disassemble/reassemble round-trip wait
  on an assembler of our own (invariant 5).
* A pass-ordering or IR-shape change: an end-to-end test over a sample program,
  pinning what comes out — the assembly, exactly — so that the change is visible where
  it matters and a later pass cannot quietly undo it.
* Anything touching error paths: a test for the error, including its position
  information.

---

## Where code goes

```
ir/         IR data model, parser, printer, verifier
ssa/        dominators, phi insertion, SSA construction
pass/       generic analysis and transformation passes
target/     target descriptions, target-specific late passes, ISA + encodings
isel/       instruction form selection and addressing modes (no lowering)
regalloc/   allocation, and the copies of a register into itself that turn out
            to be unnecessary
emit/       assembly text emitter
asm/        the assembly text as data: its tokens, the instruction model, how it
            prints, and the dialect it is written in
cli/        command-line entry points
```

Rules that keep this layout meaningful:

* One concern per package. If a class needs the target description *and* the
  pass framework, it is in the wrong place or does too much.
* One pass per class, implementing the pass interface, with analysis split out
  into separate analysis classes that the pass consumes.
* Lowering makes operand combinations legal; selection only selects. The two
  are allowed to replace an operation with instructions in exactly one way each:
  * **Lowering** turns an operation the target cannot express into one it can:
    operand-shape legalisation, wide operations split into narrow ones, far
    pointer arithmetic written out. It happens before SSA, so what it produces
    is optimised and verified like any other code.
  * **Expansion** substitutes a fixed, target-declared instruction sequence for
    one operation that has no single instruction, and may happen at selection
    time. `setcc` on a target without `SETcc`, and materialising a flag value,
    are the examples. The sequence comes from the target description, so `isel`
    substitutes a declared expansion rather than choosing freely: it is a
    lookup, not code generation, which is what keeps its output verifiable.
  * Neither may invent the other's job. If something wants to emit instructions
    for an operand combination, that is legalisation and belongs in lowering.
* Virtual flag registers are absorbed at selection time, into the implicit flag
  effects of the concrete instructions. Register classes are all the allocator
  has to reason about.
* Flag values are ordinary values, and the surface has an explicit way to turn
  one into a value (`docs/ir.md`, the `setcc` family). SSA construction
  materialises a virtual flag register with it whenever that flag would have to
  survive an instruction which defines it. Lowering therefore needs no liveness
  analysis to keep flags alive, and no generic pass may reintroduce a special
  notion of "flag" beyond the target description's flag effects, which are
  three-state: defined, undefined, or preserved.
* New passes are registered explicitly in the pipeline, and the pass list in
  `README.md` is updated in the same change.
* `docs/ir.md` is the description of record for the IR *surface* — the syntax and
  semantics a person writes and reads. A new IR construct is proposed and
  approved there before it is implemented, and `README.md` links to it rather
  than restating it.
* The 8086 is *the* target. Everything else in README's *Future targets* is a
  later goal, and is never a reason to build abstraction ahead of need. The
  target-description boundary exists from the first milestone so that 8086
  knowledge has one home, not so that generality can be speculated about.
* Adding a second target must not require editing a generic pass. If it does,
  the abstraction is wrong: fix the abstraction rather than special-casing. This
  is a correctness property of the boundary, not a promise to ship other
  targets. A new target may of course bring its own target-specific late passes;
  those live under the target that owns them, never in `pass/`.
* `sim/` is a test aid, not a product. It models exactly what the 8086 makes
  observable — registers, memory, flags, ports, interrupts — and nothing more;
  it is not cycle-accurate. No pass, selector, allocator or emitter may depend
  on it, and no target semantics may live in it that belong in `target/`.

---

## Working discipline

* Only modify files inside this repository. Build artifacts under `build/` are
  disposable; everything else is not.
* Keep diffs minimal and localized. Do not reformat, reorder imports, or rename
  unrelated code in the same change.
* Do not run `git add` or `git commit` unless the user asks for it. Read-only
  git commands are fine.
* Ask before changing: IR syntax, the pass pipeline order, public interfaces,
  the build entry point, or the dependency policy.
* Do not invent IR constructs. If a construct is missing, say so and propose it
  instead of smuggling the information through comments or naming conventions.
* Do not implement ahead of the current milestone. Suggestions about future work
  are welcome as notes, not as half-finished code.
* When a design decision changes, update `README.md` in the same change. The
  README is the description of record; if it disagrees with the code, that is a
  bug in the change.
* Prefer reading the existing code over assuming a convention. Introduce a new
  abstraction when there is a second real use case, not a hypothetical one.
