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
package org.jikesrvm.compilers.opt.ia32;

import java.util.ArrayList;
import java.util.Enumeration;
import org.jikesrvm.ArchitectureSpecific.OPT_PhysicalRegisterSet;
import org.jikesrvm.compilers.opt.OPT_GenericRegisterRestrictions;
import org.jikesrvm.compilers.opt.OPT_LiveIntervalElement;
import org.jikesrvm.compilers.opt.ir.MIR_BinaryAcc;
import org.jikesrvm.compilers.opt.ir.MIR_CacheOp;
import org.jikesrvm.compilers.opt.ir.MIR_Compare;
import org.jikesrvm.compilers.opt.ir.MIR_CondMove;
import org.jikesrvm.compilers.opt.ir.MIR_DoubleShift;
import org.jikesrvm.compilers.opt.ir.MIR_LowTableSwitch;
import org.jikesrvm.compilers.opt.ir.MIR_Set;
import org.jikesrvm.compilers.opt.ir.MIR_Test;
import org.jikesrvm.compilers.opt.ir.MIR_Unary;
import org.jikesrvm.compilers.opt.ir.MIR_UnaryNoRes;
import org.jikesrvm.compilers.opt.ir.OPT_BasicBlock;
import org.jikesrvm.compilers.opt.ir.OPT_Instruction;
import org.jikesrvm.compilers.opt.ir.OPT_InstructionEnumeration;
import org.jikesrvm.compilers.opt.ir.OPT_MemoryOperand;
import org.jikesrvm.compilers.opt.ir.OPT_Operand;
import org.jikesrvm.compilers.opt.ir.OPT_OperandEnumeration;
import org.jikesrvm.compilers.opt.ir.OPT_Operators;
import org.jikesrvm.compilers.opt.ir.OPT_Register;
import org.jikesrvm.compilers.opt.ir.OPT_RegisterOperand;
import org.jikesrvm.compilers.opt.ir.ia32.OPT_BURSManagedFPROperand;

/**
 * An instance of this class encapsulates restrictions on register
 * assignment.
 */
