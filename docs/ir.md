# The 8086-IR surface

Status: **draft, and in force where it is built**. This document is the
description of record for the IR *surface* — the syntax and semantics a person
writes and reads. `README.md` says what the project is and what the compiler does
with this surface today, `AGENTS.md` says how to work on it without breaking it,
[`docs/ssa.md`](ssa.md) describes the form the middle of the pipeline uses, and
[`docs/asm.md`](asm.md) the assembly that comes out.

A construct is proposed here and approved here **before** it is implemented
(`AGENTS.md`). Every claim below carries one of three marks:

* **[decided]** — agreed; implementable as written.
* **[proposed]** — designed, spelled out, but not yet confirmed; may change.
* **[open]** — named and known to be missing, not designed yet.

A mark says whether the *design* is settled, not whether the compiler does it —
several things marked [decided] here are not built, and `README.md`'s *Status*
is where that is written down. A construct that is decided and unbuilt is still an
unsupported-input hard error rather than a quiet approximation of something else.

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

## 2. Rules to test every addition against

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

### 2.3 A program's effects are not its registers — [decided]

The IR knows the target's memory world, and **memory, ports, interrupts and the
flags somebody reads are what a program does.** The register file is not among
them. Which register holds which value at which point is the allocator's business,
so a value nothing reads is **dead**, even when the code that computed it happened
to leave it in `AX`.

So `ret` promises nothing about the registers, and neither does the end of any
other path: a `.COM` hands DOS whatever happens to be in the registers, and a
module that needs a particular register to hold a particular value at that point
says so — today with an inline assembly block (§9), and one day with a first-class
form for saying it (§12, item 12). Leaving it to the allocator is what lets a value
the program never reads disappear instead of being computed for a register's sake.

Two consequences are worth stating now, because they are rules for passes rather
than matters of opinion:

* An inline assembly block is an **interface**, not a value. What it reads and
  writes is what it declares, which is what keeps the register file out of the
  rest of the surface.
* A value that is only fed to something the compiler cannot see into is **not
  dead**. Until a block can declare its inputs (§9), every variable in scope is
  taken to be live across it. Fixing that is what item 12 in §12 is for, and until
  it is fixed, this is the conservative reading an optimiser has to take.

## 3. Values, variables and memory

### 3.1 Variables are virtual registers — [decided]

`var x: u16` introduces a mutable **virtual register**. Reading it is not a
memory access. Whether the allocator keeps it in a register or spills it to the
frame is invisible at this level.

A name may not be a word the surface already uses for something else — a type
prefix such as `byte`, a data directive such as `db`, a statement word such as
`jmp`, or a condition such as `jc`. Such a declaration is refused, because the
meaning of the word would then depend on where you looked.

**Data labels are memory.** `msg:` denotes an address — a near pointer constant.

This distinction is load-bearing. It is what makes `expr` pure (§5.2), and it is
what makes `mov dx, offset msg` meaningful inside an inline assembly block.

The input is not SSA. Variables are mutable and are written more than once; SSA
construction renames them, and [`docs/ssa.md`](ssa.md) is where the form it
produces is described.

* **[open]** reading a variable before anything has written it is **not checked**.
  Nothing in the surface says a variable has to be assigned first, and nothing in
  the compiler asks, so `var x: u16` followed by `y = eval(x + 1)` is accepted and
  reads whatever the register happens to hold. SSA construction has to be able to
  say "nothing defines this", and says it with the variable's undefined value
  ([`docs/ssa.md`](ssa.md) §5). Adding the check is a decision rather than an
  implementation detail, because it would refuse programs this compiler accepts
  today, and precision may only ever grow (§4.3).

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
* **[open]** a literal cannot be written negative: `-1` is refused, and the bit
  pattern has to be written as `0xFFFF`. There is no unary minus in the surface,
  and whether there should be is not decided.

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
  removed, duplicated or reordered" out of reach of expression optimisation,
  instead of relying on every optimisation to remember it. The verifier refuses
  one written inside an expression, and `Effects.hasEffect` is what a pass asks
  before deleting a statement — so the rule holds for the passes that exist and
  for the ones that do not yet.

#### What the compiler may assume about two accesses — [open]

