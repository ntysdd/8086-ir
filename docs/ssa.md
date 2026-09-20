# The SSA form

Status: **implemented**. This document is the description of record for the
*internal* SSA form — what a module becomes on its way to instruction selection.
[`docs/ir.md`](ir.md) describes the surface a person writes, [`docs/asm.md`](asm.md)
the assembly that comes out, and `README.md` what the project is; this is what is
in between. Every claim carries one of three marks:

* **[decided]** — agreed; implementable as written.
* **[proposed]** — designed, spelled out, but not yet confirmed.
* **[open]** — named and known to be missing, not designed yet.

It is **not a surface**. What the dump writes cannot be read back: a version name
contains a character no identifier may, and a φ is a construct the IR surface does
not have. The surface's round trip, `parse(print(ir)) == ir`, is about
`docs/ir.md` and is unaffected — building the SSA form of a module does not modify
the module (`AGENTS.md`, invariant 5). `8086-ir ssa FILE.ir` prints the form, and
that dump is a debugging aid.

## 1. Shape — [decided]

The graph is the one `Cfg` reads off the item list: a block is a run of items
entered only at its top, and an edge is a jump, a branch or a fall-through. What
the form adds is the content of the blocks:

* a block's **φ's** come first, in the order they were placed;
* then its **statements**, each of which is an item from the module with every
  variable renamed, plus the flags version it defines if it defines one;
* and three tables, because a name alone does not say what it is: version →
  variable, version → type, version → where it was written.

Every version is defined exactly once. A use names the definition that reaches
it, and nothing else.

    block2 ($lbl1) <- block0 block1:
        i#3 = phi(block0: i#1, block1: i#5)
        flags#4 = cmp i#3, n#2
        jc $lbl0

## 2. Versions — [decided]

Renaming gives every definition a fresh version, `x#3`, numbered in creation
order across the whole module, and every use the version in force at that point.
The renaming walks the **dominator tree**, not the graph: walking the tree is what
makes the versions in force at a point exactly the ones that dominate it, which is
the SSA property itself.

An item that both reads and writes a variable reads before it writes —
`x = eval(x + 1)` reads the old version and defines a new one ([`docs/ir.md`](ir.md)
§5.1) — so a version's own definition is never one of its reads.

The numbering is a function of the module alone: blocks are walked in the order
they were read, and φ's are placed in the order the variables were declared, so
the same input gives the same version numbers every time (`AGENTS.md`,
invariant 6).

## 3. φ's — [decided]

A φ takes **one operand per predecessor, in the order the predecessors are in**.
That order is why a block's predecessor list is part of the form and not a detail
of how the graph was built: arriving from the third predecessor makes the variable
the third operand, and nothing else can tell you that.

A φ is placed where a variable's definitions meet — at a block in the dominance
frontier of a block that defines it — and only where the variable is **live** on
the way in. A φ nobody reads costs a register and buys nothing, so the form is
pruned rather than minimal: a value defined on both arms of an `if` and never read
afterwards gets no φ at all.

A block nothing reaches is renamed on its own, with nothing in force, and no φ is
placed in it. Its versions cannot escape, because the only way a version leaves a
block is as a φ operand.

## 4. The flags — [decided]

`flags` is renamed like any other variable ([`docs/ir.md`](ir.md) §4.1), so what
defines them is numbered with everything else, and a statement whose *only* result
is the flags says so:

    flags#4 = cmp i#3, n#2

A statement that also defines a value does not repeat itself — `i#3 = eval(...)`
defines the flags too, and the version it leaves is what a later branch reads,
since there is only ever one of those at a time.

An item that gives a flag **up** — a value computed with `expr`, a conversion,
or an inline block, which declares its registers and nothing about them ([`docs/ir.md`](ir.md)
§4.2, §9) — leaves that flag's undefined value in force, `flags#undef` (§5). Nothing can
read it, because the surface refuses a read of a flag nobody defined, so the state
is recorded faithfully and never used.

