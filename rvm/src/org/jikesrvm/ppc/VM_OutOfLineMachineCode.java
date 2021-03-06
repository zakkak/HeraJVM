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
package org.jikesrvm.ppc;

import org.jikesrvm.ArchitectureSpecific;
import org.jikesrvm.VM;
import org.jikesrvm.compilers.common.assembler.VM_ForwardReference;
import org.jikesrvm.compilers.common.assembler.ppc.VM_Assembler;
import org.jikesrvm.compilers.common.assembler.ppc.VM_AssemblerConstants;
import org.jikesrvm.jni.ppc.VM_JNIStackframeLayoutConstants;
import org.jikesrvm.objectmodel.VM_ObjectModel;
import org.jikesrvm.runtime.VM_ArchEntrypoints;
import org.jikesrvm.runtime.VM_Entrypoints;
import org.jikesrvm.scheduler.VM_Processor;
import org.vmmagic.unboxed.Offset;

/**
 * A place to put hand written machine code typically invoked by VM_Magic
 * methods.
 *
 * Hand coding of small inline instruction sequences is typically handled by
 * each compiler's implementation of VM_Magic methods.  A few VM_Magic methods
 * are so complex that their implementations require many instructions.
 * But our compilers do not inline arbitrary amounts of machine code.
 * We therefore write such code blocks here, out of line.
 *
 * These code blocks can be shared by all compilers. They can be branched to
 * via a jtoc offset (obtained from VM_Entrypoints.XXXInstructionsMethod).
 *
 * 17 Mar 1999 Derek Lieber
 *
 * 15 Jun 2001 Dave Grove and Bowen Alpern (Derek believed that compilers
 * could inline these methods if they wanted.  We do not believe this would
 * be very easy since they return thru the LR.)
 */