public class OPT_RegisterRestrictions extends OPT_GenericRegisterRestrictions
    implements OPT_Operators, OPT_PhysicalRegisterConstants {

  /**
   * Allow scratch registers in PEIs?
   */
  public static final boolean SCRATCH_IN_PEI = true;

  /**
   * Default Constructor
   */
  protected OPT_RegisterRestrictions(OPT_PhysicalRegisterSet phys) {
    super(phys);
  }

  /**
   * Add architecture-specific register restrictions for a basic block.
   * Override as needed.
   *
   * @param bb the basic block
   * @param symbolics the live intervals for symbolic registers on this
   * block
   */
  public void addArchRestrictions(OPT_BasicBlock bb, ArrayList<OPT_LiveIntervalElement> symbolics) {
    // If there are any registers used in catch blocks, we want to ensure
    // that these registers are not used or evicted from scratch registers
    // at a relevant PEI, so that the assumptions of register homes in the
    // catch block remain valid.  For now, we do this by forcing any
    // register used in such a PEI as not spilled.  TODO: relax this
    // restriction for better code.
    for (OPT_InstructionEnumeration ie = bb.forwardInstrEnumerator(); ie.hasMoreElements();) {
      OPT_Instruction s = ie.next();
      if (s.isPEI() && s.operator != IR_PROLOGUE) {
        if (bb.hasApplicableExceptionalOut(s) || !SCRATCH_IN_PEI) {
          for (Enumeration<OPT_Operand> e = s.getOperands(); e.hasMoreElements();) {
            OPT_Operand op = e.nextElement();
            if (op != null && op.isRegister()) {
              noteMustNotSpill(op.asRegister().getRegister());
              handle8BitRestrictions(s);
            }
          }
        }
      }

      // handle special cases
      switch (s.getOpcode()) {
        case MIR_LOWTABLESWITCH_opcode: {
          OPT_RegisterOperand op = MIR_LowTableSwitch.getIndex(s);
          noteMustNotSpill(op.getRegister());
        }
        break;
        case IA32_MOVZX__B_opcode:
        case IA32_MOVSX__B_opcode: {
          if (MIR_Unary.getVal(s).isRegister()) {
            OPT_RegisterOperand val = MIR_Unary.getVal(s).asRegister();
            restrictTo8Bits(val.getRegister());
          }
        }
        break;
        case IA32_SET__B_opcode: {
          if (MIR_Set.getResult(s).isRegister()) {
            OPT_RegisterOperand op = MIR_Set.getResult(s).asRegister();
            restrictTo8Bits(op.getRegister());
          }
        }
        break;

        default:
          handle8BitRestrictions(s);
          break;
      }
    }
    for (OPT_InstructionEnumeration ie = bb.forwardInstrEnumerator(); ie.hasMoreElements();) {
      OPT_Instruction s = ie.next();
      if (s.operator == IA32_FNINIT) {
        // No floating point register survives across an FNINIT
        for (OPT_LiveIntervalElement symb : symbolics) {
          if (symb.getRegister().isFloatingPoint()) {
            if (contains(symb, s.scratch)) {
              addRestrictions(symb.getRegister(), phys.getFPRs());
            }
          }
        }
      } else if (s.operator == IA32_FCLEAR) {
        // Only some FPRs survive across an FCLEAR
        for (OPT_LiveIntervalElement symb : symbolics) {
          if (symb.getRegister().isFloatingPoint()) {
            if (contains(symb, s.scratch)) {
              int nSave = MIR_UnaryNoRes.getVal(s).asIntConstant().value;
              for (int i = nSave; i < NUM_FPRS; i++) {
                addRestriction(symb.getRegister(), phys.getFPR(i));
              }
            }
          }
        }
      }
    }
  }

  /**
   * Does instruction s contain an 8-bit memory operand?
   */
  final boolean has8BitMemoryOperand(OPT_Instruction s) {
    for (OPT_OperandEnumeration me = s.getMemoryOperands(); me.hasMoreElements();) {
      OPT_MemoryOperand mop = (OPT_MemoryOperand) me.next();
      if (mop.size == 1) {
        return true;
      }
    }
    return false;
  }

  /**
   * Ensure that if an operand has an 8 bit memory operand that
   * all of its register operands are in 8 bit registers.
   * @param s the instruction to restrict
   */
  final void handle8BitRestrictions(OPT_Instruction s) {
    for (OPT_OperandEnumeration me = s.getMemoryOperands(); me.hasMoreElements();) {
      OPT_MemoryOperand mop = (OPT_MemoryOperand) me.next();
      if (mop.size == 1) {
        for (OPT_OperandEnumeration e2 = s.getRootOperands(); e2.hasMoreElements();) {
          OPT_Operand rootOp = e2.next();
          if (rootOp.isRegister()) {
            restrictTo8Bits(rootOp.asRegister().getRegister());
          }
        }
      }
    }
  }

  /**
   * Ensure that a particular register is only assigned to AL, BL, CL, or
   * DL, since these are the only 8-bit registers we normally address.
   */
  final void restrictTo8Bits(OPT_Register r) {
    OPT_Register ESP = phys.getESP();
    OPT_Register EBP = phys.getEBP();
    OPT_Register ESI = phys.getESI();
    OPT_Register EDI = phys.getEDI();
    addRestriction(r, ESP);
    addRestriction(r, EBP);
    addRestriction(r, ESI);
    addRestriction(r, EDI);
  }

  /**
   * Given symbolic register r that appears in instruction s, does the
   * architecture demand that r be assigned to a physical register in s?
   */
  public static boolean mustBeInRegister(OPT_Register r, OPT_Instruction s) {
    switch (s.getOpcode()) {
      case IA32_PREFETCHNTA_opcode: {
        OPT_RegisterOperand op = MIR_CacheOp.getAddress(s).asRegister();
        if (op.register == r) return true;
      }
      break;

      case IA32_CVTSD2SI_opcode:
      case IA32_CVTSD2SS_opcode:
      case IA32_CVTSI2SD_opcode:
      case IA32_CVTSS2SD_opcode:
      case IA32_CVTSS2SI_opcode:
      case IA32_CVTTSD2SI_opcode:
      case IA32_CVTTSS2SI_opcode:
      case IA32_CVTSI2SS_opcode: {
        OPT_RegisterOperand op = MIR_Unary.getResult(s).asRegister();
        if (op.asRegister().getRegister() == r) return true;
      }
      break;

      case IA32_ADDSS_opcode:
      case IA32_DIVSS_opcode:
      case IA32_MULSS_opcode:
      case IA32_SUBSS_opcode:
      case IA32_XORPS_opcode:
      case IA32_ADDSD_opcode:
      case IA32_DIVSD_opcode:
      case IA32_MULSD_opcode:
      case IA32_SUBSD_opcode:
      case IA32_XORPD_opcode: {
        OPT_RegisterOperand op = MIR_BinaryAcc.getResult(s).asRegister();
        if (op.asRegister().getRegister() == r) return true;
      }
      break;

      case IA32_UCOMISD_opcode:
      case IA32_UCOMISS_opcode: {
        OPT_RegisterOperand op = MIR_Compare.getVal1(s).asRegister();
        if (op.asRegister().getRegister() == r) return true;
      }
      break;

      case IA32_SHRD_opcode:
      case IA32_SHLD_opcode: {
        OPT_RegisterOperand op = MIR_DoubleShift.getSource(s);
        if (op.asRegister().getRegister() == r) return true;
      }
      break;
      case IA32_FCOMI_opcode:
      case IA32_FCOMIP_opcode: {
        OPT_Operand op = MIR_Compare.getVal2(s);
        if (!(op instanceof OPT_BURSManagedFPROperand)) {
          if (op.asRegister().getRegister() == r) return true;
        }
      }
      break;
      case IA32_IMUL2_opcode: {
        OPT_RegisterOperand op = MIR_BinaryAcc.getResult(s).asRegister();
        if (op.asRegister().getRegister() == r) return true;
      }
      break;
      case MIR_LOWTABLESWITCH_opcode: {
        OPT_RegisterOperand op = MIR_LowTableSwitch.getIndex(s);
        if (op.asRegister().getRegister() == r) return true;
      }
      break;
      case IA32_CMOV_opcode:
      case IA32_FCMOV_opcode: {
        OPT_RegisterOperand op = MIR_CondMove.getResult(s).asRegister();
        if (op.asRegister().getRegister() == r) return true;
      }
      break;
      case IA32_MOVZX__B_opcode:
      case IA32_MOVSX__B_opcode: {
        OPT_RegisterOperand op = MIR_Unary.getResult(s).asRegister();
        if (op.asRegister().getRegister() == r) return true;
      }
      break;
      case IA32_MOVZX__W_opcode:
      case IA32_MOVSX__W_opcode: {
        OPT_RegisterOperand op = MIR_Unary.getResult(s).asRegister();
        if (op.asRegister().getRegister() == r) return true;
      }
      break;
      case IA32_SET__B_opcode: {
        if (MIR_Set.getResult(s).isRegister()) {
          OPT_RegisterOperand op = MIR_Set.getResult(s).asRegister();
          if (op.asRegister().getRegister() == r) return true;
        }
      }
      break;
      case IA32_TEST_opcode: {
        // at least 1 of the two operands must be in a register
        if (!MIR_Test.getVal2(s).isConstant()) {
          if (MIR_Test.getVal1(s).isRegister()) {
            if (MIR_Test.getVal1(s).asRegister().getRegister() == r) return true;
          } else if (MIR_Test.getVal2(s).isRegister()) {
            if (MIR_Test.getVal2(s).asRegister().getRegister() == r) return true;
          }
        }
      }
      break;
      case IA32_BT_opcode: {
        // val2 of bit test must be either a constant or register
        if (!MIR_Test.getVal2(s).isConstant()) {
          if (MIR_Test.getVal2(s).isRegister()) {
            if (MIR_Test.getVal2(s).asRegister().getRegister() == r) return true;
          }
        }
      }
      break;

      default:
        break;
    }
    return false;
  }

  /**
   * Can physical register r hold an 8-bit value?
   */
  private boolean okFor8(OPT_Register r) {
    OPT_Register ESP = phys.getESP();
    OPT_Register EBP = phys.getEBP();
    OPT_Register ESI = phys.getESI();
    OPT_Register EDI = phys.getEDI();
    return (r != ESP && r != EBP && r != ESI && r != EDI);
  }

  /**
   * Is it forbidden to assign symbolic register symb to physical register r
   * in instruction s?
   */
  public boolean isForbidden(OPT_Register symb, OPT_Register r, OPT_Instruction s) {

    // Look at 8-bit restrictions.
    switch (s.operator.opcode) {
      case IA32_MOVZX__B_opcode:
      case IA32_MOVSX__B_opcode: {
        if (MIR_Unary.getVal(s).isRegister()) {
          OPT_RegisterOperand val = MIR_Unary.getVal(s).asRegister();
          if (val.getRegister() == symb) {
            return !okFor8(r);
          }
        }
      }
      break;
      case IA32_SET__B_opcode: {
        if (MIR_Set.getResult(s).isRegister()) {
          OPT_RegisterOperand op = MIR_Set.getResult(s).asRegister();
          if (op.asRegister().getRegister() == symb) {
            return !okFor8(r);
          }
        }
      }
      break;
    }

    if (has8BitMemoryOperand(s)) {
      return !okFor8(r);
    }

    // Otherwise, it's OK.
    return false;
  }
}
