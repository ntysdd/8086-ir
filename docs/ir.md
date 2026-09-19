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
frame is invisible at this level — unless the declaration says where the variable
lives, and then it is memory and the answer is not the allocator's to give
(§3.1.2).

**Almost nothing is reserved.** A word means what its position says it means, and
every word the surface knows can also be an author's name — with one exception, a
leading dot, which is how the sugar is spelled (§7.2):

```
var adc: i16        ; an operator's spelling, and a variable
var pad: i16        ; and a word of a statement form
var ax: i16         ; and a register's name: this surface has no registers
var in: i16         ; and the word a declaration reads after its type (§3.1.2)
adc = 1
add pad, 1          ; the statement 'add', not a variable called add
word [ax] = 1       ; the size word, and the variable inside the brackets
```

What makes that possible is a property of the surface rather than a promise about
the parser: **values never stand next to each other**. There is no reading of
`eval(a adc b)` in which `adc` is a name and no reading of `eval(adc + 1)` in which
it is an operator, so a word's role is decided by what stands beside it, and one
token of lookahead settles the few places where a word could begin two things:
`eval` and `expr` are the operation only in front of `(`; a size word is a size
only when a value follows it; and a name gives a variable a home only after the type
of its declaration, with the word after it saying whether the home is kept current
(§3.1.2). The dot words are not on this list because the
dot settles them: `.if` is the sugar and cannot be a name (§7.2).

**The dot is the one exception, and it is worth its cost.** `.if`, `.elseif`,
`.else`, `.endif`, `.while` and `.endw` are the surface's words, and the dot in
front of them is what says so; a name may not begin with one in any position, so a
dot word always means the sugar and a reader never has to count back to see whether
one is a name. What that buys beyond the reading is a guarantee about the *output*:
an assembler reads a label beginning with a dot as a local label of its own
({@code docs/asm.md} §3), and no program can now make this compiler emit one. The
only dot that is not the sugar's is the compiler's `..@` namespace (§3.1.1), which
the printer writes for the sugar's own labels and which has to stay readable.

The price is paid on the way out rather than on the way in. The printer writes the
canonical form, and the canonical form of **every** name the author chose carries a
`$` — so a reader of canonical text never has to ask whether the `eval` in front of
them is a variable or the surface's word, and never has to ask it about any other
name either (§3.1.1). Input is liberal, output is canonical, which is the same split
as the thirty spellings of a condition (§4.4).

### 3.1.1 `$name` is the author's, `..@name` is the compiler's — [decided]

Two prefixes settle what would otherwise be a running argument, and they are
taken from NASM, which has the same problem and the same two answers:

> An identifier may also be prefixed with a `$` to indicate that it is intended
to be read as an identifier and not a reserved word.
> — *NASM Manual*, §3.2

> … if a label begins with the special prefix `..@`, then it does nothing to the
local label mechanism.
> — *NASM Manual*, §3.9

* **`$name`** is the author's name whatever it looks like. `var $shl: i16` and
  `$shl = 1` declare and use a variable called `shl`. The `$` is a **marker and
  not part of the name**: `$ax` and `ax` name the same thing. It is not needed to
  *write* a name like that — nothing is reserved — but it is what the printer
  writes, so it is also what a program can always write to be understood at a
glance.
* **`..@name`** is the compiler's. Names it makes up begin there, and declaring
  a *variable* with one is refused. Writing a label with one is not, because the
  printer writes labels and its output has to be readable again.

The two prefixes are worth stating plainly, and the list that goes with them:

* **What the printer marks** is every name the author chose, and nothing else. A name
  the compiler made up (`..@`) is not marked, and neither are the surface's own words:
  a segment, a clobber list and the body of an inline block are written by the
  printers that own them and never come through the name printer at all.
* **The rule is total, and it is deliberately not a question about the vocabulary.**
  Marking only the names that could be read as a word of the surface made the
  canonical form of a program depend on the compiler's word list, so that adding a
  word silently rewrote the text of every program that had used it as a name — which
  is exactly what happened the day `in` became a word. A total rule has no such
  moment. The vocabulary is still a list with a job: the audit below walks it.
* **The assembly text has one exception, because that language has registers.** There
  a bare name spelled like a register *is* the register (`mov ax, 1`), so a symbol of
  that name has to say so and is written `$ax` (`docs/asm.md` §3). The IR surface has no
  such exception in a value position, because it has no registers there: `var ax: u16`
  is a variable, and it is written `$ax` like every other name. It has one in the
  positions where a statement names a register — `movreg`'s source (§8.1) and the
  register position of a `with` clause (§11) — and there a bare name is the machine's,
  which is the same rule for the same reason: a name the author wants is written `$ax`.
* **What cannot be a name** is only what is not a word at all: a name the compiler
  generated, and a spelling no name can have.

So a program never fails to compile because of a name it chose, and a reader of
canonical text never has to work out which of two things a word is. The audit that
establishes the first half is a test — every word the surface knows, used as a name
in every position a name can stand in. The sugar's dot words are not in it, because
they are the one thing that cannot be a name (§3.1).

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

### 3.1.2 A variable may be given a home in memory — [proposed; the default mode is built]

```
tries: pad 2

main:
    var left: u16 in tries        ; a home for left: these bytes, if it needs them
    left = word [0x40]
    int 0x13                      ; a handler this compiler has never seen
    left = eval(left - 1)
    word [0x42] = left
    ret
```