Nothing here says whether two memory accesses can be the same memory. That
question is the whole of what would make a load redundant, and it is unanswered,
so the answer the compiler takes is the safe one: **two accesses may alias unless
they are the same access written twice**.

What that costs, concretely:

* A load is **removable** when nothing reads its value and it is not volatile.
  That needs no aliasing question at all, and it is what the optimiser does today:
  `x = [msg]` with `x` dead goes, and `volatile [0x40]` beside it stays.
* A load is **reusable** — replaced by an earlier load of the same address — only
  if no store in between can write that address. A near pointer on this machine can
  point anywhere in the segment, so a store through a *variable* may alias
  anything, including a data label. Without an analysis that can say otherwise, no
  load is reusable.
* A load from a **fixed address** whose bytes are in the image — `msg: dw 0x1234`
  and `x = [msg]` — could be folded to the value in the image, because the program
  has not run yet. A store written before it may still have changed those bytes, so
  even this waits for the same answer.

The analysis that would answer it is not designed here. What is decided is the
shape it has to take: it is a question about *addresses*, asked through the target
(§2.1), and the surface must be able to say when a pointer is known to point at
one thing — an address into the image and an address of a device register are not
the same kind of address, and a language that cannot tell them apart has to be as
careful as if they were the same.

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

`byte` and `word` are the same two words as the size prefixes of a memory
operand (§3.4), and the bracket after them is what says which of the two is
meant: `byte [p]` loads one byte, `byte y` takes the low byte of a value.
`dword` is only ever a prefix, because nothing is wider than a double word, so
there is nothing to narrow from.

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
* **[open]** what a conversion does to the flags. On this machine widening is an
  instruction that touches them, so the compiler currently assumes a conversion
  disturbs them, which refuses more than it has to. Which it is, is the target's
to say (§4.2), and the answer belongs behind the target's flag effects rather
  than in the IR.

## 4. Flags

### 4.1 `flags` is an ordinary variable — [decided]

`flags` is a mutable variable, like `x`. Arithmetic, logic, shifts, `cmp` and
`test` write it; conditions read it. SSA construction renames it like any other
variable, and materialises it — through a target-declared expansion — when a
flag value has to survive an instruction that defines those flags. The user
never writes `LAHF`, `SAHF` or `PUSHF` by hand.

It is **predeclared**: no module declares it, and a module that tries to declare
that name is refused, because one name cannot be two things.

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

There are two ways for that to happen, and they are not the same thing:

* the writer **gave the flags up**, by computing a value with `expr` (§5.2), and
  the fix is to write `eval` instead or not to read them here;
* the compiler **cannot prove they are defined**, because a path arrived without
  defining them. The fix is a redundant `cmp`/`test`, and the message must say
  that this is the compiler's limitation rather than a mistake — there is a
  difference between "you did not define this" and "I could not show that you
  did", and confusing the two is how a compiler earns a reputation for being
  obnoxious.

Today the check is a single pass in source order that is **sound but
incomplete**: it never accepts a program that reads an undefined flag, and it
refuses some programs that are in fact correct. It is sound because every jump
and branch lands on a label (checked, §7.1), a label clears the state, and the
entry point is a label, so an accepted branch can only have been reached by
falling through the sequence the check just walked. It is incomplete because the
flags at a label are really a question about a graph, and this is a flat walk of
a list.

That is temporary, and the shape of the fix is fixed: once a control flow graph
and SSA exist, `flags` is an ordinary value, the question becomes reaching
definitions over it, and a join that needs the flags materialises them. Two
consequences are worth stating now, because they belong to the compiler's
behaviour rather than to this document:

* **Precision may only ever grow.** A program this compiler accepts is accepted
  by every later version; a check may not become stricter without changing this
document first. Users put a compiler in a build, and a build that stops
  working on an upgrade is a broken promise.
* The check belongs behind one interface. The verifier turns a negative answer
  into a hard error with a position; it does not own the analysis, or there will
  be two implementations of one question.

**[open]** how fine the granularity is. The flags are six independent bits and a
`jc` reads only `CF`, so the precise question is per bit; today the check is per
whole flag set, which is coarser than it needs to be.

### 4.4 Condition mnemonics state the condition — [decided]

```
cmp  x, y
jc   L        ; reads the current flags
ja   L        ; unsigned above
jg   L        ; signed greater
```

