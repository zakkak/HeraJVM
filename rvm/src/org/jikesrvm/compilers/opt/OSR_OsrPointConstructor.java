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
package org.jikesrvm.compilers.opt;

import java.util.LinkedList;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.VM_NormalMethod;
import org.jikesrvm.compilers.opt.ir.OPT_BasicBlock;
import org.jikesrvm.compilers.opt.ir.OPT_IR;
import org.jikesrvm.compilers.opt.ir.OPT_InlinedOsrTypeInfoOperand;
import org.jikesrvm.compilers.opt.ir.OPT_Instruction;
import org.jikesrvm.compilers.opt.ir.OPT_InstructionEnumeration;
import org.jikesrvm.compilers.opt.ir.OPT_Operand;
import static org.jikesrvm.compilers.opt.ir.OPT_Operators.OSR_BARRIER_opcode;
import org.jikesrvm.compilers.opt.ir.OPT_OsrTypeInfoOperand;
import org.jikesrvm.compilers.opt.ir.OsrBarrier;
import org.jikesrvm.compilers.opt.ir.OsrPoint;

/**
 * A phase in the OPT compiler for construction OsrPoint instructions
 * after inlining.
 */
public class OSR_OsrPointConstructor extends OPT_CompilerPhase {

  public final boolean shouldPerform(OPT_Options options) {
    return VM.runningVM && options.OSR_GUARDED_INLINING;
  }

  /**
   * Return this instance of this phase. This phase contains no
   * per-compilation instance fields.
   * @param ir not used
   * @return this
   */
  public OPT_CompilerPhase newExecution(OPT_IR ir) {
    return this;
  }

  public final String getName() {
    return "OSR_OsrPointConstructor";
  }

  /**
   * Need to run branch optimizations after
   */
  private final OPT_BranchOptimizations branchOpts;

  /**
   * Constructor
   */
  OSR_OsrPointConstructor() {
    branchOpts = new OPT_BranchOptimizations(-1, false, false);
  }

  /**
   * Goes through each instruction, reconstruct OsrPoint instructions.
   */
  public void perform(OPT_IR ir) {
    // 1. collecting OsrPoint instructions
    LinkedList<OPT_Instruction> osrs = collectOsrPoints(ir);

    //    new OPT_IRPrinter("before renovating osrs").perform(ir);

    // 2. trace OsrBarrier for each OsrPoint, and rebuild OsrPoint
    renovateOsrPoints(osrs, ir);

    //    new OPT_IRPrinter("before removing barriers").perform(ir);

    // 3. remove OsrBarriers
    removeOsrBarriers(ir);

    //    new OPT_IRPrinter("after removing barriers").perform(ir);

    // 4. reconstruct CFG, cut off pieces after OsrPoint.
    fixupCFGForOsr(osrs, ir);
/*
        if (VM.TraceOnStackReplacement && (0 != osrs.size())) {
      new OPT_IRPrinter("After OsrPointConstructor").perform(ir);
    }
*/
/*
    if (VM.TraceOnStackReplacement && (0 != osrs.size())) {
      verifyNoOsrBarriers(ir);
    }
*/
    branchOpts.perform(ir);
  }

  /** Iterates instructions, build a list of OsrPoint instructions. */
  private LinkedList<OPT_Instruction> collectOsrPoints(OPT_IR ir) {
    LinkedList<OPT_Instruction> osrs = new LinkedList<OPT_Instruction>();

    OPT_InstructionEnumeration instenum = ir.forwardInstrEnumerator();
    while (instenum.hasMoreElements()) {
      OPT_Instruction inst = instenum.next();

      if (OsrPoint.conforms(inst)) {
        osrs.add(inst);
      }
    }

    return osrs;
  }

