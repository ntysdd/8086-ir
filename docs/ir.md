# The 8086-IR surface

Status: **draft**. This document is the description of record for the IR
*surface* — the syntax and semantics a person writes and reads. `README.md`
says what the project is and why; `AGENTS.md` says how to work on it without
breaking it; this document says what the thing in the middle looks like.

A construct is proposed here and approved here **before** it is implemented
(`AGENTS.md`). Every claim below carries one of three marks:

* **[decided]** — agreed; implementable as written.
* **[proposed]** — designed, spelled out, but not yet confirmed; may change.
* **[open]** — named and known to be missing, not designed yet.

---

## 1. Scope of v1

Targets: DOS `.COM` programs, boot sectors and bare metal, and BIOS option ROMs.
All three mean no OS runtime, a single-segment layout with a fixed origin, an
entry point, and register-based interfaces to BIOS/DOS — which is what the
inline assembly escape hatch is for.

Out of scope for v1: floating point and the 8087; aggregates (structs, arrays,
unions); 64-bit arithmetic; dynamic memory; a module/link model beyond a single
segment. Integers, pointers, flags, ports, interrupts and inline assembly are
the whole surface.

## 2. Two rules to test every addition against

### 2.1 The spine — [decided]

> The IR knows the target's **memory world** — segments, near and far pointers,
> ports, flags — and refuses to know the target's **register file** or
> instruction encodings.

This is the test for every future addition. Segmentation, ports, flags and
interrupts are *why* anyone writes 8086 code, so hiding them would make the IR
useless. The register file is exactly what the pipeline exists to manage, so
exposing it would make the IR pointless.

Concretely, this is what the surface buys over writing assembly: no `AX`/`AL`/`AH`
aliasing, no operand-order rules, no hand-juggled temporaries, no hand-preserved
`FLAGS`, no repeated address computation written out, and 32-bit arithmetic
without spelling out the `ADC` chain.

### 2.2 Static errors are the compiler's job; dynamic faults are the
programmer's — [decided]

The compiler rejects, at compile time and with a source position, everything it
can *prove* is meaningless: reading a flag whose value is undefined, operands of
mismatched width, an operand shape the target cannot encode, a far pointer in
arithmetic.

It does not insert run-time checks for conditions it cannot decide. Quotient
overflow and division by zero are the hardware's contract (`#DE`) and the
programmer's responsibility: this is assembly, and every fault the hardware
reports is a fault the program asked for. The same goes for every other fault a
machine instruction can raise.

The compiler's own obligation is therefore the narrow one: **it never introduces
a fault the program did not already contain.** That single sentence is the whole
content of the non-speculation rule in §5.5, and it is the reason that rule
exists.

## 3. Values, variables and memory

### 3.1 Variables are virtual registers — [decided]

`var x: u16` introduces a mutable **virtual register**. Reading it is not a
memory access. Whether the allocator keeps it in a register or spills it to the
frame is invisible at this level.

**Data labels are memory.** `msg:` denotes an address — a near pointer constant.

This distinction is load-bearing. It is what makes `expr` pure (§5.2), and it is
what makes `mov dx, offset msg` meaningful inside an inline assembly block.

The input is not SSA. Variables are mutable and are written more than once; SSA
construction renames them.

### 3.2 Widths and signedness — [decided]

`u8`, `u16`, `u32`, `i8`, `i16`, `i32`. No `u64`.

* **No implicit promotion.** The operands of one operation must have the same
  width; changing width is an explicit conversion.
* Signedness belongs to the variable or value. Operations follow it, and
  individual instructions may override it (§4.4, §5.5). A same-width signedness
  change costs nothing and needs no special syntax; a width change always does
  (§3.5).
* **Literals are untyped.** The `1` in `eval(a + 1)` takes its width from
  context, the way an assembler immediate does. Literals are not a second type
  and do not violate the same-width rule.

### 3.3 Pointers — [decided]

* A **near pointer** is a `u16` byte offset. The segment comes from the default
  or from an explicit override.
