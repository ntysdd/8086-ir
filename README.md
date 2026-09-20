# 8086-ir

A modern, SSA-based optimizer and code generator for the Intel 8086.

`8086-ir` takes a textual, human-writable intermediate representation that
describes 8086-level computation, optimizes it with a real SSA pipeline, and
emits 8086 assembly text. It is built to ship with its own miniature assembler, so
that the generated assembly can be turned into a flat binary without depending on
an external toolchain — but that assembler is **not on the critical path**: the text
it writes is NASM's dialect, so the image comes from `nasm -f bin` today, and
[`docs/asm.md`](docs/asm.md) §1 lists the four differences between the two dialects.

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
  load/use/store sequence, implicit-operand forms (`cbw`, `cwd`, `lodsb`, and the halves of a
  register a word is built in), and
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
Each step says where it stands: **built**, **partly**, or **planned**.

1. **Parse** the textual IR into a module of items — labels, statements, data —
   and print it back. Parse/print round-tripping is a tested invariant, and the
   sugar of `docs/ir.md` §7.2 and §7.3 is normalised away here, so what the rest of
   the pipeline sees is the canonical form.
   **Built.** The basic blocks and functions the rest of this list speaks of are
   derived from those items rather than parsed into existence: the graph is read
   off the item list in step 3, and there are no functions yet at all.
2. **Verify** the input IR, and **lower** it to machine IR: operations become
   target operations, while operands remain virtual registers and flags remain
   virtual flag registers. Lowering is where operand combinations are made
   legal, so a machine-IR instruction is always a combination the target can
   actually encode — a memory+memory `add` is split here, not later.
   **Partly, and not in that shape.** Verification is built. There is no
   separate machine-IR layer: the passes work on the surface's own operations,
   which are already one operation to a statement, and lowering happens item by
   item inside selection. A second target is what would make the separation
   worth its cost, and there is no second target.
3. **Build SSA**: compute dominators, insert φ-nodes, promote memory to
   registers where safe. SSA is the canonical form for everything downstream,
   and what it looks like is specified in [`docs/ssa.md`](docs/ssa.md).
   Flag values are handled here too: a virtual flag register is an ordinary
   value, and when one would have to live across an instruction that defines
   those flags, SSA construction materialises it with the explicit read-flags
   operation. From that point on it *is* an ordinary value, and every generic
   pass handles it without needing to know what a flag is.
   **Mostly built.** Dominators, φ's and renaming are done, the flags included;
   promoting memory and materialising a flag value are not. Both wait on the same
   missing thing — the target's per-flag effects — and neither is needed yet. What the
   flags do have is the three states (§4.2), asked of a statement as well as of an
   operation: a statement says whether it makes them, gives them up, or leaves them
   exactly as they were, and only the last of those keeps the definition in front of it
   alive (`i8086.target.Target#machineWritesFlags`).
4. **Optimize**, as a sequence of verified passes. The bulk of the pipeline is
   generic: constant folding and propagation, dead code elimination, copy
   propagation, global value numbering / CSE, redundant load elimination,
   loop-invariant code motion, and condition/branch simplification using the
   flag semantics the target description supplies. Each of these knows the
   machine only through the target description's general vocabulary, so each
   stays expressible for any target.

   What runs today, in order: **constant propagation**, **load folding** — a load whose only
   reader is the comparison next to it is not a value at all, and the comparison carries the
   access (`docs/ir.md` §5.4) — **dead value elimination**, and **unread flags**, which is last
   because an operation whose flags nobody reads stops claiming them, which is what lets the
   target use a form that disturbs them. The passes are listed in `i8086.pass.Pipeline`, and a
   test compares that list against the names written out here, so a pass added in one place and
   not the other fails the build. **Four built, the rest planned.**
5. **Run the target-specific tail.** Real machines have quirks that are not
   worth abstracting, and encoding-level knowledge is stated directly in a
   target-specific pass: at most three, each belonging to exactly one target,
   marked as such in the pipeline listing, and living with that target rather
   than in the generic pass package. Where such a pass runs is the target's
   answer, and the 8086's runs **last**, after allocation: the fact cheapest to
   state there is about a register, and no value has one before the allocator has
   run. `loop` counts in `cx` and nowhere else, so what a countdown loop costs
   depends on where its counter ended up.
   **Built: three passes, the 8086's.** A constant a register already holds is not built again
   (`i8086.target.RepeatedConstants`); a load of what a register already holds, with nothing between
   that could have written memory, is not loaded again (`i8086.target.RepeatedLoads` — a value in a
   home is read out of it at every point that mentions it, and two statements in a row are one
   access); and a countdown whose flags nothing else reads loses the comparison that repeats what the
   decrement already said, and, where the counter is in `cx` and the loop is short enough to count,
   the pair becomes the machine's own `loop` (`i8086.target.CountedLoops`).