  /* For each OsrPoint instruction, traces its OsrBarriers created by
   * inlining. rebuild OsrPoint instruction to hold all necessary
   * information to recover from inlined activation.
   */
  private void renovateOsrPoints(LinkedList<OPT_Instruction> osrs, OPT_IR ir) {

    for (int osrIdx = 0, osrSize = osrs.size(); osrIdx < osrSize; osrIdx++) {
      OPT_Instruction osr = osrs.get(osrIdx);
      LinkedList<OPT_Instruction> barriers = new LinkedList<OPT_Instruction>();

      // Step 1: collect barriers put before inlined method
      //         in the order of from inner to outer
      {
        OPT_Instruction bar = (OPT_Instruction) osr.scratchObject;

        if (osr.position == null) osr.position = bar.position;

        adjustBCIndex(osr);

        while (bar != null) {

          barriers.add(bar);

          // verify each barrier is clean
          if (VM.VerifyAssertions) {
            if (!isBarrierClean(bar)) {
              VM.sysWriteln("Barrier " + bar + " is not clean!");
            }
            VM._assert(isBarrierClean(bar));
          }

          OPT_Instruction callsite = bar.position.getCallSite();
          if (callsite != null) {
            bar = (OPT_Instruction) callsite.scratchObject;

            if (bar == null) {
              VM.sysWrite("call site :" + callsite);
              VM._assert(false);
            }

            adjustBCIndex(bar);
          } else {
            bar = null;
          }
        }
      }

      int inlineDepth = barriers.size();

      if (VM.VerifyAssertions) {
        if (inlineDepth == 0) {
          VM.sysWriteln("Inlining depth for " + osr + " is 0!");
        }
        VM._assert(inlineDepth != 0);
      }

      // Step 2: make a new InlinedOsrTypeOperand from barriers
      int[] methodids = new int[inlineDepth];
      int[] bcindexes = new int[inlineDepth];
      byte[][] localTypeCodes = new byte[inlineDepth][];
      byte[][] stackTypeCodes = new byte[inlineDepth][];

      int totalOperands = 0;
      // first iteration, count the size of total locals and stack sizes
      for (int barIdx = 0, barSize = barriers.size(); barIdx < barSize; barIdx++) {

        OPT_Instruction bar = barriers.get(barIdx);
        methodids[barIdx] = bar.position.method.getId();
        bcindexes[barIdx] = bar.bcIndex;

        OPT_OsrTypeInfoOperand typeInfo = OsrBarrier.getTypeInfo(bar);
        localTypeCodes[barIdx] = typeInfo.localTypeCodes;
        stackTypeCodes[barIdx] = typeInfo.stackTypeCodes;

        // count the number of operand, but ignore VoidTypeCode
        totalOperands += OsrBarrier.getNumberOfElements(bar);

        /*
        if (VM.TraceOnStackReplacement) {
          VM.sysWriteln("OsrBarrier : "+bar.bcIndex
                        +"@"+bar.position.method
                        +" "+typeInfo);
        }
        */
      }

      // new make InlinedOsrTypeInfoOperand
      OPT_InlinedOsrTypeInfoOperand typeInfo =
          new OPT_InlinedOsrTypeInfoOperand(methodids, bcindexes, localTypeCodes, stackTypeCodes);

      OsrPoint.mutate(osr, osr.operator(), typeInfo, totalOperands);

      // Step 3: second iteration, copy operands
      int opIndex = 0;
      for (int barIdx = 0, barSize = barriers.size(); barIdx < barSize; barIdx++) {

        OPT_Instruction bar = barriers.get(barIdx);
        for (int elmIdx = 0, elmSize = OsrBarrier.getNumberOfElements(bar); elmIdx < elmSize; elmIdx++) {

          OPT_Operand op = OsrBarrier.getElement(bar, elmIdx);

          if (VM.VerifyAssertions) {
            if (op == null) {
              VM.sysWriteln(elmIdx + "th Operand of " + bar + " is null!");
            }
            VM._assert(op != null);
          }

          if (op.isRegister()) {
            op = op.asRegister().copyU2U();
          } else {
            op = op.copy();
          }

          OsrPoint.setElement(osr, opIndex, op);
          opIndex++;
        }
      }
/*
      if (VM.TraceOnStackReplacement) {
        VM.sysWriteln("renovated OsrPoint instruction "+osr);
        VM.sysWriteln("  position "+osr.bcIndex+"@"+osr.position.method);
      }
*/
      // the last OsrBarrier should in the current method
      if (VM.VerifyAssertions) {
        OPT_Instruction lastBar = barriers.getLast();
        if (ir.method != lastBar.position.method) {
          VM.sysWriteln("The last barrier is not in the same method as osr:");
          VM.sysWriteln(lastBar + "@" + lastBar.position.method);
          VM.sysWriteln("current method @" + ir.method);
        }
        VM._assert(ir.method == lastBar.position.method);

        if (opIndex != totalOperands) {
          VM.sysWriteln("opIndex and totalOperands do not match:");
          VM.sysWriteln("opIndex = " + opIndex);
          VM.sysWriteln("totalOperands = " + totalOperands);
        }
        VM._assert(opIndex == totalOperands);
      } // end of assertion
    } // end of for loop
  }