`var x: u16 in place` gives `x` a **home**: bytes in the image that it may live in.
Whether it does is the allocator's decision, and that is what the declaration is for.
A value that fits in a register stays in one and the home is never touched, which
costs nothing; a value the registers cannot hold is given its home instead of being
refused, and then every read of it is a load and its one definition is followed by a
store. `left` above survives `int 0x13` without the program saying what the handler
keeps — and it needs no register across the call to do it, which is the whole point.

Why it earns its place:

* **The pressure escape hatch is the point of it.** Needing more simultaneously live
  values than the six registers is a hard error (§8.2), and this is how a program says
  "this one may live in memory" without writing the load and the store by hand — which
  is what §11.1's second route does manually, with the reload landing in a *fresh
  name*. Writing them by hand is not the same thing: it fixes *when* the memory is
  used, so a value that would have fitted in a register pays for memory anyway. A home
  is used only when it is needed, and a program that declares one and turns out not to
  need it has paid nothing.
* **A home is also a place that outlives anything opaque.** A value that has to live
  across an `int`, across an inline block, or across anything else the compiler cannot
  see is kept today in registers the thing is declared not to destroy (§11.1). That is
  a promise about the *handler*, and it cannot be made in general: nothing in the
  surface says what memory an `int` writes, so only registers can be reasoned about at
  all. A home gives the program bytes whose contents *it* controls, and what the
  compiler cannot see inside a block stops mattering.
* **It is what hand-written boot code does**, and that is why the home is a *name*
  rather than a number. A boot sector keeps its few bytes in the sector and
  addresses them, because there is nowhere else: the stack may not exist yet
  (§8.2), and the next stage may be loaded over everything below it. Syslinux's MBR
  keeps its disk address packet in a labelled cell of the image; Rufus's MBR
  relocates itself to another segment while deliberately keeping the same *offset*,
  so that every in-image label keeps its value; GRUB's installer patches fields at
  fixed byte offsets of stage1. A name survives all of that; a number does not.

**The home is memory the program declared, so nothing is invented.** §8.2's promise
is about what the *compiler* makes up — no frame, no spill slot — and a home is the
program's own storage: the address is a label in the image, chosen by the author, and
the compiler uses it only because the author said it may. What §8.2 refuses is the
compiler deciding on its own to put a value somewhere, and that has not changed: a
variable with no home is still refused when the registers run out.

**Two modes, and the difference is when the memory is written.** `in place` alone is
the first: the home is used when the registers cannot hold the value, and until then
the bytes at `place` hold whatever they held — the image's own contents, or what the
last program left there if the module never writes them. So `in place` says nothing
about what somebody else reading those bytes would see; it says where the value goes
when it has to go somewhere. The second mode is a word on the declaration —
`var packet: u16 in dap writethrough` — rather than a property of the bytes, because
what it changes is what the compiler has to do with one *variable*: every definition
of it writes those bytes.

`writethrough` is the second mode, and it says the home is to be **kept current**:
every write to that variable goes to the home, whenever it happens and whatever else
the allocator does with the value. It is for a home that somebody else reads — a
handler, the next stage, or a program that patches the image. It costs a store per
definition, which is what asking for it means.

**A program may also write a home itself, and that is a save — and whether it is kept
depends on the cell.** The store is written as an address, `[tries] = left`, because a
label is an address and §10.2 refuses to assign to one. That store writes `left`'s value
into those bytes wherever the value happens to be living, and it happens: it is a
half-volatile write like any other, so nothing removes it, duplicates it, or moves
anything across it. What the store does not buy is the bytes *staying* that way,
because a home is a cell the allocator may write too, and which of the two has the last
word is what the cell was declared to be:

* **A cell no variable declares as a home is the program's alone.** The compiler has no
  reason to write it and no permission to, so what the program puts there stays until
  the program writes it again.
* **A `writethrough` home holds that variable's current value**, which is what that mode
  is: the compiler writes the cell on every definition, and a value saved there lasts
  until the variable is next assigned. The cell is the variable's and not the allocator's
  scratch, which is the other half of why that mode is exclusive (the rule below).
* **Any other home is the allocator's**, and a value the program saves there is **not
  guaranteed** to survive. The allocator may put any value whose home that cell is into
  it, so a later read of those bytes may find the allocator's value rather than the saved
  one. That is not a promise the compiler breaks; it is one it never made.

  **When the cell is declared by more than one variable, the compiler warns at each
  write.** That is the case the author cannot see coming: the bytes may end up holding
  the value of a variable whose declaration is somewhere else in the file, and a small
  change somewhere else, one more simultaneously live value, can be the change that puts
  it there. The warning names the variables that declared the cell — which is what the
  author needs, since one of them is somewhere else in the file — and the two ways out
  are to save into a cell no variable declares, or to let the variable that wants those
  bytes keep them with `writethrough`. It is said for a store written as `[cell]`: one
  through a segment override is a place in another segment as far as anything here can
  tell, and a store to `[cell + 2]` is inside the cell rather than the cell.

  When the cell is declared by exactly one variable there is no warning, and there is no
  guarantee either. The only thing that can replace the saved bytes is that variable's own
  value, and the allocator writes it there because the declaration asked it to: letting the
  variable live in those bytes is what the declaration is for. A program whose saved bytes
  must stay what it wrote therefore has the same two ways out as before, and neither of them
  depends on how many variables asked for the cell.

