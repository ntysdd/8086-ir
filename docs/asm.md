# The assembly text

Status: **draft, and in force for what an inline block contains**. This document is
the description of record for the assembly text of this project: the form an inline
assembly block contains, and the form an assembler of our own would one day read.
[`docs/ir.md`](ir.md) is the description of record for the IR surface, `README.md`
says what the project is and what exists, and `AGENTS.md` says how to work on it.

The text the compiler **writes out** is a different matter: it is written in NASM's
dialect, because the pieces of a flat binary are an assembler's business and NASM is
the assembler (`nasm -f bin`). The two dialects differ in four places, each for a
reason, and those four are the whole of the translation (`i8086.asm.Dialect`):

| this document | NASM | why |
|---|---|---|
| `mov dx, offset msg` | `mov dx, msg` | NASM has no `offset`: a bare symbol is already the address there, and a bracketed one is what it points at |
| `mov es:[bx], al` | `mov [es:bx], al` | NASM puts a segment override inside the brackets |
| `pad 32, 0x90` | `times 32 db 0x90` | `pad` is this project's word for bytes in the image |
| `pad to 510` | `times 510-($-$$) db 0` | only the assembler knows how long the code before it is |

Our own grammar does not change to suit a tool, and the tool does not have to read
a grammar nobody else speaks. `pad to` has no spelling that is both NASM's and free
of NASM's expression language, which is exactly why there are two dialects and not
one.

**The assembler this document is ultimately for is not written yet.** What is
exercised today is the writing half: the syntax below, the mnemonics and operand
shapes the target lists, and the size prefixes. Everything about reading it back —
encodings, the shortest-encoding rule, label relaxation — is decided and untested,
and belongs to a component that has not been written.

Marks mean the same as in `docs/ir.md`: **[decided]**, **[proposed]**,
**[open]** — and a construct is proposed and approved here before it is
implemented.

---

## 1. What this text is, and who owns what

The assembly text is a contract between three things:

* the emitter, which writes it;
* the assembler that reads it back, encodes it and places it — NASM today, and one
  of our own if that is ever worth writing;
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

Two things are requirements on the assembler rather than observations about it,
because the component they belong to does not exist yet: its decoder and encoder
are inverses on every encoding the emitter can produce (`AGENTS.md`, invariant 5),
and the text the emitter writes is text it can read. Both are what the tests will
check the day it is written; neither is checked today, because today there is
nothing to check.

## 2. Lines and comments — [decided]

The text is line-oriented. **One statement per line**, and a newline ends the
statement: there is no continuation character and no separator, so anything that
does not fit on one line is written on one line.

* A **label** is a name followed by `:`. It stands on its own line, with one
exception: a labelled data definition writes the label and the definition on
one line, as `msg: db "..."` below, because the label and the bytes it names
are one thing. A label before an instruction is not allowed; the instruction
goes on the next line.
* An **instruction** is a mnemonic, then zero or more operands separated by
  commas.
* A **directive** is a data word or `org`; see §6.

A comment runs from `;` to the end of the line, and may follow a statement or
stand on its own.

```
main:
    mov  ah, 9              ; DOS: print a string
    mov  dx, offset msg
    int  0x21
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
* Two prefixes are the compiler's and the author's, and they are the same two the
  IR surface has (`docs/ir.md` §3.1.1), taken from NASM: `..@` begins a name the
  compiler generated, and `$` in front of a name is the author saying "this is
  mine, not one of the mnemonics or registers". The `$` is a marker rather than
  part of the name — `$ax` and `ax` name the same symbol — and a two-dot name
  without the `@` is refused rather than read as an ordinary name, because in an
  assembler that reads this text `..lbl0` is a local label and means something
  else again.
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

An operand is one of five shapes. Which of them a given mnemonic accepts is the
target's business, not this document's.

```
ax                  a register, named by the target
0x10                an immediate
offset msg          the address of a label, as an immediate
0x0000:0x7E00       a far pointer, segment:offset — the operand of a far jump
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
jmp  0x0000:0x7E00
```

`[decided]` a **far pointer** is two numbers with a colon between them, and the
colon is what makes it far — the machine's far jump takes exactly that, an
immediate pointer, and NASM reads the same spelling, so nothing is translated. It is
written inside an inline assembly block, which is where a boot loader's last act
lives; there is no statement for it, because it goes nowhere this module knows.

`[open]` a far pointer whose offset is a **label**, `jmp 0:kernel`. It needs the
label's offset *within the segment*, which is not the address a label has until the
assembler has placed it, and every use so far wants a number.

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
dw   handler1, handler2     a label is its address, so a dw list is a table
```

`[decided]` a label in a `dw` list means its address, and it is written plainly:
`dw msg`, not `dw offset msg` (`docs/ir.md` §10.2). The same spelling is what NASM
wants, so unlike the `offset` of an operand this one needs no translation.

`[decided]` the repeat form for space in the image is `pad`, spelled the same as
the IR's (`docs/ir.md` §10.2, §10.3): `pad 32`, `pad 400, 0x90`, and `pad to 510`
to reach a fixed length. In NASM's dialect — which is what the emitter writes —
these are `times 32 db 0` and `times 510-($-$$) db 0`. Whichever dialect, the
layer that finally knows how many bytes each instruction took is the one that
resolves `pad to`, and that is the assembler.

`[open]` `align`, which reaches a multiple rather than a length, and is a different
construct from either form above.

## 7. Not in this syntax — [decided]

Macros and conditional assembly; sections; `extern`/`global` and a link step;
floating point; any control-flow sugar. Everything here is out of scope for v1
(`docs/ir.md`, §1 and §13).

## 8. Open questions

1. Whether a string may contain an escaped quote (§3).
2. Whether a displacement may be an arithmetic expression (§4).
3. Whether a displacement may be an arithmetic expression (§4).