Because the mnemonic names the condition exactly, branches need no signedness
inference at all — the assembler mnemonic *is* the override.

A condition has **one canonical spelling**, which is the one the printer writes.
The 8086 spells sixteen conditions thirty ways: `jb`, `jc` and `jnae` are one
test of the carry flag, `je` and `jz` are one test of the zero flag. All thirty
are accepted and normalised, so a condition is one word in the IR and the
question "is this the same test" has an answer that does not need a table.

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

The two differ in **how they are defined**, and everything else follows from
that:

* **`eval(...)` is defined constructively.** It is an operation — one operation,
  not a tree — done as written. There is no evaluation order to reason about and
  no question which operation's flags come out, because there is one operation
  and they are its.
* **`expr(...)` is defined declaratively.** It is a value, and nothing else is
  promised, so the compiler may realise it however it likes.

That is also the division of labour: `eval` is how you say *what happens*,
`expr` is how you say *what the answer is*.

### 5.1 `eval(...)` — one operation, done as written — [decided]

```
s = eval(a + b)     ; one addition
s = eval(a + [p])   ; one addition, whose second operand is a load
a = eval(a + b)     ; and when the destination is the first operand the move is
                    ; elided: add a, b
s = eval(~a)        ; one complement
eval(a + b)         ; one addition, value discarded, flags left defined
```

* **One operation.** `eval(a + b + c)` and `eval(a + b * c)` are refused: they
  are two operations, and which one's flags came out would be a question.
  Anything with structure is written with `expr(...)`, which is a tree, or broken
  into statements:

  ```
  var t: i16
  t = expr(x * 4)
  y = eval(x + t)
  ```

  A **load is an operand and not an operation**, so `eval(a + [p])` is still one
  operation. That is the case that needs `eval` and cannot use `expr`.
* **What happens is what is written**, and the flags it leaves are **defined**:
  they are that operation's flags.
* **The value is computed, then written.** `s = eval(...)` means: compute the
  operation's value, then store it in `s`. The elision above is a *code
  generation* fact and not part of the meaning, and it holds when writing the
  result straight into the destination cannot change anything. It does not hold
  for `a = eval(b - a)`, where the destination is needed *after* it would have
  been overwritten: the value is `b - a`<sub>old</sub>, and reading the elision
  as "`mov a, b` then `sub a, a`" would give zero. A register allocator gets that
  right for free; a definition that reads like a recipe does not.
* Consequence: while those flags are live, folding, reassociation and strength
  reduction are constrained. Replacing `MUL` with `SHL` changes the flags, so it
  is legal only when the flags are dead. Folding is legal whenever the resulting
  flag bits can be reproduced (§4.2) — which, on the 8086, generally means only
  when the flags are dead. This is not left for each pass to remember: **unread
  flags** is a pass, it asks the SSA form whether anything reads the version an
  operation defines, and it is what makes the one-byte `inc` reachable for an
  addition nobody reads the carry of (`README.md`, *Status*).
* A comparison is not written with `eval`: `cmp` and `test` are statements of
  their own (§4.4), and like `eval` they are one operation, because a comparison
  whose flags came from somewhere else would be a comparison nobody could read.

### 5.2 `expr(...)` — a value, and the optimiser's business — [decided]

```
s = expr((a + b) * c)
```

* It is a **tree**, which is the other half of the division of labour: structure
  belongs here and a single operation belongs to `eval`. Brackets and precedence
  decide the tree (§5.5).
* Operands are **variables and literals only**. No memory operands, no loads, no
  `volatile`, no labels.
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

### 5.5 Operators and precedence — [decided]

An expression is a tree, and brackets say what binds to what. Where they are not
written, the precedence everyone already has in their fingers decides: every
operator is left-associative, and, tightest first,

| binds | operators |
|---|---|
| 1 | `~` (prefix) |
| 2 | `* / % mul imul div idiv` |
| 3 | `+ - adc sbb` |
| 4 | `shl shr sar rol ror rcl rcr` |
| 5 | `&` |
| 6 | `^` |
| 7 | `\|` |

A writer who would rather not remember that writes brackets, which change
nothing else and cost nothing.

* In `expr`: `+ - * / % & | ^ ~`, and the shifts and rotates that do not read
  the carry (`shl shr sar rol ror`). Comparisons are **not** value expressions;
  use `cmp` with `setcc`. A tree that needs a load is not written here at all:
  it is written as `eval` of one operation per load, with the rest in `expr`.