A boot loader's author is likely to meet this case by accident. The warning is what makes
it survivable, because they are expected to read and test the assembly their program
became: the warning points at the line that has to be read.

In one sentence: **a cell no variable declares belongs to the program, a `writethrough`
cell belongs to its variable, and every other cell is shared with the allocator.** A save
into the first kind stays. A save into the second kind lasts until that variable is
assigned again. A save into the third kind is written, is not promised to stay, and is
warned about when more than one variable asked for that cell.

The other direction needs the opposite rule, and it is the allocator's to keep: a value
**living** in a home does not survive a write of something else to those bytes, so
the allocator may not have one living there across a write by the program. It has to be in
a register there instead, or the program is refused. Never a quiet wrong answer — the same
promise §8.2 makes about the stack, and the reason this direction cannot be left to a
warning: a warning is for what the program may rely on and will not get, and a register or
a refusal is for the value the compiler would otherwise lose.

**A write to a home is half of a volatile write.** That covers the stores
`writethrough` demands and the ones a program writes itself, `[tries] = left` among them.
Such a write is never removed, never duplicated, and never moved across another access
to memory, all of which a volatile write is too (§3.4), with one difference: the
compiler **may** leave it out when it can prove the home already holds that value, and
a volatile write never may. That proof is the compiler's to make and nobody else's to
assume, and today the only one available is the one §3.4 already states — the same
access written twice — so a store of the same value to the same home with nothing in
between is the store that may go. Everything else stands: the bytes are written, in
the order the program writes them, and nothing else may be moved across one.

Three rules, and what each is for:

* **The home must name bytes, and be wide enough.** `place` must be an item that
  names bytes in the image whose width the front end can work out — a labelled
  `db`/`dw`/`dd` list, or a labelled `pad` with a count — and it must be at least as
  wide as the variable. A bare label names a position and not bytes, a `pad to`
  item's length is known only to the assembler (§10.3), a code label is not
  storage, and a narrow home is a program that would read the byte after it. Each
  is refused with its own reason, which is a static error and so the compiler's job
  (§2.2). The check is the verifier's rather than the parser's, because the item may
  be declared after the code that names it — the same reason a `dw` list of labels
  is checked by the verifier (§10.2).
* **A home is one cell, and more than one variable may use it — in the default mode.**
  This is the rule registers are handed out by, and it is the allocator's to keep,
  because it is the allocator that decides which values go to a home. Sharing is what a
  cell is for: values that are not alive at the same time reuse it, which is how one
  two-byte cell serves a whole program, and it is what hand-written boot code does with
  its scratch. What sharing cannot do is keep a *reader* of those bytes informed:
  `writethrough` says the cell **is** the variable's current value, and two variables
  cannot both be that — a reader could not tell whose value it was looking at, and one
  variable's write would break the other's promise. So a cell that is anyone's
  `writethrough` home belongs to that variable alone: another variable naming it is
  refused, and that is a check the verifier can make rather than something the
  allocator works around. The versions of *one* variable may of course share it — that
  is what makes the promise hold, since only one of them is alive at a time. Two
  variables may also name the same bytes without either using them as a home, which is
  the union trick and is none of the allocator's business: nothing is inferred about
  two accesses in either direction — a write through `x` is not assumed visible through
  a read of `place`, and a read of `x` is not folded to something written earlier. That
  is the rule §3.4 already takes, and here it is the feature rather than a limitation,
  because a program that asks for a home is saying it wants those bytes written. What
  the bytes *are* is what the image says (`pad` counts as zeros, §10.2), and a program
  that wants a value there has to write one.
* **Its writes are effects.** A store to a home is never removed, never duplicated,
  and never moved across another access to memory, which is what the home is for —
  and in `writethrough` mode that is true of every write to the variable, not only of
  the ones the allocator chose to put in memory. A read whose value nobody uses changes
  nothing and may go, as a plain read of memory may today; `volatile` keeps the
  meaning §3.4 gives it and stays the way to say that a read is itself an effect.
  **[open]** a home whose *bytes* something else writes — a timer's tick counter, a
  cell a handler updates — is a different matter, because then the compiler may not
  keep a copy of the value at all: §12 item 18.

**What the allocator does, since that is where the cost is.** The decision is made
where every other decision about where a value lives is made — in the allocator, and
not before SSA as a question about operand shapes (*README*, step 7). A value given a
home is one whose reads become loads and whose definition is followed by a store, so
`left = eval(left - 1)` is a load, a subtraction and a store when `left` is in memory
and one instruction when it is not. Whether the machine can do part of that in place
(`sub word [tries], 1` is one instruction on this one) is the target's business and not
the surface's; what this allocator does is the load, the operation in a register and the
store, every time.

A home is a resource like a register, and it is handed out the same way: the value
it holds may not be alive at the same time as another value that is given it, and the
allocator colours a graph to decide (*README*, step 7). A value is given its home only
when no register is left for it, which is what keeps the bytes of a program that does not
need them untouched — and an attempt that cannot place every value, or cannot get at a
value it placed in a home, is thrown away and run again with one more value sent to its
home: a value in the way that has one. So the number of values that end up in memory is a
greedy answer rather than a provably smallest one, and the register a value in a home needs
at one of its points is found the same way, by moving another value out of the way. What is
*not* optimal is the cost: which of several values goes to memory when only some of them
fit is a question about how often each is read, and that is a heuristic.