* A **far pointer** is a `u32` seg:offset pair, and it has **no arithmetic in
  expressions**. Adding to a far pointer is not a `u32` addition, because the
  offset wraps inside its segment and has to be normalised: `0xFFFE + 4` is
  `offset 0x0002` with `seg += 0x1000`. Far pointers are split, joined and used
  as memory operands; their arithmetic is written out explicitly.

### 3.4 Memory operands — [decided syntax, asm-idiomatic]

```
x        = [p]              ; load; width comes from x
x        = es:[p]           ; segment override, written the way MASM writes it
[p + 2]  = x                ; byte-offset arithmetic
x        = byte [p]         ; explicit width when the target cannot imply it
x        = volatile [p]     ; volatile load — always a statement of its own
```

* Width comes from the assignment target whenever it can, and an explicit
  `byte`/`word`/`dword` prefix states the width of the *access* when the target
  cannot imply it. The two must agree: `x: u8 = byte [p]` is fine, while loading
  a byte into a `u16` is a width change and needs an explicit conversion
  (§3.2), not a wider load. `[decided]` the prefixes; conversions are §3.5.
* `volatile` loads and stores are never nested inside `eval(...)` or
  `expr(...)`. Keeping them out of expressions is what keeps "volatile is never
  removed, duplicated or reordered" entirely out of reach of expression
  optimisation, instead of relying on every optimisation to remember it.

### 3.5 Conversions — [decided]

Width changes are written as conversions. Signedness changes are not written at
all.

```
x = movzx y      ; widen, zero-extending:  u8→u16, u16→u32
x = movsx y      ; widen, sign-extending:  i8→i16, i16→i32
x = byte y       ; narrow to the low byte
x = word y       ; narrow to the low word
t = x            ; same width, other signedness: a free reinterpretation
```

* **An assignment's two sides must have the same width.** A width change is
  always spelled as one of the conversions above; there is no implicit widening
  or narrowing anywhere.
* **Signedness may differ across the two sides of an assignment.** It changes no
  bits and emits no instruction; it only changes how later operations read the
  value. So the way to reinterpret is to let the value live in a variable of the
  type you mean.
* There is deliberately **no cast syntax**. A C-style cast would hide the fact
  that widening on the 8086 is real instructions — `xor ah, ah` for `movzx`,
  `cbw` or `cwd` for `movsx` — and this surface keeps cost visible.
* `movzx` and `movsx` are the names an assembly programmer already types, and
  they state *which* extension happens instead of making the reader derive it
  from the source type.
* Narrowing reuses the `byte`/`word`/`dword` vocabulary of memory operands
  (§3.4), which is the MASM type-operator idiom: `byte x` is how MASM says "the
  low byte of x".
* On the 8086 both extensions are **expansions** in the sense of `AGENTS.md`:
  `MOVZX` and `MOVSX` only arrived with the 386.

## 4. Flags

### 4.1 `flags` is an ordinary variable — [decided]

`flags` is a mutable variable, like `x`. Arithmetic, logic, shifts, `cmp` and
`test` write it; conditions read it. SSA construction renames it like any other
variable, and materialises it — through a target-declared expansion — when a
flag value has to survive an instruction that defines those flags. The user
never writes `LAHF`, `SAHF` or `PUSHF` by hand.

### 4.2 Flag effects are three-state and belong to the target — [decided]

For every operation, the target description states per flag whether it is
**defined**, **undefined**, or **preserved**. Three examples of why each state
matters:

* `INC` and `DEC` preserve `CF`. So `ADD r, 1` may only become `INC r` when `CF`
  is dead. With flags as values this is an ordinary dead-value check, not a trap
  waiting for a bad day.
* `SHL` leaves `AF` **undefined** on the 8086. An undefined flag may not be
  propagated as though it had a value.
* `MOV` and loads do not touch flags at all.

### 4.3 Undefined flags — [decided]

`expr(...)` leaves `flags` undefined, and the IR must be able to say so.
**Reading a flag whose value is undefined is a hard error** carrying a source
position. It is never a silent branch in an arbitrary direction.

### 4.4 Condition mnemonics state the condition — [decided]

```
cmp  x, y
jc   L        ; reads the current flags
ja   L        ; unsigned above
jg   L        ; signed greater
```