* **[proposed]** a unary minus, `-d`: one operation, the one the machine calls
  `NEG`. It is not a new meaning — `NEG`'s flags are the flags of subtracting
  from zero, `CF` included, so `-d`, `0 - d` and the instruction `neg d` are one
  operation and it is the zero in `d = eval(0 - d)` that carries no information.
  Two things it settles rather than assumes: which precedence row it belongs in
  (with `~`, level 1, where a prefix can only be a prefix), and §12's open
  question about negative literals, since `-1` and `-d` begin the same way.
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
* In `eval`: any operator of the sets below, on the single operation it is
  given. Because `eval` may read the flags, the carry consumers — `adc`, `sbb`,
  `rcl`, `rcr` — belong there and nowhere else.
* In `expr`: the operators that read no flags, as a tree.
* `cmp`, `test`, `setcc` and the conditions are statements, not operands of
  either form.
* The governing rule: **an operation that reads the flags can appear only in
  `eval`, never in `expr`.** `adc`, `sbb`, `rcl` and `rcr` all read `CF`, so
  this is not a house style, it is the hardware.
* Where the instruction really does depend on signedness, the **mnemonic form
  overrides what the operands imply**: `shr`/`sar` already do this, and
  `div`/`idiv` and `mul`/`imul` belong to the same family. They are written
  infix, like every other operator: `a shl 1`, `a adc b`, `a idiv b`.

  An operator written as a **symbol**, applied to operands of mixed signedness
  at the same width, is a **hard error** — say what you mean, with a mnemonic or
  by letting one value live in a variable of the type you mean, which costs
  nothing and emits nothing (§3.5). It is stricter than it has to be: addition
  and multiplication give the same bits either way. It is strict because a
  symbol that means two things is a symbol a reader has to stop and decode.

* `expr` works on values that are already in registers: its operands are
  variables and literals, and a load or a label is refused there (§5.2).

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
  `[open]` 32 bits is decided as a surface and not built: the back end does
  sixteen. A 32-bit multiply is a pair of 16×16 partial products and nothing here
  produces one yet.
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

`ret` ends the program's path, and it promises nothing about the registers: what
it leaves behind is the allocator's business, not an effect of the program (§2.3).

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
* The labels the sugar invents are named so that no name a person can write looks
  like one, and they are numbered in the order they are created, so the output is
  the same on every run (`AGENTS.md`, invariant 6).
* Choosing a signed or an unsigned test needs the signedness of what is compared,
  and that is written in the declarations — which may come after the comparison.
  So the declarations are read first; the parse proper is still the authority on
  what a declaration is, and the earlier reading only feeds an inference. A
  comparison that mentions no variable at all is unsigned, which is what the
  plainest mnemonics say: `jb` and `ja`.
* The sugar needs one thing of the target beyond the conditions themselves: their
  opposites. `jb` against `jnc` is not a rule anybody could guess, so it is asked
  rather than derived.

Both constructs are shaped for **instruction count**, which is one of the two
things this project measures output by:

```
.if c            .while c
  A                A
.else            .endw
  B
.endif

cmp c            jmp TEST      ; paid once, to reach the test
j{not c} L1      BODY:
A                  A
jmp END          TEST:
cmp c            cmp c
j{not c} L1      j{c} BODY     ; the condition's own branch is what goes back
B
END:
```

An `if` spends one branch per test and no jump unless there is an `else`. A
`while` puts its test at the bottom, which costs one jump on entry and then saves
one instruction every time round: the conditional branch is what goes back, and
falling out of the loop is the path that needs no instruction at all. The inner
branch of a `while` is therefore the condition **as written**, where an `if`
branches on its opposite.

### 7.3 Instruction-shaped statements — [proposed]

An operation may be written the way the machine writes it, with the destination
spelled out instead of implied by the `=`:

```
add s, 1            ; exactly  s = eval(s + 1)
sub s, t            ; exactly  s = eval(s - t)
and s, 0xff         ; exactly  s = eval(s & 0xff)
adc s, 1            ; exactly  s = eval(s adc 1)
shl s, 1            ; exactly  s = eval(s shl 1)
mov s, [p]          ; exactly  s = [p]
```