The accesses the allocator writes have to stay distinguishable from an authored
`[0x40] = x` — not in the syntax, which says nothing about it, but for the pass that
would one day promote memory to values (*README*, step 3): a home was asked for, so a
value the allocator put there may not be quietly promoted back into a register, or
the bytes the program asked to be written stop being written.

**[open]** where the absoluteness stops. This proposal confines a home to bytes the
image already contains, so the address is a label and stays right whatever `org`
says — including under the self-relocation a boot sector does. Giving a variable an
address the image does not contain — `0x0413`, `0x046C`, `0xB800:0000` — is the
other half, and boot code uses all three; that is §12 item 6, and the spelling is
deliberately not shared with it: `in place` names storage that exists, and `at
address` would name a place that does not. Whether such a variable is `volatile` by
default, and whether its segment can be stated at all (§8.1), belong to that
question.

**What it does not change.** Reading a *virtual register* before anything writes it
stays unchecked (§3.1), and a variable with a home is a virtual register with
somewhere to go — so reading it before writing it is unchecked in the same way. The
bytes at the home are there from the start, but whether the *variable* holds what they
hold depends on the allocator: a value that stayed in a register never wrote them.
That is the difference the two modes make, and a program that needs the bytes to be
current says so. The address of a virtual register is still refused, for the reason
§10.2 gives: it would be whatever the allocator chose. That reason is about the
allocator's choice and so it does not reach a home, which makes whether a home's
address may be written as a value **[open]** — §12 item 16.

The words `in` and `writethrough` join the surface, and **they are words rather than
reservations** (§3.1). A variable may still be called either of them: `var in: u16`
declares one, a declaration whose home is a data item called `in` is written
`var x: u16 in in`, and the printer writes that back as `var x: u16 in $in` — the
second `in` is where a name stands, the first is where the surface's word does, and
every name carries the `$` that says it is one (§3.1.1). `in` at the start of a
statement is still the port instruction, still refused with its reason (§11); the
roles never stand in the same place, which is the argument the whole surface rests on
(§3.1).

**How much of this is built.** The declaration is read, the static rules above are
checked, a store into a cell more than one variable declares is warned about at the
store, and the default mode is honoured: a value with no register left is given its home,
and every read of it is then a load and its one definition is followed by a store, through
a register picked for that one access. Two things stop a home from being used, and both
are refusals rather than guesses. A home the program writes while the value is alive
cannot hold the value, which is the rule two paragraphs above. And moving a value in and
out of a home needs a register at the point it is read or written: the allocator frees one
where it can, by moving a value alive at that point into a home of its own, and refuses the
program only when no value in the way has one to go to. `writethrough` is still **refused**
until every
definition writes the cell, because compiling it as if the bytes were never written is a
wrong answer the program is not told about, and a hard error is what this compiler gives
instead. A value wider than a register cannot use a home either: the access a home is moved
with is one register's worth at most, and this back end cannot name a pair (§3.4).

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
* **A byte value lives in the low half of a register.** The surface has no
  half-registers: a value *is* a register, and a `u8` or `i8` value takes one whose
  low byte has a name — `al`, `bl`, `cl`, `dl`, so `ax`, `bx`, `cx` or `dx`. An
  instruction that reads or writes the value names that half, and which of the four
  it is is the compiler's choice, not something the surface says. So a byte value has
  fewer places to live than a word: `si`, `di` and `bp` cannot hold one at all, and a
  program with four byte values alive at once has run out of registers (§8.2). A
  memory access one byte wide is ordinary: `c = byte [p]` and `byte [p] = c` are one
  load and one store, of the width the value has (§3.4). Taking the low byte of a wider
  value is the narrowing conversion of §3.5, and it is one move at most.
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
  `MOVZX` and `MOVSX` only arrived with the 386. The sequence is the machine's own: the
  byte goes into `al`, `xor ah, ah` clears the top half or `cbw` fills it from the sign,
  and the answer is copied out of `ax`. The copies that turn out to move a register into
  itself are the allocator's to drop, so a widening whose value is already in `ax` costs
  one instruction and nothing more.
* **Narrowing costs a move at most, and nothing to compute.** `byte x` is the low byte of
  `x`, and the low byte of a value is already in the low half of the register the value is
  in — so the narrowing is a copy of that half into wherever the result goes, and no
  instruction computes anything. That is why the two directions have different words
  rather than one bracket: one of them is free and the other is not (§3.2).
* **[open]** a widening into a value wider than a register. `movzx` from `u8` to `u32` is one
  conversion by the rule above, and on this machine the answer is two registers: nothing in
  the back end can name a pair, so it is refused. What a double word *is* — two registers,
  or something the target describes — is the same question as §12 item 6's far pointer.
* **[open]** what a conversion does to the flags. A narrowing is a move and touches nothing,
  so for it the answer is "nothing" — but widening is an instruction that does touch them, and
  the compiler currently assumes that *any* conversion disturbs them, which is more than it has
  to. Which flags each one leaves is the target's to say (§4.2), and the answer belongs behind
  the target's flag effects rather than in the IR.

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
| 1 | `~` `-` (prefix) |
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
* **Unary minus, `-d`** — one operation, the one the machine calls `NEG`. It is not
  a new meaning: `NEG`'s flags are the flags of subtracting from zero, `CF`
  included, so `-d`, `0 - d` and the instruction `neg d` are one operation and it
  is the zero in `d = eval(0 - d)` that carries no information. It binds like `~`
  (level 1, where a prefix can only be a prefix), which also settles how `-1` and
  `-d` are told apart: by position, the same way `a - b` and `-b` are.
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

