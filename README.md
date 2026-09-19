# 8086-ir

A modern, SSA-based optimizer and code generator for the Intel 8086.

`8086-ir` takes a textual, human-writable intermediate representation that
describes 8086-level computation, optimizes it with a real SSA pipeline, and
emits 8086 assembly text. It ships with its own miniature assembler so that the
generated assembly can be turned into a flat binary without depending on an
external toolchain.

The whole thing is written in Java 8, with no third-party runtime dependencies.

---

## Goals

* **Text in, text out.**
  * Input: a high-level, readable IR that a human can write and edit by hand.
    It is close to 8086 semantics but not bound to the hardware: unlimited
    virtual registers, no fixed operand constraints, explicit memory effects.
    The surface is specified in [`docs/ir.md`](docs/ir.md).
  * Output: ordinary, readable 8086 assembly (Intel syntax), the kind of code
    you would otherwise write by hand for DOS, BIOS, bootloaders or embedded
    targets.
* **Target-aware by design.**
  The optimizer is not a peephole filter guessing at the machine. Register
  constraints, flag effects, addressing modes, segmentation, instruction
  encoding choices and code layout are all described by the target layer, so
  every pass reasons about what the CPU actually does rather than about what
  the IR looks like. Passes speak about a target through the target
  description's general vocabulary — implicit and fixed registers, clobbers,
  operand constraints, flag def/use sets — rather than by naming an opcode or a
  register. The one bounded exception is the tail of the optimizer, where
  encoding-level knowledge is stated directly: at most three target-specific
  passes at the very end, just before instruction selection, clearly marked as
  belonging to one target.
* **Optimised for size, not for speed.**
  The optimizer is a real pipeline, but the objective it is scored against is
  instruction **size in bytes** and instruction **count**, not cycles. Both
  matter more on this target: a boot sector is exactly 512 bytes, a `.COM` has
  to fit under 64K, and the 8086 has no cache to fill and no pipeline to stall.
  So the transformations that shape code are the ones that make it smaller —
  fewer instructions, shorter encodings, a memory operand instead of a
  load/use/store sequence, implicit-operand forms (`cbw`, `cwd`, `lodsb`), and
  the shortest jump that reaches its label. Where two candidates are the same
  size, the target's cost estimates break the tie. Speed is a tie-break, never
  the goal. Deliberately absent: loop unrolling, inlining for speed, and
  anything else that trades bytes for cycles.
* **Self-contained.**
  A bundled micro-assembler (`asm`) parses the emitted assembly, encodes it,
  resolves labels and produces a flat binary. Same repo, same build, no NASM
  required to get from IR to bytes.
* **Verifiable.**
  SSA form is checked, not assumed. Every pass runs on IR that is verified
  before and after it. Where possible, results are validated by assembling and
  executing, not just by eyeballing diffs.

### Non-goals

* Not a C compiler front end — no language frontends are planned.
* Not a general-purpose retargetable compiler backend. The 8086 is the target;
  other 16-bit architectures are later goals (see *Future targets*), which
  exist only to keep the target boundary honest — not to make the project
  generic for its own sake.
* Not a cycle-accurate simulator, though a simple execution model is useful for
  testing.
* Not a disassembler. `asm` can decode exactly the instruction forms the
  emitter can produce, so that encoder round-trips are testable; decoding
  arbitrary 8086 machine code is not a goal, and nothing in the product path
  depends on the decoder.

---

## Future targets

The 8086 is the target of this project. The IR and the pass pipeline are meant
to be architecture-independent, however, with everything machine-specific
pushed into a target description plus an encoder. That boundary is drawn from
the first milestone, not bolted on later, so that 8086 knowledge has exactly
one home and no pass ever learns an 8086-specific fact.

Other architectures are not committed work. They are listed because they are
the eventual reason the boundary exists, and because they are a useful sanity
check on it; no abstraction is built in advance for them. A target gets added
only when there is real interest in it.

Candidate backends, in roughly decreasing order of likely usefulness:

* **Intel 80186 / 80286** — incremental extensions of the 8086 target.
* **NEC V20 / V30** — 8086-compatible with extra instructions worth using.
* **PDP-11** — a 16-bit architecture with a very different, much more regular
  operand model.
* **MSP430** — a clean, orthogonal 16-bit ISA; a good sanity check that the IR
  is not secretly 8086-shaped.
* **65C816** — 16-bit data on an 8-bit-derived core, with its own quirks.
* **TMS9900 / Zilog Z8000** — further 16-bit designs, if interest holds.

Adding any of these must require no changes to a generic pass (see `AGENTS.md`);
that is the property being tested, not a feature being promised.

---

## Implementation approach

The pipeline is a conventional one, adapted to the constraints of the target.

