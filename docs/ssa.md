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

An item that gives the flags **up** — a value computed with `expr`, a conversion,
or an inline block that declares it clobbers them ([`docs/ir.md`](ir.md) §4.2,
§9) — leaves the flags' undefined value in force, `flags#undef` (§5). Nothing can
read it, because the surface refuses a read of a flag nobody defined, so the state
is recorded faithfully and never used.

**No φ for the flags can appear today.** A φ for them would need a flags value
that is live across a join, and the surface's flags rule refuses every program
where one could be read: a label clears the flags, so a branch after a join needs
a definition *after* that label ([`docs/ir.md`](ir.md) §4.3). The rule is a linear
scan because there is no graph yet; when it becomes a question about reaching
definitions, a join that needs the flags will materialise them, and that is where
they stop being a special case.

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
`README.md` says the same list. Three of them, in this order: **constant
propagation**, which writes a known value where it is read; **dead value
elimination**, which removes what nothing can observe; and **unread flags**,
which lets an operation whose flags nobody reads stop claiming them — and that one
has to be last, because "nobody reads them" is a question about the program the
other two have finished shaping.

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

What the passes leave is turned back into a module, because the back end works on
the surface and not on versions ([`OutOfSsa`](../src/main/java/i8086/ssa/OutOfSsa.java)).

In most compilers this step is where the trouble is: φ's become copies, the copies
on one edge have to happen at once, cycles among them need a temporary, and an edge
that leaves a block with two ways out has to be split first. None of that is needed
here, and the reason is one sentence:

> **every operand of a φ is a version of the same variable the φ defines.**

So renaming each version back to the variable it belongs to turns every φ into
`x = x`. The value the φ would have produced is the value the variable already
holds on that path — that is what "the version reaching the end of the
predecessor" means — so the copy is not merely skippable but unnecessary, and an
edge with two ways out has nothing edge-specific left to place. A φ that carried a
value *between* variables would need all of that machinery; this IR has no such
construct, and does not want one.

The price is precision rather than correctness: the variable is one mutable name
again, so the allocator sees one interval where the form had three. That is the
shape the input had and the shape the back end was written for
(`docs/ir.md` §3.1).

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
  removed — but no pass lifts a load into a value. Doing that is the aliasing
  question ([`docs/ir.md`](ir.md) §3.4, §12 item 15) and not a missing loop: until
  something can say when two accesses are the same memory, a load is not reusable.
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
