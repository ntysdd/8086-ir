# The assembly text

Status: **draft**. This document is the description of record for the assembly
text: the form the emitter writes, the form the bundled `asm` assembler reads,
and the form an inline assembly block contains. `docs/ir.md` is the description
of record for the IR surface, `README.md` says what the project is, and
`AGENTS.md` says how to work on it.

Marks mean the same as in `docs/ir.md`: **[decided]**, **[proposed]**,
**[open]** — and a construct is proposed and approved here before it is
implemented.

---

## 1. What this text is, and who owns what

The assembly text is a contract between three things:

* the emitter, which writes it;
* the bundled assembler, which reads it back, encodes it and places it;
* the promise in `README.md` that the output is *ordinary, readable 8086
  assembly — the kind of code you would otherwise write by hand*.

That third point is why the text is deliberately an ordinary Intel-syntax
assembly rather than a private notation: destination operand first,
`[bx+si+0x10]` for memory, `byte`/`word` prefixes for operand size, `;` for
comments. It is written so that a person who knows 8086 assembly can read the
output without a manual, and so that handing it to another assembler is at
least plausible rather than absurd.

**Syntax lives here; vocabulary lives in the target.** This document defines
structure: what a line, an operand and a memory reference look like. Which
mnemonics exist, which registers exist, what they are called, and how each form
is encoded are facts about the target and live in the target description
(`AGENTS.md`, invariant 2). A syntax file that listed 8086 mnemonics would be
target knowledge in the wrong place.

Two things are tested rather than assumed: the emitter's output must always be
re-assemblable, and the assembler's decoder and encoder are inverses on every
encoding the emitter can produce (`AGENTS.md`, invariant 5).

## 2. Lines and comments — [decided]

The text is line-oriented. **One statement per line**, and a newline ends the
statement: there is no continuation character and no separator, so anything that
does not fit on one line is written on one line.

* A **label** is a name followed by `:` and stands on its own line.
* An **instruction** is a mnemonic, then zero or more operands separated by
  commas.
* A **directive** is a data word or `org`; see §6.

A comment runs from `;` to the end of the line, and may follow a statement or
stand on its own.

```
main:
    mov  ah, 9              ; DOS: print a string
    mov  dx, offset msg
    int  21h
    ret

msg: db "Hello, world!$"
```

There is no control-flow sugar in this text. `.if`/`.while` belong to the IR and
are normalised away by the IR parser (`docs/ir.md`, §7.2); an inline assembly
block is a straight sequence of instructions.

## 3. Names, numbers, strings — [decided]

* **Names** are `[A-Za-z_][A-Za-z0-9_]*`, or that with a leading `.` for the
  words that begin with one. **Names are case-insensitive**: `MOV`, `mov` and
  `Mov` are the same mnemonic, and `Counter` and `counter` are the same label.
  The emitter always writes lowercase, so the output does not depend on how the
  input was typed.
* **Numbers** are decimal (`26`) or hexadecimal with a `0x` prefix (`0x1A`,
  `0x1a`). A leading `-` is a unary minus applied by the operand parser, not part
  of the literal, so literals themselves are non-negative.
  `[decided]` there is no MASM-style `1Fh` suffix and no `0b` prefix; an input
  using them is an unsupported-input error, not a guess.
* **Strings** are `"..."` and carry no escape sequences, so a `"` cannot appear
  inside one; a quote byte is written `db 0x22`. Bytes outside ASCII are
  rejected rather than encoded in some assumed character set.
  `[proposed]` this no-escape rule, and whether a doubled `""` should be allowed
  as an escaped quote.

## 4. Operands — [decided]

An operand is one of four shapes. Which of them a given mnemonic accepts is the
target's business, not this document's.

```
ax                  a register, named by the target
0x10                an immediate
offset msg          the address of a label, as an immediate
[bx+si+0x10]        memory
```

A **memory operand** is `[` contents `]`, where the contents are zero or one
base register (`bx` or `bp`), zero or one index register (`si` or `di`), and an
optional displacement, separated by `+` or `-`. `[msg]` and `[0x1234]` are a
displacement alone. The constraint that only `bx`/`bp` may be a base and only
`si`/`di` an index is the 8086's, and is therefore the target's to enforce.

A **segment override** names a segment register before the bracket:
`es:[bx]`, `ss:[bp-2]`.

```
mov  ax, [msg]
mov  [bx+si+2], ax
mov  es:[bx], al
mov  dx, offset msg
```

`[proposed]` whether a displacement may be an arithmetic expression
(`[bx+si+len*2]`) or only a number, a name, or a name plus a number.

## 5. Size and encoding — [decided]

**The `byte`, `word` and `dword` prefixes state an operand's size, and they are
required exactly when no other operand implies it.**

```
mov  ax, 1                  ; size follows from ax
mov  word [p], 1            ; nothing else implies it, so it is written
mov  byte [p], 1
```

The prefix is a statement about *meaning* — how many bytes the instruction
touches — not a request for a particular encoding.

**Byte-level encoding is the assembler's job.** For a given form, the assembler
picks the shortest encoding: whether a displacement takes the 8-bit or the
16-bit form, and whether an immediate takes the sign-extended 8-bit form or the
full-width one. It has to be the assembler's job, because the answer can depend
on a value that is only known once everything is placed — the address of a
forward-referenced label, most obviously.

Two rules make that deterministic and keep the knowledge in the target:

* Among encodings of equal length, **the one the target description lists first
  wins**. The preference order is target data, not a tie-breaking hack inside
  the encoder.
* A value that fits no encoding of the requested form is a **hard error**, never
  a silent widening (`AGENTS.md`, invariant 7).

**Jump relaxation** follows from the same objective: `jmp` and the `jcc` family
use the shortest form that reaches their target, which means code size changes
as labels are placed, which can make more jumps short. The assembler iterates
that to a fixed point, deterministically.

## 6. Directives — [decided]

Only what a single-segment image needs.

```
org  0x100                  where the image is loaded
db   0x55, 0xAA             bytes
dw   0x1234                 words
dd   0x00012345             double words
db   "Hello, world!$"       a string, one byte per character
```

`[open]` the repeat form for zero-filled space — `db 32 dup(0)` or
`times 32 db 0` — which `docs/ir.md` §10 requires and leaves open, and whether
`align` is needed.

## 7. Not in this syntax — [decided]

Macros and conditional assembly; sections; `extern`/`global` and a link step;
floating point; any control-flow sugar. Everything here is out of scope for v1
(`docs/ir.md`, §1 and §13).

## 8. Open questions

1. Whether a string may contain an escaped quote (§3).
2. Whether a displacement may be an arithmetic expression (§4).
3. The repeat form for zero-filled space, and `align` (§6).
