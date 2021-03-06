/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.cellspu;

import org.jikesrvm.VM_SizeConstants;
import org.vmmagic.unboxed.Address;

/**
 *--------------------------------------------------------------------------
 *                     Stackframe layout conventions
 *---------------------------------------------------------------------------
 *
 * A stack is an array of "slots", declared formally as 4 bytes on the cell-spu
 * architectures, each slot containing either a primitive (byte, int, float, etc),
 * an object pointer, a machine code pointer (a return address pointer), or a
 * pointer to another slot in the same stack (a frame pointer). The interpretation
 * of a slot's contents depends on the current value of IP, the machine instruction
 * address register.
 * Each machine code generator provides maps, for use by the garbage collector,
 * that tell how to interpret the stack slots at "safe points" in the
 * program's execution.
 *
 * Here's a picture of what a stack might look like in memory.
 *
 * Note: this (array) object is drawn upside down compared to other objects
 * because the hardware stack grows from high memory to low memory, but
 * array objects are laid out from low memory to high (header first).
 * <pre>
 *  hi-memory
 *                 +===============+
 *                 |  LR save area |
 *                 +---------------+
 *                 |     MI=-1     |   <-- "invisible method" id
 *                 +---------------+
 *             +-> |     FP=0      |   <-- "end of vm stack" sentinel
 *             |   +===============+ . . . . . . . . . . . . . . . . . . . . . . . . . . .
 *             |   |   saved FPRs  |  \                                                  .
 *             |   +---------------+   \_nonvolatile register save area                  .
 *             |   |   saved GPRS  |   /                                                 .
 *             |   +---------------+  /                                                  .
 *             |   |   (padding)   |  <--optional padding so frame size is multiple of 16 .
 *             |   +---------------+                                       .
 *             |   |   operand0    |  \                                                  .
 *             |   +---------------+   \_operand stack (++)                              .
 *             |   |   operand1    |   /                                                 .
 *             |   +---------------+  /   
 *             |   |   (padding)   |  <--optional padding so frame size is multiple of 16 .
 *             |   +---------------+                                                       .
 *             |   |   local0      |  \                                                  .
 *             |   +---------------+   \_local variables (++)                            .
 *             |   |   local1      |   /                                                 .
 *             |   +---------------+  /                                                          ..frame
 *             |   |     ...       |                                                     .
 *             |   +---------------+                                                     .
 *             |   |    spill1     |  \                                                  .
 *             |   +---------------+   \_parameter spill area                            .
 *             |   |    spill0     |   /                                                 .
 *             |   +===============+  /                                                  .
 *             |   |               |   <-- spot for this frame's callee's return address .
 *             |   +---------------+                                                     .
 *             |   |     MI        |   <-- this frame's method id                        .
 *             \   +---------------+                                                     .
 *      FP ->      |   saved FP    |   <-- this frame's caller's frame                   .
 *                 +===============+ . . . . . . . . . . . . . . . . . . . . . . . . . . .
 * th.stackLimit-> |     ...       | \
 *                 +---------------+  \_guard region for detecting & processing stack overflow
 *                 |     ...       |  /
 *                 +---------------+ /
 *                 |(object header)|
 *  low-memory     +---------------+
 *
 * note: (++) means "baseline compiler frame layout and register
 * usage conventions"
 *
 * </pre>
 */
public interface VM_StackframeLayoutConstants {

  int LOG_BYTES_IN_STACKSLOT = VM_SizeConstants.LOG_BYTES_IN_ADDRESS;
  int BYTES_IN_STACKSLOT = 1 << LOG_BYTES_IN_STACKSLOT;

  int STACKFRAME_HEADER_SIZE = 3 * BYTES_IN_STACKSLOT; // size of frame header, in bytes

  int STACKFRAME_NEXT_INSTRUCTION_OFFSET = BYTES_IN_STACKSLOT;
  int STACKFRAME_METHOD_ID_OFFSET =  2 * BYTES_IN_STACKSLOT;

  int STACKFRAME_FRAME_POINTER_OFFSET = 0;    // base of this frame

  Address STACKFRAME_SENTINEL_FP = Address.fromIntSignExtend(-2); // fp value indicating end of stack walkback
  int INVISIBLE_METHOD_ID = -1; // marker for "assembler" frames that have no associated VM_Method

  // Stackframe alignment.
  // Align to 16 byte boundary for good load store performance
  //
  int STACKFRAME_ALIGNMENT = VM_SizeConstants.BYTES_IN_QUAD;

  // Sizes for stacks and sub-regions thereof.
  // Values are in bytes and must be a multiple of 8 (size of a stack slot on 64-architecture).
  //
  int STACK_SIZE_GROW = 4 * 1024; // how much to grow normal stack when overflow detected
  int STACK_SIZE_GUARD = 4 * 1024; // max space needed for stack overflow trap processing
  int STACK_SIZE_GCDISABLED = 2 * 1024; // max space needed while running with gc disabled
  int STACK_SIZE_MAX = 128 * 1024; // upper limit on stack size (includes guard region)
 
  // Complications:
  // - STACK_SIZE_GUARD must be greater than STACK_SIZE_NATIVE or STACK_SIZE_GCDISABLED
  //   to ensure that frames allocated by stack growing code will fit within guard region.
  // - STACK_SIZE_GROW must be greater than STACK_SIZE_NATIVE or STACK_SIZE_GCDISABLED
  //   to ensure that, if stack is grown prior to disabling gc or calling native code,
  //   the new stack will accommodate that code without generating a stack overflow trap.
  // - Values chosen for STACK_SIZE_NATIVE and STACK_SIZE_GCDISABLED are pure guess work
  //   selected by trial and error.
  //

  // Initial stack sizes:
  // - Stacks for "normal" threads grow as needed by trapping on guard region.
  // - Stacks for "collector" threads are fixed in size and cannot grow.
  // - Stacks for "boot" thread grow as needed - boot thread calls JNI during initialization
  //
  int STACK_SIZE_NORMAL = STACK_SIZE_GUARD + STACK_SIZE_GCDISABLED + 16 * 1024;
  int STACK_SIZE_COLLECTOR = STACK_SIZE_NORMAL;

  // TODO - fix Stack_Size_Boot
  int STACK_SIZE_BOOT = STACK_SIZE_NORMAL;
  
}