6. **Instruction selection**: pick the instruction *form* — which instruction,
   which addressing mode — by size first, with the target's cost estimates
   breaking ties (*Optimised for size*, above). Byte-level encoding choices are
   deliberately **not** made here: whether a displacement is 8 or 16 bits, or
   whether an immediate needs the sign-extended form, depends on values that are
   only known once everything has been placed — a forward-referenced label's
   address, most obviously. Those belong to the assembler (step 9). Selection
   only selects: it either takes a form the target lists or substitutes a
   sequence the target declares, and it never composes one of its own. Virtual
   flag registers are absorbed here, into the implicit flag effects of the
   instructions that produce them, so the allocator deals in register classes
   only. What comes out is a form the target can encode, which is what makes it
   verifiable. The read-flags operation is selected the same way: the target says
   what it has — `LAHF` or `PUSHF` on the 8086, which has no `SETcc`.
   **Built for arithmetic, comparisons, control flow, loads, stores, the machine's multiply and
   divide, and two bytes put together into one word** — the last one being the shape a program writes
   as `(high shl 8) + low` over two bytes it has widened, which on this machine is two moves
   into the halves of `ax` (`i8086.target.combineBytes` and the recognizer beside it in
   selection). The high half may be shifted by a statement of its own — `h = eval(w shl 8)`
   then `t = expr(h | v)` says what `t = expr((w shl 8) | v)` says — and then the shift goes with
   the widenings it reads, where nothing reads it and nothing reads the flags it leaves;
   **conversions, `setcc` and the target-provided
   operations of `docs/ir.md` §11 are not there yet**, and a byte access is refused
   with the reason.
7. **Register allocation**: graph colouring over the target's small, heavily
   constrained register file, with pre-coloured physical registers for implicit
   operands and sub-register-aware live ranges. Spilling uses frame-relative
   stack slots.
   **Built, and simpler than that.** The graph is real: a value is a node, two values
   alive at the same point are an edge, and the registers are the colours, which are
   asked for one at a time from the target's register class. It is coloured greedily
   in a perfect elimination ordering, so the number of registers a program needs is
   the number it uses and the number it cannot have is said plainly. What is *not*
   here is spilling — needing more registers than the machine has is a hard error
   (`docs/ir.md` §8.2), which is a promise rather than a shortfall — and
   pre-coloured nodes, which are not needed: what the machine insists on is handled
   by the copies its own sequences are written with. What the target may do is
   *ask*: a register it would be a byte cheaper holding a value in — this one counts
   a loop down in `cx` and nowhere else — is tried before the allocator's own order
   and the order is what is left when it does not fit. That is a preference and not
   a pre-coloured node: nothing is forced, and a request the value cannot take, or
   one that would cost more than it saves, is simply not made. That an address has three
   registers to live in rather than six is the one place a value's class is narrower
   than the machine when the value is a word; a byte value is narrower still:
   it lives in the low half of one of the four registers that has a half, so `si`
   and `di` cannot hold it (`docs/ir.md` §3.2), and a register the allocator never
   allocates — `sp`, `bp` and the segment registers — is what `movreg` writes instead.
   An access is the width of the value it moves, so a byte load is one byte and names
   `al` rather than `ax`.
8. **Emit** assembly text for the selected target. **Built.**
9. **Assemble** (optionally, in the same run): the bundled `asm` front end
   encodes instructions, choosing the shortest encoding for each form it is
   given, resolves and relaxes labels (the shortest jump that reaches its
   target), and writes a flat binary or a listing. The syntax it reads and the
   emitter writes is specified in [`docs/asm.md`](docs/asm.md).
   **Planned, and the largest thing missing**: the assembly this compiler writes
   cannot yet be turned into bytes, which makes it a listing rather than a
   program.