```
jmp there              ; to a label in this module
jmp 0x0000:0x7E00      ; to another segment: out of this image
```

**A far jump is a statement of its own**, not a line inside an inline block, and the
reason is what the graph gets out of it: the compiler knows it leaves. Nothing after
it runs, so nothing after it is reachable, and a block that follows it is dead code
rather than a fall-through. A block ending in the same instruction cannot say that —
a block goes on to the next item as far as the compiler is concerned (§9) — which is
exactly the kind of fact worth giving a statement.

Both numbers are one word, and the offset is **not a label**: a far pointer wants the
label's place *within the segment*, which nothing here knows until the assembler has
placed it ([`docs/asm.md`](asm.md) §4).

`ret` ends the program's path, and it promises nothing about the registers: what
it leaves behind is the allocator's business, not an effect of the program (§2.3).
A far jump promises even less: it leaves the segment.

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
* The labels the sugar invents are written `..@lbl0`, `..@lbl1`, and so on — the
  compiler's own namespace (§3.1.1), numbered in the order they are created, so
  that the output is the same on every run (`AGENTS.md`, invariant 6). They used
  to be written `$lbl0`, which stopped being right the moment `$` became the
  author's marker, and which could not be read back at all.
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

### 7.3 Instruction-shaped statements — [decided]

An operation may be written the way the machine writes it, with the destination
spelled out instead of implied by the `=`:

```
add s, 1            ; exactly  s = eval(s + 1)
sub s, t            ; exactly  s = eval(s - t)
and s, 0xff         ; exactly  s = eval(s & 0xff)
adc s, 1            ; exactly  s = eval(s adc 1)
shl s, 1            ; exactly  s = eval(s shl 1)
neg s               ; exactly  s = eval(-s)
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
**one operation, with exactly the operands the operation takes and no nesting**:
`add s, 1 + 1` is a tree and belongs in `expr` (§5.4), and `mov` is the only word
here that is not an operation at all — it is the bare assignment of §5.3, which
the surface already spells with `=`.

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

Every word is answered by the target, which is where the reason for each refusal
lives as well (`Target.statementOperator`, `Target.statementProblem`): whether a
word names the operation it looks like is a fact about the machine, and on another
machine the answer would be different.

**A register name is just a name.** `mov ax, 1` writes a variable called `ax`, because
this surface has no registers: they are written in an inline block (§9), and the
pinning of §12 item 12 does not exist. The printer says which was meant — that line
comes back as `$ax = 1` — so a program brought over from an assembler is not
mistaken about it, and nothing here is refused for its name (§3.1).

**[open]** one shape is not recognised yet, and it costs bytes: `d = eval(0 - d)`
is the same operation as `d = eval(-d)` and is still emitted as building a zero and
subtracting it — seven bytes against two. Recognising it is a form for the
subtraction whose first operand is the literal zero, which is the target's business
like every other encoding choice.

**[open]** whether any of this should reach the assembler-facing side too — an
`asm` block, or a `.8086`-style directive — or stay a statement form only.

## 8. Storage state and the stack

### 8.1 The machine's own registers — [decided]

A module sets its own machine state up, and the surface spells that as a statement of its
own:

```
movreg ds, 0
movreg ss, 0
movreg sp, 0x7C00
movreg es, 0xB800
movreg ds, cs
movreg bp, 0x1000
```

`movreg` takes one of the registers a value cannot live in — on this machine `ds`, `es`,
`ss`, `sp` and `bp` — and what to put there: a literal, a variable, an address, or another
register the machine has. Most of those are a sequence rather than one instruction, because
a segment register takes no immediate: `movreg ds, 0` is `mov ax, 0` and then
`mov ds, ax`, while `movreg sp, 0x7C00` is one instruction. The target description is what
says so, like every other choice of instruction.

**That list is a rule, and the rule is why this is a statement at all.** A standalone write
to a register is safe exactly when **no value can be in that register**: then the write
cannot be overwritten by anything else, and nothing has to be kept anywhere. If a value
*could* be there, a write that has to survive until some later statement reads it would be
**pinning** — a value held in a named register across a stretch of code — and that is a
different question, still open (§12 item 12). So `movreg` writes the machine's own
registers and no others, and the ones a value can live in are written by the `with` clause
of the statement that uses them (§11), which does the write and the read inside one item
and needs nothing pinned.

**The word is there because the name cannot say it.** `ds` is the machine's register, and
`ds` may also be a variable of the program's, so a statement that begins with that name
cannot tell the reader which one it is: a place, an `=`, and a name that could be either
(§3.1). `mov ds, 0` does not settle it either, because `mov d, s` is already how this
surface spells an assignment (§7.3) — the same two readings, in assembly clothing. A word
of its own does settle it, and what that buys is that **nothing is reserved**:
`var ds: u16` declares a variable like any other name, `ds = 0` assigns it, and only
`movreg` reaches the register. `flags` stays the one predeclared name (§4.1).

A name in the second position is read the way the assembly text reads one (§3.1.1): a bare
name this machine has a register for **is** that register, and a name the author wants is
written `$cs`. Canonical text therefore has no name that could be two things, because the
printer writes the `$` on everything the author chose. So `movreg ds, cs` copies the code
segment — which is how a loader that was loaded somewhere else picks up the segment it is
actually running in — and a program that has a variable called `cs` writes
`movreg ds, $cs` to put that variable there.

Writing one of these registers is an effect like a store and not a definition of a value,
so SSA renames nothing about it (§2.3), and it leaves the flags alone.

**The other direction is specified and not built.** A program also needs to *read* a
register into a value, and the case that matters is the one a boot loader meets at entry:
the BIOS hands the drive number over in `dl`, and today nothing outside an inline block can
name it. That direction is `movreg drive, dl` — a value on the left and a register on the
right — and it is safe for the same reason the write is: the read *defines* the value, and
the register is free the moment the copy is made, so there is no interval to keep. Nothing
is built for it yet; the parser refuses it and says so.

**`bp` is on the list on purpose, and the reason is worth writing down.** The allocator
does not use it: it is left out of the register classes because it is where a frame pointer
would go (§8.2), and because taking it now would mean giving it back later. Since no value
is ever allocated there, a module may use it — and a hand-written block is exactly what
wants to, since `bp` is the one register the compiler will never touch. The day a function
that spills exists, that decision is the one to revisit: today nothing spills, so `sp` and
`bp` are the program's, like every other register in the image it is setting up.


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
program that needs more registers than six is refused (`README.md`, *Status*). The
refusal is not a heuristic giving up: the allocator colours a graph, so "six is not
enough" is a theorem about that graph, and the message names the values that were
alive at the same time — which is the answer to "why not", and the thing a program
has to change.

This marker is about the **stack**, and §3.1.2's homes are about memory the program
declared, so the two are different promises: a function that may not spill still has
its declared homes to put a value in, because those bytes are the program's own and
not something the compiler invented. What that means for the open question here is
that the spelling does not have to say anything about them.

**[decided] each definition is one life.** The form gives every definition a name of
its own, and the allocator is told only which names have to share a register: the ones
a φ joins, and the two ends of a copy whose source dies there (`RegisterAllocator`,
[`docs/ssa.md`](ssa.md) §8). So a name written on both sides of a call is two values
with two lives, and the register the first one used is free again after the call — the
second may live in a register the call destroys, because the call happens before it
exists. What is *not* two lives is a value that really is live across the call: it is
one name, kept out of everything the call destroys until it is read (§11.1).

This is what a name's life being a question about the form answers, and the earlier
attempt to answer it by splitting intervals in the allocator is why the entry stayed
[open] for so long: that version moved a value into the register a shift count arrives
in, because the shift sequence shifts its source in place and the source is alive
across the instruction that sets the count. Nothing splits an interval now. A value
that reads and writes itself — `x = eval(x + 1)` — is one instruction because its two
names are a copy whose source dies at it, which is a rule about copies rather than
about lifetimes.

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

**A block may have labels of its own**, on a line of their own:

```
asm clobbers(ax, flags) {
    mov ax, 0x0201
retry:
    int 0x13
    jc retry
}
```

That is what a retry loop needs, and there was no way to write one: a block could
only branch to labels of the module, so the loop's tail had to be outside the
block it belonged to. The label is reachable from the block and nowhere else — an
IR branch cannot see it — but its *name* is a name in the image, because the
emitted text is one flat file. Two blocks may not both call a label `again`, and
the compiler refuses that rather than letting the assembler report it
({@code docs/asm.md} §3).

**[open]** a branch inside a block is not checked against anything: `jmp nowhere`
inside a block is a name like any other operand, and the assembler is what
notices. Checking it here would refuse programs this compiler accepts today, since
a block may name a label of the module as well as one of its own.

**A block goes on to the next item as far as the compiler is concerned.** A block
ending in `hlt`, or in the far jump that hands control to a kernel, goes nowhere,
and treating it as falling through is conservative rather than wrong: the extra
edge may keep code alive that nothing reaches, and it never removes code that
something does. Asking the target whether an instruction can return is worth doing
the day it costs something.

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
jmp: dw handler1, handler2      ; labels, so these are addresses
buf: pad 32
```