Because the mnemonic names the condition exactly, branches need no signedness
inference at all — the assembler mnemonic *is* the override.

Turning a flag into a value uses the `setcc` family:

```
c = setc      ; c is 0 or 1
c = setnz
```

On the 8086, which has no `SETcc`, this is a target-declared expansion. Note
that `seto` expands differently from `setc`: `LAHF` does not carry `OF`.

**[open]** the spelling for naming an older flag value (`jc(f) L` and the
capture form), and how much of `lahf`/`sahf`/`pushf` is exposed directly.

## 5. The two expression forms

All arithmetic is written through one of two forms. There is no bare
`s = a + b`.

### 5.1 `eval(...)` — do it as written — [decided]

```
s = eval(a + b)     ; ≡  mov s, a ; add s, b
a = eval(a + b)     ; ≡  add a, b          (the move is elided)
eval(a + b)         ; compute, discard the value, leave the flags defined
```

* `eval` follows program order, and the flags it leaves are **defined**: they are
  the flags the naive sequence of instructions would leave.
* It may carry memory operands.
* Consequence: while those flags are live, folding, reassociation and strength
  reduction are constrained. Replacing `MUL` with `SHL` changes the flags, so it
  is legal only when the flags are dead. Folding is legal whenever the resulting
  flag bits can be reproduced (§4.2) — which, on the 8086, generally means only
  when the flags are dead.

### 5.2 `expr(...)` — a value, and the optimiser's business — [decided]

```
s = expr((a + b) * c)
```

* Operands are **variables only**. No memory operands, no loads, no `volatile`.
* Same width throughout, `u8` included.
* It reads no flags, and it leaves `flags` **undefined**.
* It is free to reassociate, CSE, duplicate, delete, strength-reduce and
  reorder: it has no observable effect beyond its result. The single exception
  is an operation that can fault — see §2.1 and §5.5; a trap may not be
  introduced into an execution that would not have reached it.
* **A variable whose address has been taken may not appear in `expr`.** At that
  point it is a memory object rather than a register, and its value can be
  observed through the address. This is also what keeps the guarantee intact
  after spilling: a spill slot is private and unaliased, so nothing can observe
  it.

### 5.3 Bare assignments — [decided]

Assignment that computes nothing stays bare. These are moves and loads:

```
x = y             ; MOV
x = 5             ; immediate
x = [p]           ; LOAD
x = volatile [p]  ; volatile LOAD, a statement of its own
```

### 5.4 Nesting — [decided]

`expr` and `eval` never nest inside one another.

### 5.5 Operator set — [decided]

* In `expr`: `+ - * / % & | ^ ~`, and the shifts and rotates that do not read
  the carry (`shl shr sar rol ror`). Comparisons are **not** value expressions;
  use `cmp` with `setcc`.
* Division **is** in `expr` (`/` and `%`). It reads no flags and touches no
  memory; the fault it can raise is covered by the rule below.
* Divide-by-zero and quotient overflow are the hardware's contract (`#DE`), no
  check is generated, and both are the programmer's responsibility (§2.1). The
  two conditions are not equally reachable, which is worth knowing while
  writing the code:
  * `u16 / u16` **cannot** overflow. With a 16-bit dividend the quotient always
    fits, so divide-by-zero is the only way to fault.
  * `i16 / i16` **can** overflow on an entirely ordinary computation:
    `-32768 / -1` traps, because `32768` is not a signed 16-bit value. That is
    the programmer's business like any other fault; the compiler's only duty is
    not to add faults of its own.
* **An operation that can fault is not speculatable.** `expr` may be
  reassociated, CSE'd, duplicated within one execution path and reordered, but an
  `expr` containing division may not be moved to a place where it would execute
  in an execution that would not otherwise reach it. Hoisting it out of a loop
  whose body never runs must not introduce a trap. This is the only exception to
  "free to optimise", and it follows from §2.1: the compiler never invents a
  fault the program did not already contain.
* In `eval` and as statements: all of the above, plus the carry consumers
  (`adc sbb rcl rcr`), `cmp`, `test`, `setcc`, and the conditions.