There are three flags, `flags`, `carry` and `direction`, and they are renamed apart
(§4 of [`docs/ir.md`](ir.md)). A statement may define one, the other, both, or neither, and
what it defines is written beside it:

    flags#2, carry#3 = cmp x#1, 0x80
    direction#4 = cld

An operation leaves the conditions and the carry, except that an increment or a decrement leaves the
carry exactly as it found it — which is why it is a name of its own ({@code docs/ir.md} §4.1).

Everything that reads a flag names the one it means: a branch reads the arithmetic flags, an
operation that asks for the carry reads those, and a string operation reads `direction`
([`docs/ir.md`](ir.md) §11).

**A φ for a flag does appear, now that the direction flag is its own name.** A copy reads the
flag that says which way it walks, and a program that decides the direction on one path and the
other on another needs the two merged like any other value:

    block3 (copy) <- block1 block2:
        direction#7 = phi(block1: direction#5, block2: direction#6)
        rep movsb with cx = 0x200, si = src#2, di = dst#3

It costs no instruction, because a flag is machine state and not a register: the φ says which
value the form believes in, and the machine's own flag is what the branch or the copy reads. What
it took was the flag half of the bookkeeping — the φ has to be a *definition* of the flag for the
passes that count readers, or the pass that removes what nothing reads removes the definition of a
flag that is read.

The arithmetic flags still get none: the surface's rule refuses every program where one could be
read, because a label clears them ([`docs/ir.md`](ir.md) §4.3), and that rule is a linear scan
because it is older than the graph. A join that needs *them* will get the same treatment when that
rule is asked of the form, and that is where they stop being a special case.

## 5. The value with no definition — [decided]

A variable may be read before anything writes it: the surface has no
definite-assignment rule, and adding one is [open] ([`docs/ir.md`](ir.md) §3.1). So
the form has to be able to say "nothing defines this", and it says it with a name
per variable: **`x#undef` is the undefined value of `x`**.

* **What it means.** The value is not known to the compiler. It is not zero, not
  any other particular value, and not a promise that the variable is uninitialised
  at run time — on this machine the read is of whatever the register happens to
  hold.
* **What a pass may not assume.** Two occurrences of `x#undef` are **not** the
  same value. The allocator decides where `x` lives and inline assembly may write
  it, so `x#undef - x#undef` is not zero and may not be folded, two of them may
  not be commoned up, and neither may be treated as a constant known to be equal
  to itself.
* **Why per variable.** A single anonymous `undef` would say that something is
  unknown without saying what, and the verifier's question is per variable —
  "exactly one version of *this* variable reaches this use" — so a value that does
  not name its variable cannot be checked. The name is never taken apart to find
  the variable back; the mapping is recorded, the same way a version's variable is
  recorded rather than derived from its spelling (`AGENTS.md`: strings are not
  data structures).
* **What it is not.** It is a value, not a state: it appears only as an operand,
  and nothing ever defines it.

The surface's flag-undefinedness is spelled the same way (`flags#undef`, §4), and
is a different thing wearing the same word: a read of it is a hard error, not a
value of unknown content. Today that never shows, because the surface rule refuses
such a read before the form exists. If that rule is ever relaxed to a dataflow
question, the two have to be told apart, and the flags one becomes what LLVM calls
*poison* rather than *undef*.

One more thing a pass may not assume, and it is not about `undef`: a value whose
only use is inside an inline assembly block is **live**, because a block declares
the registers it destroys but not the values it reads ([`docs/ir.md`](ir.md) §2.3,
§9). Until it can declare them, liveness is taken conservatively across a block,
and a dead-value pass has to respect that.

### Why there is no poison — [decided]

LLVM needs `poison` because one IR has to serve machines that disagree: a shift by
more than the width of the type has one answer on x86 and another on ARM, and
neither is a fault, so the value is marked as bad rather than defined. The 8086 is
the only target here, and the hardware's answer *is* the definition, so there is
nothing to leave undecided.