**[decided]** There is no separate "uninitialised" storage class, and there is
nothing beyond the image. Reserved space is **zero bytes emitted into the
image**, and it is written with `pad`, which is bytes that exist in the image and
mean nothing:
```
buf:  pad 32             ; 32 zero bytes, named
sled: pad 400, 0x90      ; 400 bytes of 0x90: a NOP sled
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

Because the concept is "bytes in the image", naming it `resb`/`resw`/`resd` would
be actively misleading: in NASM those do *not* grow the file, which is the
opposite of what happens here.

A label may name a piece of padding, and then it is an address like any other:
`buf: pad 32` makes `buf` the address of the padding, usable as `p = buf` or
`[buf + 2]`.

**A `dw` list may name labels, and then each is its address.** That is how a
pointer table, a vector table or a jump table is written:

```
tbl: dw  handler1, handler2, 0x1234
```

The value is an **address**, and like `org` and `pad to` it is not something the
front end can work out: an address depends on where everything lands. So the IR
states it and the assembler resolves it, which is also why the names are checked
by the verifier rather than while reading — the label may be defined after the data
that names it.

Three things about the form are deliberate:

* **A label is written plainly**, `dw msg`, without `offset`. A data item's value
  *is* a constant, and a label's value *is* its address, so there is nothing to
  disambiguate; `dw offset msg` is refused and the complaint says so.
* **It belongs in a `dw` list.** An address is one word wide, so it does not fit in
  `db`, and a `dd` list would be a far pointer — segment *and* offset — which this
  surface cannot express yet.
* **A variable is not a candidate.** A variable is a register, not an address, and
  naming one is refused rather than turned into whatever the allocator chose. That
  reason is about the allocator's choice, so it does not reach a variable that was
  given a home in memory by its declaration (§3.1.2): such a variable *does* have an
  address — the one the program named. Whether the surface may then write that
  address is a separate decision, and is **[open]** in §12 item 16.

### 10.3 `pad to` — laying out an image that has a fixed shape — [decided]

The second form says how long the image is, rather than how many bytes to add:

```
org  0x7c00