The left operand is the destination, and the statement means the operation whose
result is written there — which is what the surface already means by
`s = eval(s + 1)`, down to the flags (§5.1). It is a **spelling, not a new
operation**, so it changes nothing a reader of the form has to know, and nothing
that reaches the optimiser: a statement written this way is the same IR as the
`eval` form, and printing it produces the `eval` form, exactly as §7.2's sugar
prints as labels and branches.

It exists for one reason: a program written for an assembler can be brought over
as it stands, one operation per line, without being rewritten into `eval` shape
first. That is also the whole of what it promises, and it is why it is
**one operation with two operands and no nesting**: `add s, 1 + 1` is a tree and
belongs in `expr` (§5.4).

**A word is accepted only when it names an operation the surface already has.**
That is where the line is, and it is not a matter of taste: a word that would
introduce an operation the surface does not have would be a second meaning for a
familiar spelling, and the meaning would then be a guess. So:

* **Accepted**, because each names one of §5.5's operators and means exactly what
  that operator's `eval` spelling means: `mov` (the bare assignment of §5.3),
  `add`, `sub`, `and`, `or`, `xor`, `not` (`~`), `neg`, and the operators already
  spelled as words — `adc`, `sbb`, `shl`, `shr`, `sar`, `rol`, `ror`, `rcl`,
  `rcr`, `mul`, `imul`, `div`, `idiv`.
* **Refused, with the reason**, because the machine's instruction and the surface
  operation it looks like are **not the same operation**:
  * `inc d` / `dec d` — the carry is the difference, and it is exactly the kind a
    reader would not see: `d = eval(d + 1)` defines `CF`, `inc` leaves it as it
    was. Writing the `eval` form instead is *not* a substitute, because a later
    carry consumer would read a different `CF`. The target description says the
    same thing in its own vocabulary, which is why `inc` is a form of its own
    marked as not keeping the flags (`Form.keepsFlags()`).
  * `mul r` / `imul r` / `div r` / `idiv r` / `cwd` / `cbw` — one operand or
    none, with `ax` and `dx` read and written behind the writer's back. The
    two-operand `mul d, s` is fine, because that one *is* the surface's
    operation; the one-operand machine form is not.
  * `xchg`, `lea`, `push`, `pop`, the `in`/`out` and interrupt group, and the
    string operations — several effects at once, or an addressing form, or a
    target operation of §11 with no surface spelling yet.

`neg d` is in the first list on purpose, and it is worth saying why, because the
obvious argument cuts the other way. `NEG` **is** subtraction from zero: the
manual defines its flags as `CF` cleared exactly when the operand is zero and the
rest from the result, which is what subtracting from zero gives, so
`neg d`, `d = eval(-d)` and `d = eval(0 - d)` are one operation in three
spellings and nothing about the program changes between them.

The distinction in that list is **not** "the machine's flags match the surface's
exactly" — for `~`, `rol` and `ror` they do not, and §4.2's per-flag effects are
what would fix that. It is that one spelling must mean what the other spelling of
it means: `not d` and `d = eval(~d)` are one statement written twice, and so are
`neg d` and `d = eval(0 - d)`, while `inc d` and `d = eval(d + 1)` are two
different programs. Only the first kind may share a name.

One thing this costs, which is a target's business and not the surface's: the
spelling must not be worse than what it is a spelling of. `d = eval(0 - d)`
compiles today to a copy, a zero and a subtract — seven bytes where `NEG` is two —
so accepting `neg d` goes together with the target listing `NEG` as a form of the
negation (§5.5, §5.6), not before it.

**A register name is not an operand here.** `mov ax, 1` would otherwise name a
*variable* called `ax` — legal, since a variable is a virtual register whose name
its author chose (§3.1) — and quietly mean something other than what the writer
wrote. A register is reached through inline assembly (§9), or through the pinning
of §12 item 12 once it exists. The refusal is inside the new construct, so a
program that already has a variable named `ax` is unaffected.

**[proposed]** the whole construct, and in particular: whether `mov` is worth
having when `=` already spells the assignment, and whether the word may be
followed by more than the two operands an 8086 form takes.

**[open]** whether any of this should reach the assembler-facing side too — an
`asm` block, or a `.8086`-style directive — or stay a statement form only.

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

