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
   commit IR that has not been verified.
2. **Target knowledge lives in the target layer, except in the last passes.**
   Instruction encodings, operand constraints, register classes, flag
   semantics, addressing modes and segmentation belong to the target
   description. A generic pass must never branch on a specific opcode or
   register name; when it has to talk about a target quirk, it does so through
   the generic vocabulary the target description provides — implicit and fixed
   registers, clobbers, operand constraints, flag-def and flag-use sets. Such a
   pass is expressible for any target because it *asks* the target, not because
   it already knows the answer.

   The exception is deliberate and bounded: the last one to three passes of the
   optimizer, immediately before instruction selection, may be target-specific.
   Late peephole and cleanup work is exactly where encoding-level knowledge is
   cheapest to state, and pretending otherwise buys an abstraction nobody needs.
   Such a pass must be registered as target-specific for exactly one target,
   must be visibly marked as such in the pipeline listing, and must not be
   reachable from a target it does not belong to. The rest of the pipeline —
   SSA, the analysis and transformation passes, isel, regalloc — stays generic.
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
   `parse(print(ir)) == ir`, and the assembler's decoder and encoder are
   inverses on every encoding the emitter can produce. Break either and the
   change is wrong. The decoder exists for that check: it is a test aid, not a
   general 8086 disassembler, and no product path may depend on it.
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

---

## Build, run, and test

`build.bat` is the single entry point. It compiles `src/main/java` and
`src/test/java`, runs the test suite, and exits non-zero on failure. Keep it
working; a change that leaves `build.bat` red is not done.

```
build.bat                                   # compile everything, run all tests
build.bat run optimize examples/hello.ir -o hello.asm
build.bat run assemble hello.asm -o hello.bin
```

* Plain batch only. No PowerShell-only constructs, no downloaded tools, no
  network access during build.
* Build output goes to `build/` and is never edited by hand, never committed.
* Compile with `--release 8 -Xlint:all`, and keep the build warning-clean.
  `--release 8` pins the language level and the API together, so a newer JDK
  cannot leak a newer library method into Java 8 code, and it avoids the
  bootstrap-classpath warning that `-source 8 -target 8` produces. Suppress a
  warning only with a comment explaining why.

### The test harness is ours

There is no JUnit and no other test framework. Tests are plain classes with
static methods, collected by a small hand-written runner:

* One runner entry point that reports per-test results and returns a non-zero
  exit code if anything failed.
* Assertions are small hand-written helpers (`assertEquals`,
  `assertRefused`, ...). Keep them dependency-free and readable.
* `src/test/resources/` holds golden files: IR samples, expected assembly,
  expected instruction encodings. Tests compare against them and report a
  readable diff on mismatch.

Adding a test framework is a change to the project's dependency policy, not a
convenience refactor. Ask first.

### What a change is expected to ship with

* A new or changed pass: SSA verification on both sides, at least one case
  where it fires and one where it must not, and a pipeline registration.
* A new instruction or addressing form: exact-byte encoder test plus
  disassemble/reassemble round-trip.
* A pass-ordering or IR-shape change: end-to-end test over a sample program,
  comparing observable results against the unoptimized run.
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
regalloc/   allocation, coalescing, spilling
emit/       assembly text emitter
asm/        mini-assembler: lexer, parser, encoder, decoder, linker
sim/        reference interpreter for the 8086; end-to-end tests only
cli/        command-line entry points
```

Rules that keep this layout meaningful:

* One concern per package. If a class needs the target description *and* the
  pass framework, it is in the wrong place or does too much.
* One pass per class, implementing the pass interface, with analysis split out
  into separate analysis classes that the pass consumes.
* Lowering makes operand combinations legal; selection only selects. `isel`
  picks an instruction form for a machine-IR instruction and never synthesizes a
  new sequence. If something wants to emit instructions after lowering, the
  missing work belongs in lowering, before SSA, where it can still be optimized
  and verified.
* Virtual flag registers are absorbed at selection time, into the implicit flag
  effects of the concrete instructions. Register classes are all the allocator
  has to reason about.
* Flag values are ordinary values. The IR has exactly one explicit read-flags
  operation, and SSA construction materialises a virtual flag register with it
  whenever that flag would have to survive an instruction which defines it.
  Lowering therefore needs no liveness analysis to keep flags alive, and no
  generic pass may reintroduce a special notion of "flag" beyond the target
  description's flag-def and flag-use sets.
* New passes are registered explicitly in the pipeline, and the pass list in
  `README.md` is updated in the same change.
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