* The governing rule: **an operation that reads the flags can appear only in
  `eval`, never in `expr`.** `adc`, `sbb`, `rcl` and `rcr` all read `CF`, so
  this is not a house style, it is the hardware.
* Where the instruction really does depend on signedness, the **mnemonic form
  overrides what the operands imply**: `shr`/`sar` already do this, and
  `div`/`idiv` and `mul`/`imul` belong to the same family. An operator written
  as a symbol, applied to operands of mixed signedness at the same width, is a
  **hard error** — say what you mean, with a mnemonic or by copying the value
  into a variable of the type you mean (§3.5). `[proposed]` the exact spelling
  of the mnemonic operators.

### 5.6 8086 costs worth knowing — [decided]

* The 8086 has no shift by immediate (`SHL r/m, imm8` arrived with the 80186):
  `eval(x << 3)` is three `SHL x, 1`, and a shift count held in a variable must
  go through `CL`.
* Neither does `MUL` have an immediate form, and it forces `AX` (plus `DX` for
  the wide product). It costs 118+ cycles where `ADD` costs 3, and a 32×32
  multiply is three `MUL`s plus a carry chain — on the order of 400+ cycles.
  This is precisely why `expr` exists: only `expr` lets `*2` become `shl`.

## 6. Arithmetic contracts the 8086 forces

### 6.1 Multiply — [decided]

* `*` is available at 16 and 32 bits and **truncates to the operand width**.
* The high half is the user's business: `(u32)a * (u32)b` gives the full 16×16
  product in its low 32 bits, and anything wider than that is inline assembly.
  The surface language has no tuple-returning `mul`.
* Signedness follows the operands: `u16 * u16` is `MUL`, `i16 * i16` is `IMUL`.

### 6.2 Divide — [decided]

* v1 offers **16-bit division only**: `u16 / u16` (`DIV`) and `i16 / i16`
  (`IDIV`). `u32 / u16`, `u32 / u32` and the remainders of those are **hard
  errors at lowering**. No software division is synthesised.
* The contract is the hardware's: the quotient must fit and the divisor must be
  non-zero, or it is a `#DE` trap. **No overflow check is generated.** The trap
  is the hardware's contract and the programmer's responsibility (§2.1), and the
  compiler's duty is only not to introduce one the program did not already have
  — division is not speculatable (§5.5).
* `%` is offered at the same widths as `/`, and costs nothing extra: `DIV`/`IDIV`
  already produce the remainder in `DX` beside the quotient in `AX`.
* v1 stops at 16 bits on purpose. A more convenient shape for wider division may
  be worth revisiting later; that is a note, not a plan, and nothing promises it.

### 6.3 Why the asymmetry is deliberate — [decided]

A wide product can be assembled exactly from 16×16 partial products. A wide
quotient cannot be assembled without a long software routine, and this project
does not ship one. Hence 32-bit multiply without 32-bit divide.

## 7. Control flow

### 7.1 Primitives — [decided]

Labels, `jmp`, and the `jcc` family. This is what the pipeline sees.

### 7.2 MASM-style sugar — [decided]

```
.if x < y
    ...
.elseif x == y
    ...
.else
    ...
.endif

.while n > 0
    ...
.endw
```

* **[proposed]** the signedness of a condition is inferred from its operands,
  with an explicit conversion to override it. `&&` and `||` are not in v1.
* **[decided]** The sugar is normalised away at parse time. The printer prints
  the canonical labels-and-branches form, so `print(parse(text))` is not `text`
  unless `text` was already canonical — while `parse(print(ir)) == ir` continues
  to hold. The two invariants are about different things and both are tested.

## 8. Storage state and the stack

### 8.1 Segment registers — [decided]

`ds`, `es`, `ss` and `sp` are writable names. They are segmentation state rather
than general registers, so `ds = 0` and `sp = 0x7C00` are the natural spellings
for what an assembly programmer writes as `mov ds, ax`.

### 8.2 No-spill functions — [decided]

Early boot code has no valid `SS`/`SP`, so the allocator must never use the
stack behind the user's back. A function can be marked as not allowed to spill.
Because variables are virtual registers, the meaning is exact:

> A no-spill function uses **no stack memory at all** — no frame, no spill
> slots. If it needs more simultaneously live variables than the register file
> holds, that is a **hard error**, not a silent frame.

**[open]** the spelling of the marker.

## 9. Inline assembly — [decided]

Inline assembly is the escape hatch: register-based BIOS/DOS calls, port
sequences, string operations, and anything the surface cannot express. A block
declares the registers it clobbers, and just as importantly the flags it
**reads**, not only the ones it writes — a block containing `adc` reads `CF`, and
without a declared use the optimiser may move a flag-clobbering `expr` in front
of it.

```
asm clobbers(ax, dx, flags) {
    mov ah, 9
    mov dx, offset msg
    int 21h
}
```

Data labels can be referenced directly, because a label is an address. Note that
this is also what defines "address taken" in §5.2.

**[open]** how *variables* are bound as inline assembly operands — placeholders
filled by the allocator, or forcing the variable into memory — and whether
inputs and outputs are part of the syntax or only clobbers are.

## 10. Data — [proposed]

Single-segment `.COM` layout: an origin, an entry point, and data defined inline
where it sits, asm-style.

```
target 8086
org    0x100
entry  main

main:
    ...
    ret

msg: db "Hello, world!$"
tbl: dw 0x1234, 0x5678
buf: <32 zero bytes — repeat spelling open, see below>
```

**[decided]** There is no separate "uninitialised" storage class, and there is
nothing beyond the image. Reserved space is **zero bytes emitted into the
image**, so it is a repeat form with a default fill of zero rather than a
storage-class directive:

```
buf: db 32 dup(0)        ; or whichever repeat spelling is chosen
pad: db 6 dup(0xFF)
```

The reasoning, in the order that matters:

* A `.COM` file is loaded whole, and DOS does **not** zero the memory beyond the
  loaded image; relying on that is a classic bug. Emitting the zeros is the only
  reading that is actually guaranteed.
* A boot sector is exactly one 512-byte image and a ROM is a fixed image. Inside
  them there is no "beyond the image" at all, so emitted zeros are the only
  meaningful semantics.
* Consequence: a large buffer costs image size. If the image does not fit — a
  boot sector over 512 bytes, a `.COM` over 64K−0x100 — that is a **hard
error**, not a truncation.

Because the concept has become "repeat a value N times", naming it
`resb`/`resw`/`resd` would be actively misleading: in NASM those do *not* grow
the file, which is the opposite of what happens here.

**[open]** the repeat spelling (`db 32 dup(0)`, MASM-style, or `times 32 db 0`,
NASM-style), and whether anything beyond the image — absolute placement, a `.COM`
"BSS" past the end of the file — is ever offered. It is not in v1 and it is not
promised.

**[open]** sections, modules, `extern`/`global` and a link step — deliberately
out of v1.

## 11. Target-provided operations — [decided]

Operations that are not arithmetic: `in`/`out`, `int`, `hlt`, `cli`, `sti`,
`nop`, `iret`, the `setcc` family, and the carry consumers. On a target that does
not provide one, using it is an **unsupported-input hard error** — never a silent
substitution.

**[open]** whether string operations (`movsb` and friends) and `jcxz` get a
surface of their own or are left to inline assembly.

## 12. Open questions

Collected for greppability; each is marked **[open]** at its point of use above.

1. Spelling for naming an older flag value, and the flag capture form (§4.4).
2. How much of `lahf`/`sahf`/`pushf` is exposed directly (§4.4).
3. The spelling of the mnemonic operators `div`/`idiv`/`mul`/`imul` (§5.5).
4. The no-spill marker's spelling (§8.2).
5. Inline assembly operand binding for variables, and inputs/outputs (§9).
6. Whether string operations and `jcxz` get a surface (§11).
7. The repeat spelling for zero-filled space, and whether anything beyond the
   image is ever offered (§10); plus which other details of §10 survive review.

## 13. Non-goals for v1

Floating point and the 8087; aggregates; 64-bit arithmetic; dynamic memory; a
software multiply/divide library; `&&`/`||` in conditions; modules and linking;
a general disassembler.
