#include "zerobox_core.h"

extern "C" {

#if defined(__aarch64__)
__attribute__((naked, noinline))
long zerobox_raw_syscall(
    long number,
    long arg0,
    long arg1,
    long arg2,
    long arg3,
    long arg4,
    long arg5
) {
    __asm__ volatile(
        "mov x8, x0\n"
        "mov x0, x1\n"
        "mov x1, x2\n"
        "mov x2, x3\n"
        "mov x3, x4\n"
        "mov x4, x5\n"
        "mov x5, x6\n"
        "svc #0\n"
        "ret\n"
        ".global zerobox_raw_syscall_end\n"
        "zerobox_raw_syscall_end:\n"
        "nop\n"
    );
}

#elif defined(__arm__)
__attribute__((naked, noinline))
long zerobox_raw_syscall(
    long number,
    long arg0,
    long arg1,
    long arg2,
    long arg3,
    long arg4,
    long arg5
) {
    __asm__ volatile(
        "mov ip, sp\n"
        "stmfd sp!, {r4, r5, r6, r7}\n"
        "mov r7, r0\n"
        "mov r0, r1\n"
        "mov r1, r2\n"
        "mov r2, r3\n"
        "ldmfd ip, {r3, r4, r5}\n"
        "svc #0\n"
        "ldmfd sp!, {r4, r5, r6, r7}\n"
        "bx lr\n"
        ".global zerobox_raw_syscall_end\n"
        "zerobox_raw_syscall_end:\n"
        "nop\n"
    );
}

#else
#include <unistd.h>
long zerobox_raw_syscall(
    long number,
    long arg0,
    long arg1,
    long arg2,
    long arg3,
    long arg4,
    long arg5
) {
    return syscall(number, arg0, arg1, arg2, arg3, arg4, arg5);
}

void zerobox_raw_syscall_end() {}
#endif

} // extern "C"