1. **Parse** the textual IR into a module → functions → basic blocks →
   instructions.
   Also: print it back. Parse/print round-tripping is a tested invariant.
2. **Verify** the input IR and **lower** it to machine IR: operations become
   target operations, while operands remain virtual registers and flags remain
   virtual flag registers. Lowering is where operand combinations are made
   legal, so a machine-IR instruction is always a combination the target can
   actually encode — a memory+memory `add` is split here, not later.
3. **Build SSA**: compute dominators, insert φ-nodes, promote memory to
   registers where safe. SSA is the canonical form for everything downstream,
   and what it looks like is specified in [`docs/ssa.md`](docs/ssa.md).
   Flag values are handled here too: a virtual flag register is an ordinary
   value, and when one would have to live across an instruction that defines
   those flags, SSA construction materialises it with the explicit read-flags
   operation. From that point on it *is* an ordinary value, and every generic
   pass handles it without needing to know what a flag is.
4. **Optimize**, as a sequence of verified passes. The bulk of the pipeline is
   generic: constant folding and propagation, dead code elimination, copy
   propagation, global value numbering / CSE, redundant load elimination,
   loop-invariant code motion, and condition/branch simplification using the
   flag semantics the target description supplies. Each of these knows the
   machine only through the target description's general vocabulary, so each
   stays expressible for any target.

   What runs today, in order: **constant propagation**, **dead value
   elimination**, and **unread flags** — an operation whose flags nobody reads
   stops claiming them, which is what lets the target use a form that disturbs
   them. The passes are listed in `i8086.pass.Pipeline`, and a test compares that
   list against the names written out here, so a pass added in one place and not
   the other fails the build.
5. **Run the target-specific tail.** Real machines have quirks that are not
   worth abstracting, and encoding-level knowledge is stated directly at the
   end of the optimizer, immediately before instruction selection: at most
   three target-specific passes, for the 8086 late peephole and cleanup work.
   Each belongs to exactly one target, is marked as such in the pipeline
   listing, and lives with that target rather than in the generic pass package.
6. **Instruction selection**: pick the instruction *form* — which instruction,
   which addressing mode — by size first, with the target's cost estimates
   breaking ties (*Optimised for size*, above). Byte-level encoding choices are
   deliberately **not** made here: whether a displacement is 8 or 16 bits, or
   whether an immediate needs the sign-extended form, depends on values that are
   only known once everything has been placed — a forward-referenced label's
   address, most obviously. Those belong to the assembler (step 9). Selection
   only selects: it never synthesizes a new
   instruction sequence, because lowering already guaranteed legal operand
   combinations. Virtual flag registers are absorbed here, into the implicit
   flag effects of the instructions that produce them, so the allocator deals
   in register classes only. What comes out is a form the target can encode,
   which is what makes it verifiable. The read-flags operation is selected the
   same way: the target says what it has — `LAHF` or `PUSHF` on the 8086, which
   has no `SETcc`, and `SETcc` once a target provides it.
7. **Register allocation**: graph colouring over the target's small, heavily
   constrained register file, with pre-coloured physical registers for implicit
   operands and sub-register-aware live ranges. Spilling uses frame-relative
   stack slots.
8. **Emit** assembly text for the selected target.
9. **Assemble** (optionally, in the same run): the bundled `asm` front end
   encodes instructions, choosing the shortest encoding for each form it is
   given, resolves and relaxes labels (the shortest jump that reaches its
   target), and writes a flat binary or a listing. The syntax it reads and the
   emitter writes is specified in [`docs/asm.md`](docs/asm.md).

This pass list is the description of record: adding, removing, reordering or
re-targeting a pass means updating it in the same change, and a target-specific
pass has to be visible as such here.

### Repository layout

```
src/main/java/.../ir/         IR data model, parser, printer, verifier
src/main/java/.../ssa/        dominators, phi insertion, SSA construction
src/main/java/.../pass/       generic analysis and transformation passes
src/main/java/.../target/     target descriptions, target-specific late passes,
                              8086 ISA + encodings
src/main/java/.../isel/       instruction selection and addressing modes
src/main/java/.../regalloc/   allocation, coalescing, spilling
src/main/java/.../emit/       assembly text emitter
src/main/java/.../asm/        mini-assembler: lexer, parser, encoder, decoder,
                              linker
src/main/java/.../sim/        reference interpreter for the 8086
src/main/java/.../cli/        command-line entry points
src/test/java/...             unit tests, golden tests, round-trip tests
src/test/resources/           golden files: IR samples, expected assembly/bytes
examples/                     hand-written IR samples and expected output
docs/ir.md                    the IR surface: syntax and semantics
docs/asm.md                   the assembly text: syntax and encoding rules
```

### Building and running

