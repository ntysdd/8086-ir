; The same job as examples/mbr7.ir, hand-written: scan the four partition entries for the
; bootable one, build a disk address packet, read one sector to 0x7E00 with int 0x13, check
; its signature, and hand over in DL. Written the way a competent author would, not golfed:
; labels, no clever tricks.
;
; It is here to be the comparison in README.md's worked example, so that the number there
; can be checked. It is 75 bytes of code where the compiler's output is 76, and the byte is
; worth naming: it reads one half of the partition entry, stores it, and only then reads the
; other, so a single register holds both words and both stores get the three-byte
; register-direct form. The compiler cannot reorder a store past a load on its own, because
; nothing yet says the packet and the partition entry are different memory (docs/ir.md
; §3.4) — but writing examples/mbr7.ir's two stores the same way, one next to the load it
; stores, produces the same 75 bytes. The order is the program's to give and the compiler's
; to keep.
;
; Nothing in the build reads this file: it is a reference, not input.

        bits 16
        org 0x7C00

        cli
        xor ax, ax
        mov ds, ax
        mov es, ax
        mov ss, ax
        mov sp, 0x7C00
        sti

        mov bx, parts
        mov cx, 4
.find:
        cmp byte [bx], 0x80
        je .found
        add bx, 16
        loop .find
        hlt

.found:
        ; the packet is data now
        mov ax, [bx + 8]
        mov [dap + 8], ax
        mov ax, [bx + 10]
        mov [dap + 10], ax
        mov si, dap
        mov ah, 0x42
        mov dl, 0x80
        int 0x13
        jc .failed
        cmp word [0x7DFE], 0xAA55
        jne .failed
        mov dl, 0x80
        jmp 0x0000:0x7E00

.failed:
        mov ah, 0
        mov dl, 0x80
        int 0x13
        hlt

dap:    db 0x10, 0, 1, 0, 0x00, 0x7E, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
parts:  times 0x1BE - ($ - $$) db 0
        db 0x80, 0, 1, 0, 0x83, 0, 0, 0
        dw 1, 0, 1, 0
        times 0x1FE - ($ - $$) db 0
        dw 0xAA55