start:
    ...

pad  to 510
 dw   0xAA55            ; the last two bytes of a 512-byte sector
```

* **The number is relative to the start of the image, not an address.** An image
  says how it is laid out, and `org` says where it lands; those are two different
  facts, and mixing them makes the layout depend on the load address. The three
  lines above are a boot sector at `0x7C00`, a ROM at `0xF000`, and a test image
  at `0x100`.
* **Only the assembler can resolve it.** The front end writes text, not bytes, and
  how long the code before it is depends on encodings it never chose — the size of
  a displacement, whether a branch had to grow. A pass that selects a shorter
  instruction changes the answer too, which is why this cannot be a byte list or a
  number a person works out.
* **It is where a limit is enforced.** A boot sector that has grown past 510 bytes
  cannot be padded back, so `pad to 510` is a hard error at that point, with both
  numbers in the message. Nothing else needs to know that a boot sector is 512
  bytes.

**[open]** how to say "the image is a multiple of N bytes" — a payload that has to
be whole sectors — and the alignment spelling that goes with it. `pad to` reaches a
length, not a multiple, so it is a different construct and it is not in v1.

**[open]** whether anything beyond the image — absolute placement, a `.COM` "BSS"
past the end of the file — is ever offered. It is not in v1 and it is not promised.
`pad to` is image-relative on purpose: an absolute form would make a binary's
layout depend on where it is loaded.

**[open]** sections, modules, `extern`/`global` and a link step — deliberately
out of v1.

## 11. Target-provided operations — [decided]

Operations that are not arithmetic: `in`/`out`, `int`, `hlt`, `cli`, `sti`,
`nop`, `iret`, the `setcc` family, and the carry consumers. On a target that does
not provide one, using it is an **unsupported-input hard error** — never a silent
substitution.

**Six of them are statements now**, and they are written the way the machine writes
them:

```
cli
int 0x10                       ; destroys everything, because silence cannot promise more
int 0x13 clobbers(ax, bx, cx, dx, flags)
sti
hlt
nop
iret
```

What the target provides is a table, and the statement form exists for the thing a
block cannot say: **the compiler understands it**. A block is opaque in both
directions — it makes a whole module unoptimisable (§9) — while one of these says
exactly what it does: it is an effect, and it carries a list of what it destroys,
which is what an allocator and SSA need and all they need.

Three things about the form are deliberate:

* **No register operands outside a clause.** Why these six and not `in`/`out`: an operation that
  reads or writes a register the machine names has nowhere to put it unless the statement
  says where, and the `with` clause below is that — for the arguments of an interface. What
  is still missing after it is a value that has to *stay* in a register across a stretch of
  code, which is pinning (§12 item 12).
* **The clobber list is optional, and silence means everything.** Only the program
  knows what an interrupt handler keeps, so a target's honest answer for `int` is
  "all of it", and a value that has to live across one is refused until the author
  says what is really destroyed. That refusal is the compiler asking a question
  rather than guessing: `int 0x10` alone will not let a value live across it, and
  `int 0x10 clobbers(ax, dx, flags)` will.
* **The list is written back**, so the canonical form says what the compiler will
  assume on the author's behalf, and a re-read statement gets the list it was read
  with.
* **The list is believed.** It is a promise about code the compiler cannot see, so a
  list that names too little is a promise broken, and the result is a value the
  handler was trusted to keep. That is true of every inline-assembly facility there
  has ever been, and the reason the default is the worst case rather than the
  friendliest: silence is the only answer the target can give honestly.

**A statement that is an interface may be given its registers.** A BIOS call wants its
arguments where the machine wants them, and the surface says so on the statement that
consumes them:

```
int 0x13 clobbers(ax, bx, cx, dx) with ah = 0x02, dl = 0x80, bx = buffer
jmp 0x0000:0x7E00 with dl = drive
asm clobbers(ax) with al = c {
    int 0x10
}
```

The clause is a list of `register = operand`, the word `with` introduces it, and what it
means is a sequence: those operands are put into those registers and then the statement
runs. On this machine a statement that gives a register is the machine statement, the far
jump and an inline block, because those are the statements with an outside world to talk
to.

**It is not pinning, and the reason is that all of it happens inside one item.** Nothing can
be allocated in the middle of a sequence, and the statement defines no value, so nothing can
take a register between the write and the statement that reads it. A *standalone* write to a
register could not promise that — which is exactly why §8.1's `movreg` writes only the
registers a value cannot live in. A value that has to be in a register across a stretch of
code is still not spellable, and that is §12 item 12.

* **Any register the target has** may be named, because the write and the read are one item:
  `ah`, `dl`, `bx`, `si` and the segment registers are all ordinary here. This is the
  difference between the clause and `movreg`, and it is the whole of it.
* **A bare name in the register position is the machine's**, and the author's variable of
  that name is written `$ax` — the rule the assembly text has (§3.1.1), and the same one
  `movreg`'s source follows.
* **The operands are ordinary operands**: a literal, a variable, a label, an address. The
  width rule is the one assignments have — both sides the same width, and a literal takes the
  width of the register it goes into (§3.2).
* **What the statement leaves in those registers is not a value.** The registers are the
  statement's, and afterwards they hold whatever it left there — for `int 0x13`, the BIOS's
  answer. Reading one back into a value is `movreg`'s other direction (§8.1).

**Not built**, and refused as such rather than mis-parsed: a clause is a shape this compiler
recognises and does not implement yet.

### 11.1 What to do when a value has to live across a call — [decided]

Three ways, and a real boot loader uses all three:

```
; 1. say what the handler keeps, and let the allocator find a register
int 0x13 clobbers(ax, bx, cx, dx, flags)