**[open]** the spelling of the marker — and a fact worth knowing while it is open:
**nothing spills today**, marker or not. There is no frame and no spill slot in the
compiler at all, so every function currently behaves as a no-spill one, and a
program that needs more registers than six is refused (`README.md`, *Status*).

## 9. Inline assembly — [decided]

Inline assembly is the escape hatch: register-based BIOS/DOS calls, port
sequences, string operations, and anything the surface cannot express. **The
text inside a block is the assembly text of [`docs/asm.md`](asm.md)** — the same
syntax the emitter writes and the bundled assembler reads — so there is one
assembly language in the project rather than two. A block declares the registers
it clobbers, and just as importantly the flags it **reads**, not only the ones
it writes — a block containing `adc` reads `CF`, and
without a declared use the optimiser may move a flag-clobbering `expr` in front
of it.

The list is the **complete** set of registers the block destroys, and it is a
promise in both directions. A register it does not name survives the block, so a
value may live there; a register it names does not, so the allocator keeps any
value that is still to be read after the block out of it. That is the whole of
what this declaration buys today: not the registers the block *reads*, which it
still cannot say, but a value it is allowed to destroy being known to be dead
anyway.

```
asm clobbers(ax, dx, flags) {
    mov ah, 9
    mov dx, offset msg
    int 0x21
}
```

Data labels can be referenced directly, because a label is an address. Note that
this is also what defines "address taken" in §5.2.

**[open]** how *variables* are bound as inline assembly operands — placeholders
filled by the allocator, or forcing the variable into memory — and whether
inputs and outputs are part of the syntax or only clobbers are.

Until that is decided, a block is opaque in the one direction that matters to an
optimiser: it says which registers it destroys, but not which ones it **reads**,
so a variable whose only other use is inside a block cannot be shown to be live
(§2.3, §12 item 12). The workaround an author can write today is to read the value
into a variable *after* the block as well, or to keep the computation the block
depends on; the thing that must not happen is a pass deciding it is dead.

## 10. The header, and data

### 10.1 The module header — [decided]

Three directives, first, once each, before any item:

```
target 8086                 which machine the module is for
org    0x100                where the image is loaded
entry  main                 which label execution begins at
```

* All three are required. A module without them has no meaning: there would be
  nothing to compile it for, nothing to place it at, and nothing to start.
* **A target name may begin with a digit**, because this one does. This is the
  only place in the surface where a number is read as a word, and it is
  deliberate: the processor is called the 8086, and `target i8086` would be
  inventing a name to suit the lexer.
* `org` is an offset inside a segment, so it is at most `0xFFFF`.
* The entry point is a label defined in the module. It is placed where it is
  written: **items keep their source order**, so a module that wants its image to
  begin with its entry point writes that label first. `[open]` whether a later
  version instead places the entry point itself, or emits a jump to it.

### 10.2 Data — [proposed, except as noted]

Single-segment `.COM` layout: data defined inline where it sits, asm-style.

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
promised. Nor is any repeat form implemented: `db`/`dw`/`dd` take a list of atoms
and nothing else, and a file that needs a zero-filled buffer has to write one out.

**[open]** sections, modules, `extern`/`global` and a link step — deliberately
out of v1.

## 11. Target-provided operations — [decided]

Operations that are not arithmetic: `in`/`out`, `int`, `hlt`, `cli`, `sti`,
`nop`, `iret`, the `setcc` family, and the carry consumers. On a target that does
not provide one, using it is an **unsupported-input hard error** — never a silent
substitution.

None of them is selectable yet: the surface for them is decided, the back end does
not emit them, and **inline assembly is how a program writes one today** (§9).
That is a hard error rather than a fallback, but it is worth saying out loud
because it is the shortest path to a program this compiler cannot compile and
another assembler could.

**[open]** whether string operations (`movsb` and friends) and `jcxz` get a
surface of their own or are left to inline assembly.

## 12. Open questions

Collected for greppability; each is marked **[open]** at its point of use above.

1. Spelling for naming an older flag value, and the flag capture form (§4.4).
2. How much of `lahf`/`sahf`/`pushf` is exposed directly (§4.4).
3. The no-spill marker's spelling (§8.2).
4. Inline assembly operand binding for variables, and inputs/outputs (§9).
5. Whether string operations and `jcxz` get a surface (§11).
6. The repeat spelling for zero-filled space, and whether anything beyond the
   image is ever offered (§10); plus which other details of §10 survive review.