  /**
   * The OsrBarrier instruction is not in IR, so the bc index was not
   * adjusted in OSR_AdjustBCIndex
   */
  private void adjustBCIndex(OPT_Instruction barrier) {
    VM_NormalMethod source = barrier.position.method;
    if (source.isForOsrSpecialization()) {
      barrier.bcIndex -= source.getOsrPrologueLength();
    }
  }

  /** remove OsrBarrier instructions. */
  private void removeOsrBarriers(OPT_IR ir) {
    OPT_InstructionEnumeration instenum = ir.forwardInstrEnumerator();
    while (instenum.hasMoreElements()) {
      OPT_Instruction inst = instenum.next();
      // clean the scratObjects of each instruction
      inst.scratchObject = null;
      if (OsrBarrier.conforms(inst)) {
        inst.remove();
      }
    }
  }

  @SuppressWarnings("unused")
  // it's a debugging tool
  private void verifyNoOsrBarriers(OPT_IR ir) {
    VM.sysWrite("Verifying no osr barriers");
    OPT_InstructionEnumeration instenum = ir.forwardInstrEnumerator();
    while (instenum.hasMoreElements()) {
      OPT_Instruction inst = instenum.next();
      if (inst.operator().opcode == OSR_BARRIER_opcode) {
        VM.sysWriteln(" NOT SANE");
        VM.sysWriteln(inst.toString());
        if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
        break;
      }
    }
    VM.sysWriteln(" SANE");
  }

  /** verify barrier is clean by checking the number of valid operands */
  private boolean isBarrierClean(OPT_Instruction barrier) {
    OPT_OsrTypeInfoOperand typeInfo = OsrBarrier.getTypeInfo(barrier);
    int totalOperands = countNonVoidTypes(typeInfo.localTypeCodes);
    totalOperands += countNonVoidTypes(typeInfo.stackTypeCodes);
    return (totalOperands == OsrBarrier.getNumberOfElements(barrier));
  }

  private int countNonVoidTypes(byte[] typeCodes) {
    int count = 0;
    for (int idx = 0, size = typeCodes.length; idx < size; idx++) {
      if (typeCodes[idx] != org.jikesrvm.osr.OSR_Constants.VoidTypeCode) {
        count++;
      }
    }
    return count;
  }

  /**
   * Split each OsrPoint, and connect it to the exit point.
   */
  private void fixupCFGForOsr(LinkedList<OPT_Instruction> osrs, OPT_IR ir) {

    for (int i = 0, n = osrs.size(); i < n; i++) {
      OPT_Instruction osr = osrs.get(i);
      OPT_BasicBlock bb = osr.getBasicBlock();
      OPT_BasicBlock newBB = bb.segregateInstruction(osr, ir);
      bb.recomputeNormalOut(ir);
      newBB.recomputeNormalOut(ir);
    }
  }
}