What is left is the operations that really do fault on this machine — division,
and a quotient that does not fit (`#DE`). Those are handled by a rule about
*speculation* rather than by a value: the compiler never introduces a fault the
program did not already have ([`docs/ir.md`](ir.md) §2.2, §5.5). An operation that
can fault may not be hoisted, duplicated or speculated with, and nothing needs to
carry a mark saying so, because the operation itself says it.

The contrast is worth stating because the words are borrowed: an `undef` here is
what LLVM calls an `undef`, and cannot be replaced by a concrete value; there is
no `freeze`, because there is no propagation to stop — a pass that does not
understand `x#undef` simply declines to touch it, which is always safe. LLVM's own
documentation calls `undef` deprecated and keeps it for exactly one purpose,
representing a load of uninitialised memory, which is the purpose served here
(LLVM, *Undefined Behavior Manual*, `llvm.org/docs/UndefinedBehavior.html`).

## 6. What is verified — [decided]

`SsaVerifier` checks the property, not the naming: **exactly one version of a
variable reaches every use**. It works out which definitions reach each point the
way execution would — everything a predecessor leaves behind arrives, a definition
replaces every version of the variable it defines, a loop has to agree with
itself, so the computation repeats until it settles — and refuses a point where
two versions arrive with no φ to merge them. A missing φ is exactly that, and it
is the failure a dominance check would accept: the version read may well be one
that dominates the use, it is just not the only one that arrives.

Around that:

* every version is defined exactly once, so a name means one thing;
* every φ has one operand per predecessor, and each is a version of the variable
  the φ merges;
* every name in an item is a version, an undefined value, or a label — a variable
  still standing under its declared name is one name meaning several values, which
  is the ambiguity renaming exists to remove;
* every φ operand is what its predecessor actually leaves behind, which is the
  only way to check that a φ was filled in from the right edge;
* a statement's flags version agrees with the item it belongs to.

What it does **not** check is whether reading the flags was legitimate. That is
the surface's own rule, enforced before this point, and a second implementation of
it would be a second thing to be wrong ([`docs/ir.md`](ir.md) §4.3). Nothing in the
verifier knows a target, a register or an instruction.

## 7. What the passes do with it — [decided]

The passes that read this form are listed in `i8086.pass.Pipeline`, and
`README.md` says the same list. Four of them, in this order: **constant
propagation**, which writes a known value where it is read; **load folding**,
which takes away a load whose only reader is the comparison beside it and lets
that comparison carry the access ([`docs/ir.md`](ir.md) §5.4); **dead
value elimination**, which removes what nothing can observe; and **unread flags**,
which lets an operation whose flags nobody reads stop claiming them — and that one
has to be last, because "nobody reads them" is a question about the program the
others have finished shaping.

Three things about working on it are worth stating here, because they are
properties of the form rather than of any one pass:

* **Every pass runs on a verified form and has its output verified.** The
  pipeline verifies once at the start and after each pass, which is the first
  invariant and the reason a pass does not verify itself.
* **A pass builds the form it means.** The form is a value; a pass that changes
  one thing answers with a new one, and the version tables follow the content —
  deleting a statement deletes the version it defined, and a version nothing
  defines is not a version (`SsaForm.rewriting`). A pass may not invent a
  version.
* **What a pass may not assume is written down.** Two occurrences of a variable's
  undefined value are not the same value (§5), and while a module contains an
  inline block nothing in it may be removed (§5, and [`docs/ir.md`](ir.md) §2.3).
  The second is why dead value elimination reads a form with a block in it as
  "everything is used": keeping a value costs registers, and removing one the
  block reads costs the program.
* **A pass may not throw away information the IR still needs.** The clearest case
  is a width: `[0x32] = x` is sixteen bits because `x` is, so replacing that `x`
  with a literal leaves a statement with no width at all
  ([`docs/ir.md`](ir.md) §3.4). A pass that folds may only do it where something
  else in the statement still says how wide it is — which is the same reason a
  comparison of two known values keeps one of them. The verifier is what catches
  it when a pass gets this wrong, and it does so by refusing the pass's own output,
  which is the first invariant doing its job rather than an inconvenience.