7. What a conversion does to the flags (§3.5), which the target's flag effects
   will answer.
8. Whether `expr` may take a label, which is a constant and not a load, but is
   not a variable either (§5.2).
9. Whether a literal may be written negative (§3.2).
10. How fine the flags check's granularity is: per bit, or per whole flag set
    (§4.3).
11. Whether reading a variable before anything has assigned it is refused (§3.1),
    and therefore whether the undefined value of a variable (§5 of
    [`docs/ssa.md`](ssa.md)) is a construct the surface keeps.
12. A first-class way to require a value in a register at a point — "`ax` has to
    be this here" — so that a module using a register-based interface (BIOS, DOS,
    or a caller of its own) does not have to write an inline assembly block and
    pay for the moves a block's opacity forces. The spelling is undecided; what is
    decided is that it stays **bounded**: it names a register at a boundary rather
    than letting a program address the register file, the allocator treats it as a
    pre-coloured live range rather than as an instruction, and a pinned value
    cannot be spilled (§8.2). Whether it extends to *reading* a register the
    compiler never put anything in is the harder half of the question, and is
    probably a different construct.

    **Half of this now exists, and it turns out to be the other half.** An
    instruction can say what it destroys, and the allocator keeps a value that is
    still to be read out of those registers: `mov cl, 8` writes a register no value
    was given, `mul` leaves half its answer in `dx`, and a value living in `cx` or
    `dx` across either one is refused that register. What the *machine* insists on —
    that a multiply has one operand in `ax` — is handled by the target declaring the
    sequence and the allocator dropping the copies that turn out to be copies from a
    register into itself, so nothing is pinned and nothing is reserved.

    What is still missing is the direction none of that covers: the *surface* has no
    way to say "this value has to be in this register here", which is what a BIOS or
    DOS interface wants and what the item was about in the first place. The machine's
    own insistence is spelled out by the target; a program's is not spellable yet.
13. A **calling convention**: how a call is written at all, where the arguments
    go, what a callee preserves, and who tidies up afterwards. None of it exists —
    the surface has no call, so a module's only interfaces are its entry point and
    its inline assembly blocks (§1, §9). What is decided is where the answer lives
    and what it has to be able to say:

    * A convention is a **target** fact by default (`AGENTS.md`, invariant 2),
      because the machine's interfaces are register-based, and a **declared** fact
      per function or per call when a module wants another one, because real 8086
      code mixes them: BIOS takes arguments in registers, DOS takes a function
      number in `AH`, and a boot loader's own routines follow whatever the author
      decided.
    * A stack convention cannot be the only one. Pushing arguments and cleaning
      them up is the obvious way to pass more than a couple, and on this machine
      it is cheap — but early boot code has no valid `SS`/`SP` at all (§8.2), and
      the interfaces this IR exists for pass their arguments in registers. So a
      register convention has to be expressible, and it is the *same* vocabulary
      as an instruction's implicit registers and a block's clobber list — item 12's
      mechanism, not a second one.
    * "Callee-saved" then needs no machinery of its own: it is the clobber list on
      the call. A register the call does not destroy is one the allocator may leave
      a value in, and a register it does destroy is one a value alive across the
      call is kept out of. What the callee has to do to earn the promise — save and
      restore, or use other registers — is the callee's own code, and in a no-spill
      function (§8.2) it is the difference between using a register and not being
      allowed to touch it.
14. What a shift by the width of its operand, or more, does. The 8086 answers it —
    the count is taken modulo 32 — and the surface does not say whether that *is*
    the meaning or whether it is one of the things a program may not do. So the
    optimiser folds no such shift and refuses none either: the machine's answer is
    what the program gets, which is the honest thing while the question is open.
15. What the compiler may assume about two accesses being the same memory (§3.4,
    under *What the compiler may assume about two accesses*). Until it is answered,
    a load is reusable only when it is written twice in a row, and a load from the
    image is not folded to the bytes in the image.

## 13. Non-goals for v1

Floating point and the 8087; aggregates; 64-bit arithmetic; dynamic memory; a
software multiply/divide library; `&&`/`||` in conditions; modules and linking;
a general disassembler.