This pass list is the description of record, and so is the state written beside
each step: adding, removing, reordering or re-targeting a pass means updating it
in the same change, and a target-specific pass has to be visible as such here.

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
build.bat run optimize --emit ir   examples/hello.ir   # stop after the IR
build.bat run optimize --emit ssa  examples/hello.ir   # stop after the SSA form
nasm -f bin hello.asm -o hello.com                     # the assembler, until we have one
```

`optimize` runs the pipeline and stops where `--emit` says — `ir`, `ssa` or `nasm`
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
  cycle-accurate, and nothing in the pipeline may depend on it. Neither the
  assembler nor `sim` exists yet, so end-to-end tests currently stop at the
  assembly text, compared exactly.

---

## Status

The pipeline runs end to end: IR text in, assembly text out, with SSA construction and
three optimization passes — and the form is what the back end reads, rather than
something it is turned back into first. What is missing is the assembler that would
turn that text into bytes, and the parts of the surface and the instruction set
listed below.

Working today:

* **The IR surface** of [`docs/ir.md`](docs/ir.md): parsing, printing and
  verification, so `parse(print(ir)) == ir` and every refusal carries a position —
  together with the sugar, normalised away as it is read: the control flow of §7.2,
  the instruction-shaped statements of §7.3 (`add s, 1` is `s = eval(s + 1)` written
  the machine's way), and the unary minus of §5.5. **Almost nothing is reserved**:
  any word can be a variable, except a name beginning with a dot, which is how the
  sugar is spelled (§3.1) — and the printer writes `$` in front of every name the
  author chose, not only the ones that look like a word of the surface, so the
  canonical form is never ambiguous and never depends on the compiler's word list
  (§3.1, §3.1.1).
* **SSA construction and verification**, described in
  [`docs/ssa.md`](docs/ssa.md): the control flow graph, dominators and the dominance
  frontier, liveness, φ placement, and the renaming walk. Every variable is renamed
  — the flags included — and every use names the definition that reaches it. It does
  not stop there: instruction selection and register allocation read the form, so the
  instructions they work on name versions, and a name is a life. What joins two names
  into one is a φ — the register is what carries a value along each path, since there is
  no copy at a merge ([`docs/ssa.md`](docs/ssa.md) §8) — and a copy whose source dies at
  it. `optimize --emit ssa` prints the form.
* **An optimiser**: constant propagation, dead value elimination, and giving up
  flags nobody reads, in that order. Every pass runs on a verified form and has its
  output verified in turn. `optimize --emit ir` prints what the passes left, in the
  surface — which is a dump, because the back end no longer needs it.
* **Data, and padding that reaches a layout**: `db`/`dw`/`dd` inline where they sit
  — including a `dw` list of labels, which is a jump or vector table — and
  `pad N [, fill]` / `pad to N [, fill]` for bytes that exist in the image and
  mean nothing: a reserved buffer, a NOP sled, and the 510 bytes before a boot
  sector's `dw 0xAA55` (`docs/ir.md` §10.2, §10.3). `pad to` is resolved by the
  assembler, which is the only thing that knows how long the code before it is.
* **An inline assembly block** that declares the registers it destroys, may name
  labels of its own for a retry loop, and may contain anything the machine has
  (`docs/ir.md` §9, `docs/asm.md` §3, §4).
* **Machine statements the target provides**: `int 0x13`, `hlt`, `cli`, `sti`, `nop`,
  `iret`, each optionally saying what it destroys. They exist because a block is
  opaque and these are not: an interrupt with a declared clobber list leaves the
  rest of the module optimisable, and a value may live across it
  (`docs/ir.md` §11).
* **A statement that is an interface can be given its registers**, with a `with` clause:
  `int 0x13 clobbers(ax, bx, cx, dx) with ah = 0x42, dl = 0x80, si = $dap` becomes the four
  instructions an assembly author would write, inside one item — so nothing is pinned and the
  statement stays as optimisable as any other (`docs/ir.md` §11).
* **The machine's own registers**: `movreg ds, 0`, `movreg ss, 0`,
  `movreg sp, 0x7C00`, `movreg ds, cs`, `movreg bp, 0x1000` — the registers a value cannot
  live in, which is what makes a standalone write to one safe, in the sequence the machine
  needs for each (a segment register takes no immediate, so an immediate goes through `ax`,
  while a value that is already in a register goes straight in). It is a
  statement of its own rather than an assignment, because a name in the position an
  assignment writes cannot say whether it means the machine's register or a variable of that
  name — which is what keeps `ds` an ordinary name. Read the other way round, the same word
  gets a register *out*: `movreg drive, dl` is the drive number a BIOS hands a boot loader at
  entry, and what it gives back is what the machine left there — so no value of the
  compiler's may be in that register up to the read, and a read of a register the compiler's
  own arithmetic has already written is refused rather than answered with it
  (`docs/ir.md` §8.1).
* **A far jump**, `jmp 0x0000:0x7E00`, which is how a boot loader hands control to a
  kernel — and it is a statement because the compiler then knows nothing after it
  runs (`docs/ir.md` §7.1).
* **Instruction selection and register allocation**, enough to compile arithmetic
  on variables and control flow: `var`, assignments, `eval`, `expr`, `cmp`, `test`,
  `jmp`, the `jcc` family, loads and stores of a byte or a word, narrowing a value to its
  low byte or its low word, widening a byte with `xor ah, ah` or `cbw` — the sequences this
  machine needs where a 386 says `movzx` and `movsx` — and `*`, `/` and `%` signed and
  unsigned. The smallest instruction is taken where the flags allow it: a comparison with
  zero is a test of the operand against itself, and a zero is built with `xor r, r` rather
  than moved wherever nothing can read the flags afterwards — two bytes instead of three in
  both cases, and never where they are still wanted ([`docs/ir.md`](docs/ir.md) §4.2).
  `volatile` is honoured: a read marked volatile happens even when nothing
  uses its value, while a plain read nobody uses is removed.
* **A target that says what its instructions do to registers**: which registers an
  instruction destroys (`mov cl, n` writes one no value was given, `mul` leaves half
  its answer in `dx`, and writing `cl` counts as writing `cx`), which registers an
  address may live in, and what to expand when the machine insists on a register of
  its own. The allocator does not spill — too many live values is a hard error, which
  is the promise [`docs/ir.md`](docs/ir.md) §8.2 makes, and it is decided by colouring
  a graph rather than guessed at — and it drops the copies of a register into itself
  that turn out to be unnecessary, which are the ones a copy whose source dies at it
  makes unnecessary.
* **The assembly text** of [`docs/asm.md`](docs/asm.md), and the emitter that writes
  it — in the dialect NASM reads, so that `nasm -f bin` turns it into the image. The
  four differences from our own dialect, which is what an inline block is written
  in, are the whole of the translation: `pad to 510` comes out as
  `times 510-($-$$) db 0`. Every name the author chose carries the `$` that says it
  is a symbol, because in that language a bare name spelled like a register is the
  register. An assembler of our own is still planned and still not written, but
  nothing waits on it.
* **A home in memory for a variable that will not fit in a register**: `var left: u16 in
  tries` gives the value bytes to wait in, and the allocator uses them only when no
  register is left — a value that fits in one never touches them, so a home costs a
  program that does not need it nothing. A value in a home is loaded where it is read
  and stored where it is written, through a register picked for that access — and picked
  so that the move the access would otherwise need is a register moved into itself: a
  clause that wants the value in `dl` has it loaded straight into `dl`, and a value defined
  by a copy into its cell names the register the copy reads. When that register is taken, a
  value the program gave a home to moves into it to make room
  ([`docs/ir.md`](docs/ir.md) §3.1.2).

Not built yet, and refused with a reason rather than guessed at: a widening into a value
wider than a register (the answer is
two of them, and nothing in the back end can name a pair), and a value wider than a register
anywhere else — `var x: u32` followed by `x = 0` is refused rather than silently truncated to
its low half, because a word operation on the low half of a four-byte value is not the value
the program thinks it has ([`docs/ir.md`](docs/ir.md) §3.1.2). `setcc`,
a load inside an arithmetic operand, and the target-provided operations of
[`docs/ir.md`](docs/ir.md) §11. The mode that keeps a home current, `writethrough`, is
refused until every definition writes those bytes — a wrong answer nobody is told about
is what a refusal replaces — and a variable that is not the width of a register cannot
have a home used for it, because moving a value in and out of one is a whole register's
worth of access ([`docs/ir.md`](docs/ir.md) §3.1.2). Three things the
pipeline names are also absent:
materialising a flag value that has to survive an instruction defining those flags,
promoting memory to values, and any target-specific pass. And two deliberate
retreats, each with the missing piece named: nothing may be removed from a module
containing an inline assembly block, because a block cannot say what it reads yet
(§9), and no load is reusable, because nothing yet says when two accesses are the
same memory (§3.4).

One thing the compiler says without refusing anything: a **warning**. It goes to
standard error and never into the program, so `optimize ... > x.asm` writes nothing but
assembly, and the exit status does not change. There is one so far — a store into bytes
that more than one variable calls its home, which is written but not promised to stay
([`docs/ir.md`](docs/ir.md) §3.1.2).

Planned milestones:

1. IR definition, parser, printer, verifier, and the target description the
   rest of the pipeline reads 8086 facts from. **Done.**
2. Bundled assembler (encode + label resolution + branch relaxation) for 8086.
   **Not started, and deliberately not on the critical path**: the emitted text is
   NASM's dialect and `nasm -f bin` produces the image today, so what is missing is
   self-containment rather than a working pipeline.
3. SSA construction and verification. **Done**, apart from the flag
   materialisation that waits on the target's per-flag effects.
4. Core optimization passes. **Started**: constant propagation, dead value
   elimination and unread flags; the rest of the list in *Implementation
   approach* is not written.
5. 8086 instruction selection and register allocation. **Partly done**: enough
   for arithmetic, comparisons, control flow, multiplication and division, and
   16-bit loads and stores, and byte-wide ones; conversions are refused with a
   reason.
6. `sim` interpreter, and an end-to-end example that assembles and runs.
7. A second backend on top of the existing target description, to prove that
   the boundary holds without touching pass code.

## License

To be decided.