public abstract class VM_OutOfLineMachineCode
    implements VM_BaselineConstants, VM_JNIStackframeLayoutConstants, VM_AssemblerConstants {
  public static void init() {
    reflectiveMethodInvokerInstructions = generateReflectiveMethodInvokerInstructions();
    saveThreadStateInstructions = generateSaveThreadStateInstructions();
    threadSwitchInstructions = generateThreadSwitchInstructions();
    restoreHardwareExceptionStateInstructions = generateRestoreHardwareExceptionStateInstructions();
    invokeNativeFunctionInstructions = generateInvokeNativeFunctionInstructions();
  }

  @SuppressWarnings("unused")
  // Accessed via VM_EntryPoints
  private static ArchitectureSpecific.VM_CodeArray reflectiveMethodInvokerInstructions;
  @SuppressWarnings("unused")
  // Accessed via VM_EntryPoints
  private static ArchitectureSpecific.VM_CodeArray saveThreadStateInstructions;
  @SuppressWarnings("unused")
  // Accessed via VM_EntryPoints
  private static ArchitectureSpecific.VM_CodeArray threadSwitchInstructions;
  @SuppressWarnings("unused")
  // Accessed via VM_EntryPoints
  private static ArchitectureSpecific.VM_CodeArray restoreHardwareExceptionStateInstructions;
  @SuppressWarnings("unused")
  // Accessed via VM_EntryPoints
  private static ArchitectureSpecific.VM_CodeArray invokeNativeFunctionInstructions;

  // Machine code for reflective method invocation.
  // See also: "VM_Compiler.generateMethodInvocation".
  //
  // Registers taken at runtime:
  //   T0 == address of method entrypoint to be called
  //   T1 == address of gpr registers to be loaded
  //   T2 == address of fpr registers to be loaded
  //   T4 == address of spill area in calling frame
  //
  // Registers returned at runtime:
  //   standard return value conventions used
  //
  // Side effects at runtime:
  //   artificial stackframe created and destroyed
  //   R0, volatile, and scratch registers destroyed
  //
  private static ArchitectureSpecific.VM_CodeArray generateReflectiveMethodInvokerInstructions() {
    VM_Assembler asm = new ArchitectureSpecific.VM_Assembler(0);

    //
    // free registers: 0, S0
    //
    asm.emitMFLR(0);                                         // save...
    asm.emitSTAddr(0, STACKFRAME_NEXT_INSTRUCTION_OFFSET, FP); // ...return address

    asm.emitMTCTR(T0);                          // CTR := start of method code

    //
    // free registers: 0, S0, T0
    //

    // create new frame
    //
    asm.emitMR(S0, FP);                  // S0 := old frame pointer
    asm.emitLIntOffset(T0, T4, VM_ObjectModel.getArrayLengthOffset()); // T0 := number of spill words
    asm.emitADDI(T4, -BYTES_IN_ADDRESS, T4);                  // T4 -= 4 (predecrement, ie. T4 + 4 is &spill[0] )
    int spillLoopLabel = asm.getMachineCodeIndex();
    asm.emitADDICr(T0, T0, -1);                  // T0 -= 1 (and set CR)
    VM_ForwardReference fr1 = asm.emitForwardBC(LT); // if T0 < 0 then break
    asm.emitLAddrU(0, BYTES_IN_ADDRESS, T4);                  // R0 := *(T4 += 4)
    asm.emitSTAddrU(0, -BYTES_IN_ADDRESS, FP);                  // put one word of spill area
    asm.emitB(spillLoopLabel); // goto spillLoop:
    fr1.resolve(asm);

    asm.emitSTAddrU(S0, -STACKFRAME_HEADER_SIZE, FP);     // allocate frame header and save old fp
    asm.emitLVAL(T0, INVISIBLE_METHOD_ID);
    asm.emitSTWoffset(T0, FP, Offset.fromIntSignExtend(STACKFRAME_METHOD_ID_OFFSET)); // set method id

    //
    // free registers: 0, S0, T0, T4
    //

    // load up fprs
    //
    VM_ForwardReference setupFPRLoader = asm.emitForwardBL();

    for (int i = LAST_VOLATILE_FPR; i >= FIRST_VOLATILE_FPR; --i) {
      asm.emitLFDU(i, BYTES_IN_DOUBLE, T2);                 // FPRi := fprs[i]
    }

    //
    // free registers: 0, S0, T0, T2, T4
    //

    // load up gprs
    //
    VM_ForwardReference setupGPRLoader = asm.emitForwardBL();

    for (int i = LAST_VOLATILE_GPR; i >= FIRST_VOLATILE_GPR; --i) {
      asm.emitLAddrU(i, BYTES_IN_ADDRESS, S0);                 // GPRi := gprs[i]
    }

    //
    // free registers: 0, S0
    //

    // invoke method
    //
    asm.emitBCCTRL();                            // branch and link to method code

    // emit method epilog
    //
    asm.emitLAddr(FP, 0, FP);                                    // restore caller's frame
    asm.emitLAddr(S0, STACKFRAME_NEXT_INSTRUCTION_OFFSET, FP);   // pick up return address
    asm.emitMTLR(S0);                                            //
    asm.emitBCLR();                                                // return to caller

    setupFPRLoader.resolve(asm);
    asm.emitMFLR(T4);                          // T4 := address of first fpr load instruction
    asm.emitLIntOffset(T0, T2, VM_ObjectModel.getArrayLengthOffset()); // T0 := number of fprs to be loaded
    asm.emitADDI(T4,
                 VOLATILE_FPRS << LG_INSTRUCTION_WIDTH,
                 T4); // T4 := address of first instruction following fpr loads
    asm.emitSLWI(T0, T0, LG_INSTRUCTION_WIDTH); // T0 := number of bytes of fpr load instructions
    asm.emitSUBFC(T4, T0, T4);                // T4 := address of instruction for highest numbered fpr to be loaded
    asm.emitMTLR(T4);                           // LR := """
    asm.emitADDI(T2, -BYTES_IN_DOUBLE, T2);    // predecrement fpr index (to prepare for update instruction)
    asm.emitBCLR();                            // branch to fpr loading instructions

    setupGPRLoader.resolve(asm);
    asm.emitMFLR(T4);                          // T4 := address of first gpr load instruction
    asm.emitLIntOffset(T0, T1, VM_ObjectModel.getArrayLengthOffset()); // T0 := number of gprs to be loaded
    asm.emitADDI(T4,
                 VOLATILE_GPRS << LG_INSTRUCTION_WIDTH,
                 T4); // T4 := address of first instruction following gpr loads
    asm.emitSLWI(T0, T0, LG_INSTRUCTION_WIDTH); // T0 := number of bytes of gpr load instructions
    asm.emitSUBFC(T4, T0, T4);                  // T4 := address of instruction for highest numbered gpr to be loaded
    asm.emitMTLR(T4);                          // LR := """
    asm.emitADDI(S0, -BYTES_IN_ADDRESS, T1);   // predecrement gpr index (to prepare for update instruction)
    asm.emitBCLR();                            // branch to gpr loading instructions

    return asm.makeMachineCode().getInstructions();
  }

  // Machine code to implement "VM_Magic.saveThreadState()".
  //
  // Registers taken at runtime:
  //   T0 == address of VM_Registers object
  //
  // Registers returned at runtime:
  //   none
  //
  // Side effects at runtime:
  //   T1 destroyed
  //
  private static ArchitectureSpecific.VM_CodeArray generateSaveThreadStateInstructions() {
    VM_Assembler asm = new ArchitectureSpecific.VM_Assembler(0);

    // save return address
    //
    asm.emitMFLR(T1);               // T1 = LR (return address)
    asm.emitSTAddrOffset(T1, T0, VM_ArchEntrypoints.registersIPField.getOffset()); // registers.ip = return address

    // save non-volatile fprs
    //
    asm.emitLAddrOffset(T1, T0, VM_ArchEntrypoints.registersFPRsField.getOffset()); // T1 := registers.fprs[]
    for (int i = FIRST_NONVOLATILE_FPR; i <= LAST_NONVOLATILE_FPR; ++i) {
      asm.emitSTFD(i, i << LOG_BYTES_IN_DOUBLE, T1);
    }

    // save non-volatile gprs
    //
    asm.emitLAddrOffset(T1, T0, VM_ArchEntrypoints.registersGPRsField.getOffset()); // T1 := registers.gprs[]
    for (int i = FIRST_NONVOLATILE_GPR; i <= LAST_NONVOLATILE_GPR; ++i) {
      asm.emitSTAddr(i, i << LOG_BYTES_IN_ADDRESS, T1);
    }

    // save fp
    //
    asm.emitSTAddr(FP, FP << LOG_BYTES_IN_ADDRESS, T1);

    // return to caller
    //
    asm.emitBCLR();

    return asm.makeMachineCode().getInstructions();
  }

  /**
   * Machine code to implement "VM_Magic.threadSwitch()".
   *
   *  Parameters taken at runtime:
   *    T0 == address of VM_Thread object for the current thread
   *    T1 == address of VM_Registers object for the new thread
   *
   *  Registers returned at runtime:
   *    none
   *
   *  Side effects at runtime:
   *    sets current Thread's beingDispatched field to false
   *    saves current Thread's nonvolatile hardware state in its VM_Registers object
   *    restores new thread's VM_Registers nonvolatile hardware state.
   *    execution resumes at address specificed by restored thread's VM_Registers ip field
   */
  private static ArchitectureSpecific.VM_CodeArray generateThreadSwitchInstructions() {
    VM_Assembler asm = new ArchitectureSpecific.VM_Assembler(0);

    Offset ipOffset = VM_ArchEntrypoints.registersIPField.getOffset();
    Offset fprsOffset = VM_ArchEntrypoints.registersFPRsField.getOffset();
    Offset gprsOffset = VM_ArchEntrypoints.registersGPRsField.getOffset();

    // (1) Save nonvolatile hardware state of current thread.
    asm.emitMFLR(T3);                         // T3 gets return address
    asm.emitLAddrOffset(T2,
                        T0,
                        VM_Entrypoints.threadContextRegistersField.getOffset());         // T2 = T0.contextRegisters
    asm.emitSTAddrOffset(T3, T2, ipOffset);           // T0.contextRegisters.ip = return address

    // save non-volatile fprs
    asm.emitLAddrOffset(T3, T2, fprsOffset); // T3 := T0.contextRegisters.fprs[]
    for (int i = FIRST_NONVOLATILE_FPR; i <= LAST_NONVOLATILE_FPR; ++i) {
      asm.emitSTFD(i, i << LOG_BYTES_IN_DOUBLE, T3);
    }

    // save non-volatile gprs
    asm.emitLAddrOffset(T3, T2, gprsOffset); // T3 := registers.gprs[]
    for (int i = FIRST_NONVOLATILE_GPR; i <= LAST_NONVOLATILE_GPR; ++i) {
      asm.emitSTAddr(i, i << LOG_BYTES_IN_ADDRESS, T3);
    }

    // save fp
    asm.emitSTAddr(FP, FP << LOG_BYTES_IN_ADDRESS, T3);

    // (2) Set currentThread.beingDispatched to false
    asm.emitLVAL(0, 0);                                       // R0 := 0
    asm.emitSTBoffset(0, T0, VM_Entrypoints.beingDispatchedField.getOffset()); // T0.beingDispatched := R0

    // (3) Restore nonvolatile hardware state of new thread.

    // restore non-volatile fprs
    asm.emitLAddrOffset(T0, T1, fprsOffset); // T0 := T1.fprs[]
    for (int i = FIRST_NONVOLATILE_FPR; i <= LAST_NONVOLATILE_FPR; ++i) {
      asm.emitLFD(i, i << LOG_BYTES_IN_DOUBLE, T0);
    }

    // restore non-volatile gprs
    asm.emitLAddrOffset(T0, T1, gprsOffset); // T0 := T1.gprs[]
    for (int i = FIRST_NONVOLATILE_GPR; i <= LAST_NONVOLATILE_GPR; ++i) {
      asm.emitLAddr(i, i << LOG_BYTES_IN_ADDRESS, T0);
    }

    // restore fp
    asm.emitLAddr(FP, FP << LOG_BYTES_IN_ADDRESS, T0);

    // resume execution at saved ip (T1.ipOffset)
    asm.emitLAddrOffset(T0, T1, ipOffset);
    asm.emitMTLR(T0);
    asm.emitBCLR();

    return asm.makeMachineCode().getInstructions();
  }

  // Machine code to implement "VM_Magic.restoreHardwareExceptionState()".
  //
  // Registers taken at runtime:
  //   T0 == address of VM_Registers object
  //
  // Registers returned at runtime:
  //   none
  //
  // Side effects at runtime:
  //   all registers are restored except condition registers, count register,
  //   JTOC_POINTER, and PROCESSOR_REGISTER with execution resuming at "registers.ip"
  //
  private static ArchitectureSpecific.VM_CodeArray generateRestoreHardwareExceptionStateInstructions() {
    VM_Assembler asm = new ArchitectureSpecific.VM_Assembler(0);

    // restore LR
    //
    asm.emitLAddrOffset(REGISTER_ZERO, T0, VM_ArchEntrypoints.registersLRField.getOffset());
    asm.emitMTLR(REGISTER_ZERO);

    // restore IP (hold it in CT register for a moment)
    //
    asm.emitLAddrOffset(REGISTER_ZERO, T0, VM_ArchEntrypoints.registersIPField.getOffset());
    asm.emitMTCTR(REGISTER_ZERO);

    // restore fprs
    //
    asm.emitLAddrOffset(T1, T0, VM_ArchEntrypoints.registersFPRsField.getOffset()); // T1 := registers.fprs[]
    for (int i = 0; i < NUM_FPRS; ++i) {
      asm.emitLFD(i, i << LOG_BYTES_IN_DOUBLE, T1);
    }

    // restore gprs
    //
    asm.emitLAddrOffset(T1, T0, VM_ArchEntrypoints.registersGPRsField.getOffset()); // T1 := registers.gprs[]

    for (int i = FIRST_NONVOLATILE_GPR; i <= LAST_NONVOLATILE_GPR; ++i) {
      asm.emitLAddr(i, i << LOG_BYTES_IN_ADDRESS, T1);
    }

    for (int i = FIRST_SCRATCH_GPR; i <= LAST_SCRATCH_GPR; ++i) {
      asm.emitLAddr(i, i << LOG_BYTES_IN_ADDRESS, T1);
    }

    for (int i = FIRST_VOLATILE_GPR; i <= LAST_VOLATILE_GPR; ++i) {
      if (i != T1) asm.emitLAddr(i, i << LOG_BYTES_IN_ADDRESS, T1);
    }

    // restore specials
    //
    asm.emitLAddr(REGISTER_ZERO, REGISTER_ZERO << LOG_BYTES_IN_ADDRESS, T1);
    asm.emitLAddr(FP, FP << LOG_BYTES_IN_ADDRESS, T1);

    // restore last gpr
    //
    asm.emitLAddr(T1, T1 << LOG_BYTES_IN_ADDRESS, T1);

    // resume execution at IP
    //
    asm.emitBCCTR();

    return asm.makeMachineCode().getInstructions();
  }

  /**
   * Generate innermost transition from Java => C code used by native method.
   * on entry:
   *   JTOC = TOC for native call
   *   S0 = threads JNIEnvironment, which contains saved PR reg
   *   S1 = IP address of native function to branch to
   *   Parameter regs R4-R10, FP1-FP6 loaded for call to native
   *   (R3 will be set here before branching to the native function)
   *
   *   GPR3 (T0), PR regs are available for scratch regs on entry
   *
   * on exit:
   *  RVM JTOC and PR restored
   *  return values from native call stored in stackframe
   */
  private static ArchitectureSpecific.VM_CodeArray generateInvokeNativeFunctionInstructions() {
    VM_Assembler asm = new ArchitectureSpecific.VM_Assembler(0);

    // move native code address to CTR reg;
    // do this early so that S1 will be available as a scratch.
    asm.emitMTCTR(S1);

    //
    // store the return address to the Java to C glue prolog, which is now in LR
    // into transition frame. If GC occurs, the JNIGCMapIterator will cause
    // this ip address to be relocated if the generated glue code is moved.
    //
    asm.emitLAddr(S1, 0, FP);
    asm.emitMFLR(T0);
    if (VM.BuildForSVR4ABI || VM.BuildForMachOABI) {
      // save return address of JNI method in mini frame (1)
      asm.emitSTAddr(T0, STACKFRAME_NEXT_INSTRUCTION_OFFSET, S1);
    } else {
      if (VM.VerifyAssertions) VM._assert(VM.BuildForPowerOpenABI);
      // save return address in stack frame
      if (VM.BuildForPowerOpenABI) {
        asm.emitSTAddr(T0, -JNI_PROLOG_RETURN_ADDRESS_OFFSET, S1);
      }
    }

    //
    // Load required JNI function ptr into first parameter reg (GPR3/T0)
    // This pointer is an interior pointer to the VM_JNIEnvironment which is
    // currently in S0.
    //
    asm.emitADDI(T0, VM_Entrypoints.JNIExternalFunctionsField.getOffset(), S0);

    //
    // change the vpstatus of the VP to IN_NATIVE
    //
    asm.emitLAddrOffset(PROCESSOR_REGISTER, S0, VM_Entrypoints.JNIEnvSavedPRField.getOffset());
    asm.emitLVAL(S0, VM_Processor.IN_NATIVE);
    asm.emitSTWoffset(S0, PROCESSOR_REGISTER, VM_Entrypoints.vpStatusField.getOffset());

    //
    // CALL NATIVE METHOD
    //
    asm.emitBCCTRL();

    // save the return value in R3-R4 in the glue frame spill area since they may be overwritten
    // if we have to call sysVirtualProcessorYield because we are locked in native.
    if (VM.BuildFor64Addr) {
      asm.emitSTD(T0, NATIVE_FRAME_HEADER_SIZE, FP);
    } else {
      asm.emitSTW(T0, NATIVE_FRAME_HEADER_SIZE, FP);
      asm.emitSTW(T1, NATIVE_FRAME_HEADER_SIZE + BYTES_IN_ADDRESS, FP);
    }

    //
    // try to return virtual processor to vpStatus IN_JAVA
    //
    int label1 = asm.getMachineCodeIndex();
    asm.emitLAddr(S0, 0, FP);                            // get previous frame
    if (VM.BuildForSVR4ABI || VM.BuildForMachOABI) {
      // mini frame (2) FP -> mini frame (1) FP -> java caller
      asm.emitLAddr(S0, 0, S0);
    }
    asm.emitLAddr(PROCESSOR_REGISTER, -JNI_ENV_OFFSET, S0);   // load VM_JNIEnvironment
    asm.emitLAddrOffset(JTOC, PROCESSOR_REGISTER, VM_ArchEntrypoints.JNIEnvSavedJTOCField.getOffset());      // load JTOC
    asm.emitLAddrOffset(PROCESSOR_REGISTER,
                        PROCESSOR_REGISTER,
                        VM_Entrypoints.JNIEnvSavedPRField.getOffset()); // load PR
    asm.emitLVALAddr(S1, VM_Entrypoints.vpStatusField.getOffset());
    asm.emitLWARX(S0, S1, PROCESSOR_REGISTER);                 // get status for processor
    asm.emitCMPI(S0, VM_Processor.BLOCKED_IN_NATIVE);         // are we blocked in native code?
    VM_ForwardReference fr = asm.emitForwardBC(NE);
    //
    // if blocked in native, call C routine to do pthread_yield
    //
    asm.emitLAddrOffset(T2, JTOC, VM_Entrypoints.the_boot_recordField.getOffset());  // T2 gets boot record address
    asm.emitLAddrOffset(T1, T2, VM_Entrypoints.sysVirtualProcessorYieldIPField.getOffset());  // load addr of function
    if (VM.BuildForPowerOpenABI) {
      /* T1 points to the function descriptor, so we'll load TOC and IP from that */
      asm.emitLAddrOffset(JTOC, T1, Offset.fromIntSignExtend(BYTES_IN_ADDRESS));          // load TOC
      asm.emitLAddrOffset(T1, T1, Offset.zero());
    }
    asm.emitMTLR(T1);
    asm.emitBCLRL();                                          // call sysVirtualProcessorYield in sys.C
    asm.emitB(label1);                                    // retest the attempt to change status to IN_JAVAE
    //
    //  br to here -not blocked in native
    //
    fr.resolve(asm);
    asm.emitLVAL(S0, VM_Processor.IN_JAVA);               // S0  <- new state value
    asm.emitSTWCXr(S0, S1, PROCESSOR_REGISTER);             // attempt to change state to java
    asm.emitBC(NE, label1);                              // br if failure -retry lwarx

    //
    // return to caller
    //
    asm.emitLAddr(S0, 0, FP);                                // get previous frame
    if (VM.BuildForSVR4ABI || VM.BuildForMachOABI) {
      asm.emitLAddr(S0, STACKFRAME_NEXT_INSTRUCTION_OFFSET, S0);
    } else {
      if (VM.VerifyAssertions) VM._assert(VM.BuildForPowerOpenABI);
      asm.emitLAddr(S0, -JNI_PROLOG_RETURN_ADDRESS_OFFSET, S0); // get return address from stack frame
    }
    asm.emitMTLR(S0);
    asm.emitBCLR();

    return asm.makeMachineCode().getInstructions();
  }
}