; 2. or keep the value in memory, and read it into a name of its own afterwards
word [save] = n
int 0x10
m = [save]

; 3. or save and restore inside a block, and declare what is left destroyed
asm clobbers(bx, cx, dx, flags) {
    push ax
    int 0x10
    pop ax
}
```

The second one is what hand-written boot code does most of the time, and it is why
it so rarely needs to preserve anything: state goes in memory, and a register holds
it only for the instruction that needs it. Note the two names in it: the value that
spans the call cannot be given a register the call destroys, so writing it to memory
and reading it into a name of its own afterwards is what makes the route work — the
second name is a new life, and the call is behind it ([`docs/ssa.md`](ssa.md) §8).

**This route can also be declared rather than written.** `var m: u16 in save`
(§3.1.2) gives a variable that home, so the store and the load are the compiler's to
place, the fresh name the route needs is the compiler's business, and the program
uses `m` like any other name.

`pusha` and `popa` are **not 8086 instructions** — they arrived with the 80186 — so
they are not statements this target can provide, and a block that writes them is
writing 186 code. The third route above is their 8086 equivalent, and the first one
is usually better than either: the allocator does the saving by choosing a register
the handler keeps, which costs no instruction at all.

**[open]** whether the four that take no immediate and clobber nothing, `hlt` in
particular, should say that they leave — `hlt` waits for an interrupt and carries on,
so it stays in the block it is in, and `iret` leaves without the graph knowing. Both
are conservative in the same direction as a block (§9), which is why neither is
urgent.

## 12. Open questions

Collected for greppability; each is marked **[open]** at its point of use above.

1. Spelling for naming an older flag value, and the flag capture form (§4.4).
2. How much of `lahf`/`sahf`/`pushf` is exposed directly (§4.4).
3. The no-spill marker's spelling (§8.2).
4. Inline assembly operand binding for variables, and inputs/outputs (§9).
5. Whether string operations and `jcxz` get a surface (§11).
6. Whether anything beyond the image is ever offered (§10) — which real boot code
   needs, at `0x0413`, `0x046C` and `0xB800:0000`, and which §3.1.2's `in`
   deliberately does not reach — the alignment form that reaches a multiple rather
   than a length (`align`), and whether `dd` can hold a far pointer — a segment and
   an offset — which is the other half of §10.2's `dw` label.
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

    **The interface half is now spelled**, by the `with` clause of §11: a statement that
    talks to the outside world can be given its arguments, and because the write and the
    read are inside one item, nothing is pinned to give it them. `movreg` (§8.1) is the
    other direction, reading a register into a value, which needs no pinning either.

    What is still missing is a value that has to **stay** in a register across a stretch
    of code — a loop that keeps its argument in `dl`, a value the compiler must not move
    while something outside it runs. That is a pre-coloured live range, and it is the
    item. What is decided about it stands: it names a register at a boundary rather than
    letting a program address the register file, and a pinned value cannot be spilled
    (§8.2).
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
16. Whether a variable that has a home in memory (§3.1.2) may have that home's
    address written as a value — `p = x`, `dw x` — which §10.2 refuses for a
    variable, and refuses for the reason that the address would be whatever the
    allocator chose. A home is named by the program, so that reason is gone, and what
    is left is a link-time constant of the same kind as a label's address — where
    item 8 already stands. Answering it means deciding whether a home *is* a label
    that a variable happens to have, or a second kind of thing that has an address of
    its own.
17. Whether an instruction's operand should say whether it is a register or a name.
    It cannot say today, and the assembly text needs to know: there a bare name
    spelled like a register *is* the register, so a symbol of that name is written
    `$ax` and the printer decides by asking the target what a register is called
    (`docs/asm.md` §3, §3.1.1 above). That is right for everything the compiler writes
    and wrong for a label the author named `ax` and branched to — a program the
    surface allows, because nothing is reserved (§3.1). Reading a program back needs
    the spelling to be enough on its own, and here it is not.
18. A home whose bytes something else writes. `writethrough` keeps the *memory* current
    on every write, which is what a reader outside the module needs, and says nothing
    about the other direction: the compiler may still hold a copy of a value it read.
    For a cell a handler or a device updates — the timer's counter at 0x046C, a byte a
    handler rewrites — no copy may be held at all, which is what `volatile` means for
    an access (§3.4) and what a variable cannot say yet. Whether that is a third mode,
    or the same idea applied to the variable rather than to the declaration, is the
    question.

## 13. Non-goals for v1

Floating point and the 8087; aggregates; 64-bit arithmetic; dynamic memory; a
software multiply/divide library; `&&`/`||` in conditions; modules and linking;
a general disassembler.