## 8. Leaving SSA — [decided]

The form is what the back end reads. Selection selects from it, so the instructions it
emits name versions, and a φ is not an instruction but a constraint on the allocator —
which is the whole of what a φ has to say here:

> **every operand of a φ is a version of the same variable the φ defines.**

In most compilers this step is where the trouble is: φ's become copies, the copies on
one edge have to happen at once, cycles among them need a temporary, and an edge that
leaves a block with two ways out has to be split first. None of that is needed here,
and the sentence above is why. Renaming each version back to the variable it belongs
to turns every φ into `x = x`: the value the φ would have produced is the value the
variable already holds on that path — that is what "the version reaching the end of
the predecessor" means — so the copy is not merely skippable but unnecessary, and an
edge with two ways out has nothing edge-specific left to place. A φ that carried a
value *between* variables would need all of that machinery; this IR has no such
construct, and does not want one.

So the versions are told apart, or brought together, and what does it is the allocator:

* **A φ's names are one life.** The register *is* what carries the value along each path,
  so the values a φ joins have to be in it. Selection read the φ's and passes them on
  with the instructions, because that is a fact about the stream and not something the
  allocator can see for itself.
* **A φ is a definition and a set of reads, and neither is an instruction.** The name it
  defines is defined where its block is entered, and each operand is read at the end of
  the predecessor it arrives from — which is what "the version reaching the end of the
  predecessor" means. Where the two happen is a third thing Selection passes on, and it is
  not a detail: a value whose only definition is a φ would otherwise look like a value with
  no definition at all and be live from the start of the program, and a value arriving along
  the back edge of a loop would look live along every other edge into it too. Both make the
  pressure a loop costs larger than the loop is.
* **A copy's two ends are one life when the source dies there** — {@code mov d, s} with
  nothing left to read of {@code s} afterwards. Then {@code d} may as well *be*
  {@code s}: the copy becomes a register moved into itself, and the rule that drops those
  takes it away. That is what keeps a two-address machine from paying for a copy per
  operation: {@code x = eval(x + 1)} is one instruction and not two.
* **Everything else stays apart.** Two versions of one variable that neither meet at a φ
  nor are a copy of each other are two values, and putting them in one register would
  cost a register and buy nothing. That is the precision this has over renaming every
  version back to its variable, which is what the back end used to do.
* **For a person**, {@code OutOfSsa} still writes the form with the versions merged, and
  that is what {@code optimize --emit ir} prints.

## 9. Not here yet

* **Flag materialisation.** The README's pipeline has SSA construction
  materialising a flag value — through a target-declared expansion — when one
  would have to survive an instruction that defines those flags. It does not,
  because the target does not yet state its flag effects per flag
  ([`docs/ir.md`](ir.md) §4.2), and until it does there is nothing to ask. Nothing
  needs it today: no pass reasons about flags across an instruction that
  clobbers them.
* **Promoting memory.** Loads and stores are emitted and reasoned about — a store
  is an effect, a volatile access is one too, and a plain load nobody reads is
  removed — but no pass lifts a load into a value, and the one piece of load
  elimination that exists goes the other way: a load whose only reader is the
  comparison beside it stops being a value at all ({@code LoadFolding}). Doing the
  rest is the aliasing question ([`docs/ir.md`](ir.md) §3.4, §12 item 15) and not a
  missing loop: until something can say when two accesses are the same memory, a
  load is not reusable.
* **An inline block that says what it reads.** Until it can, a module containing
  one is optimised conservatively (§4, §7). That is the missing half of
  `docs/ir.md` §9.
* **The rest of the pass list.** The three of §7 are what exists; copy
  propagation, value numbering, load elimination, loop-invariant code motion and
  branch simplification are named in `README.md` and not written. Reassociating an
  expression, folding a comparison, and the identities (`x + 0`,
  `x * 1`) are all waiting on the same thing: a pass has to be able to ask
  what the flags of an operation are worth, and today it can only ask whether
  anybody reads them.