JDK 9 or newer to build, no third-party dependencies: `--release 8` pins the
language level and the API, so the classes produced target Java 8 and run on a
Java 8 runtime. A single batch script drives the build, including the
dependency-free test suite:

```
build.bat                                          # compile everything, run all tests
build.bat run optimize examples/hello.ir -o hello.asm
build.bat run optimize --emit ir  examples/hello.ir   # stop after the IR
build.bat run optimize --emit ssa examples/hello.ir   # stop after the SSA form
build.bat run assemble hello.asm -o hello.bin
```

`optimize` runs the pipeline and stops where `--emit` says — `ir`, `ssa` or `asm`
— and without `-o` it prints what it has instead of writing it. The two dumps are
the only way to see the middle of the pipeline, and a stage is only reached by way
of the verifications before it, so what comes out is something the compiler
accepted.

### Testing strategy

* IR round-trip: `parse(print(ir)) == ir` for every module `ir`, and printing a
  module twice yields identical text.
* Pass tests: verify SSA before and after every transformation; assert specific
  optimization behaviours, including the "must not optimize" cases.
* Assembler tests: encode each instruction form and check the exact bytes
  against known-good encodings; decode them back and compare. The decoder
  exists for this check only — it is not a general 8086 disassembler.
* End-to-end: run sample IR through the pipeline, assemble the output, execute
  it under the `sim` reference interpreter, and compare against the unoptimized
  program's observable results (registers and memory). `sim` is a test aid and
  a behavioural model of what the 8086 makes observable — it is not
  cycle-accurate, and nothing in the pipeline may depend on it.

---

## Status

The pipeline runs end to end: IR text in, assembly text out, with SSA
construction and verification in the middle. What is missing is the passes that
would read the form, and the assembler that would turn the output into bytes.

Working today:

* The IR surface of [`docs/ir.md`](docs/ir.md): parsing, printing, and
  verification, so `parse(print(ir)) == ir` and every refusal carries a position.
* The control-flow sugar of §7.2 — `.if`, `.elseif`, `.else`, `.while` —
  normalised away as it is read, into comparisons, branches and labels.
* **SSA construction and verification**, described in [`docs/ssa.md`](docs/ssa.md):
  the control flow graph, dominators and the dominance frontier, liveness, φ
  placement, and the renaming walk. Every variable is renamed — the flags
  included — and every use names the definition that reaches it. `optimize
  --emit ssa` prints the form.
* **An optimiser**: constant propagation, dead value elimination, and giving up
  flags nobody reads, run as a verified pass sequence between SSA and the back
  end. Every pass runs on a verified form and its output is verified in turn.
  Leaving SSA again is a transformation like any other, and its output — a module
  with one name per variable — is checked by the surface's own verifier before
  anything selects instructions from it. `optimize --emit ir` prints what the
  passes left.
* The assembly text of [`docs/asm.md`](docs/asm.md), and the emitter that writes
  it.
* Instruction selection and register allocation, enough to compile arithmetic on
  variables and control flow: `var`, assignments, `eval`, `expr`, `cmp`, `test`,
  `jmp`, the `jcc` family, and the operators the 8086 has forms for. A register
  allocator that does not spill, and says so; it honours an inline block's clobber
  list, keeping a value that is still to be read out of the registers the block
  destroys; when there is control flow, a value that lives across a label keeps one
  register rather than reusing it, which is right rather than clever.
* The bundled assembler is planned but not built: the assembly the emitter writes
  cannot be turned into bytes yet.

Not built yet, and refused with a reason rather than guessed at: instruction
selection for loads and stores, conversions, `setcc`, an instruction whose
operands are implicit (`mul`, `div`, a shift by a count in a register), and the
assembler. SSA construction does not yet materialise a flag value that has to
survive an instruction defining those flags, because the target does not state
its flag effects per flag yet — and nothing asks it to. The optimiser is three
passes and not the ten the pipeline describes; and while a module contains an
inline assembly block, nothing in it may be removed, because a block cannot say
what it reads yet.

Planned milestones:

1. IR definition, parser, printer, verifier, and the target description the
   rest of the pipeline reads 8086 facts from. **Done.**
2. Bundled assembler (encode + label resolution + branch relaxation) for 8086.
3. SSA construction and verification. **Done**, apart from the flag
   materialisation that waits on the target's per-flag effects.
4. Core optimization passes. **Started**: constant propagation, dead value
   elimination and unread flags; the rest of the list in *Implementation
   approach* is not written.
5. 8086 instruction selection and register allocation. **Partly done**: enough
   for arithmetic, comparisons and control flow; loads, stores and conversions
   are refused with a reason.
6. `sim` interpreter, and an end-to-end example that assembles and runs.
7. A second backend on top of the existing target description, to prove that
   the boundary holds without touching pass code.

## License

To be decided.
