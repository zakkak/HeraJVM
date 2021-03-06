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
import org.jikesrvm.ArchitectureSpecific.OPT_Assembler;
import org.jikesrvm.ArchitectureSpecific.VM_Assembler;
import org.jikesrvm.VM;
import org.jikesrvm.VM_Constants;
import org.jikesrvm.compilers.common.assembler.VM_ForwardReference;
import org.jikesrvm.compilers.opt.OPT_OptimizingCompilerException;
import org.jikesrvm.compilers.opt.ir.MIR_BinaryAcc;
import org.jikesrvm.compilers.opt.ir.MIR_Branch;
import org.jikesrvm.compilers.opt.ir.MIR_Call;
import org.jikesrvm.compilers.opt.ir.MIR_Compare;
import org.jikesrvm.compilers.opt.ir.MIR_CondBranch;
import org.jikesrvm.compilers.opt.ir.MIR_Lea;
import org.jikesrvm.compilers.opt.ir.MIR_Move;
import org.jikesrvm.compilers.opt.ir.MIR_Test;
import org.jikesrvm.compilers.opt.ir.MIR_Unary;
import org.jikesrvm.compilers.opt.ir.MIR_UnaryNoRes;
import org.jikesrvm.compilers.opt.ir.OPT_BranchOperand;
import org.jikesrvm.compilers.opt.ir.OPT_IR;
import org.jikesrvm.compilers.opt.ir.OPT_Instruction;
import org.jikesrvm.compilers.opt.ir.OPT_IntConstantOperand;
import org.jikesrvm.compilers.opt.ir.OPT_MemoryOperand;
import org.jikesrvm.compilers.opt.ir.OPT_Operand;
import org.jikesrvm.compilers.opt.ir.OPT_OperandEnumeration;
import org.jikesrvm.compilers.opt.ir.OPT_Operator;
import org.jikesrvm.compilers.opt.ir.OPT_Operators;
import org.jikesrvm.compilers.opt.ir.OPT_Register;
import org.jikesrvm.compilers.opt.ir.OPT_RegisterOperand;
import org.jikesrvm.compilers.opt.ir.OPT_TrapCodeOperand;
import org.jikesrvm.compilers.opt.ir.ia32.OPT_IA32ConditionOperand;
import org.jikesrvm.compilers.opt.ir.ia32.OPT_PhysicalRegisterSet;
import org.jikesrvm.ia32.VM_TrapConstants;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.unboxed.Offset;

/**
 *  This class provides support functionality used by the generated
 * OPT_Assembler; it handles basic impedance-matching functionality
 * such as determining which addressing mode is suitable for a given
 * OPT_IA32MemoryOperand.  This class also provides some boilerplate
 * methods that do not depend on how instructions sould actually be
 * assembled, like the top-level generateCode driver.  This class is
 * not meant to be used in isolation, but rather to provide support
 * from the OPT_Assembler.
 */
abstract class OPT_AssemblerBase extends VM_Assembler
    implements OPT_Operators, VM_Constants, OPT_PhysicalRegisterConstants {

  private static final boolean DEBUG_ESTIMATE = false;

  /**
   * Hold EBP register object for use in estimating size of memory operands.
   */
  private final OPT_Register EBP;

  /**
   * Hold EBP register object for use in estimating size of memory operands.
   */
  private final OPT_Register ESP;

  /**
   * Operators with byte arguments
   */
  private static final OPT_Operator[] byteSizeOperators;

  /**
   * Operators with word arguments
   */
  private static final OPT_Operator[] wordSizeOperators;

  /**
   * Operators with quad arguments
   */
  private static final OPT_Operator[] quadSizeOperators;

  static {
    ArrayList<OPT_Operator> temp = new ArrayList<OPT_Operator>();
    for (OPT_Operator opr : OPT_Operator.OperatorArray) {
      if (opr != null && opr.toString().indexOf("__b") != -1) {
        temp.add(opr);
      }
    }
    byteSizeOperators = temp.toArray(new OPT_Operator[temp.size()]);
    temp.clear();
    for (OPT_Operator opr : OPT_Operator.OperatorArray) {
      if (opr != null && opr.toString().indexOf("__w") != -1) {
        temp.add(opr);
      }
    }
    wordSizeOperators = temp.toArray(new OPT_Operator[temp.size()]);
    for (OPT_Operator opr : OPT_Operator.OperatorArray) {
      if (opr != null && opr.toString().indexOf("__q") != -1) {
        temp.add(opr);
      }
    }
    quadSizeOperators = temp.toArray(new OPT_Operator[temp.size()]);
  }

  /**
   * Construct Assembler object
   * @see VM_Assembler
   */
  OPT_AssemblerBase(int bytecodeSize, boolean shouldPrint, OPT_IR ir) {
    super(bytecodeSize, shouldPrint);
    EBP = ir.regpool.getPhysicalRegisterSet().getEBP();
    ESP = ir.regpool.getPhysicalRegisterSet().getESP();
  }

  /**
   * Should code created by this assembler instance be allocated in the
   * hot code code space? The default answer for opt compiled code is yes
   * (otherwise why are we opt compiling it?).
   */
  protected boolean isHotCode() { return true; }

  /**
   *  Is the given operand an immediate?  In the IA32 assembly, one
   * cannot specify floating-point constants, so the possible
   * immediates we may see are OPT_IntegerConstants and
   * OPT_TrapConstants (a trap constant really is an integer), and
   * jump targets for which the exact offset is known.
   *
   * @see #getImm
   *
   * @param op the operand being queried
   * @return true if op represents an immediate
   */
  boolean isImm(OPT_Operand op) {
    return (op instanceof OPT_IntConstantOperand) ||
           (op instanceof OPT_TrapCodeOperand) ||
           (op instanceof OPT_BranchOperand && op.asBranch().target.getmcOffset() >= 0);
  }

  /**
   *  Return the IA32 ISA encoding of the immediate value
   * represented by the the given operand.  This method assumes the
   * operand is an immediate and will likely throw a
   * ClassCastException if this not the case.  It treats
   * OPT_BranchOperands somewhat differently than isImm does: in
   * case a branch target is not resolved, it simply returns a wrong
   * answer and trusts the caller to ignore it. This behavior
   * simplifies life when generating code for ImmOrLabel operands.
   *
   * @see #isImm
   *
   * @param op the operand being queried
   * @return the immediate value represented by the operand
   */
  int getImm(OPT_Operand op) {
    if (op.isIntConstant()) {
      return op.asIntConstant().value;
    } else if (op.isBranch()) {
      // used by ImmOrLabel stuff
      return op.asBranch().target.getmcOffset();
    } else {
      return ((OPT_TrapCodeOperand) op).getTrapCode() + VM_TrapConstants.RVM_TRAP_BASE;
    }
  }

  /**
   *  Is the given operand a register operand?
   *
   * @see #getReg
   *
   * @param op the operand being queried
   * @return true if op is an OPT_RegisterOperand
   */
  boolean isReg(OPT_Operand op) {
    return op.isRegister();
  }

  /**
   * Return the machine-level register number corresponding to a given integer
   * OPT_Register. The optimizing compiler has its own notion of register
   * numbers, which is not the same as the numbers used by the IA32 ISA. This
   * method takes an optimizing compiler register and translates it into the
   * appropriate machine-level encoding. This method is not applied directly to
   * operands, but rather to register objects.
   *
   * @see #getBase
   * @see #getIndex
   *
   * @param reg the register being queried
   * @return the 3 bit machine-level encoding of reg
   */
  private byte getGPMachineRegister(OPT_Register reg) {
    if (VM.VerifyAssertions) {
      VM._assert(OPT_PhysicalRegisterSet.getPhysicalRegisterType(reg) == INT_REG);
    }
    return (byte) (reg.number - FIRST_INT);
  }

  /**
   * Return the machine-level register number corresponding to a
   * given OPT_Register.  The optimizing compiler has its own notion
   * of register numbers, which is not the same as the numbers used
   * by the IA32 ISA.  This method takes an optimizing compiler
   * register and translates it into the appropriate machine-level
   * encoding.  This method is not applied directly to operands, but
   * rather to register objects.
   *
   * @see #getReg
   * @see #getBase
   * @see #getIndex
   *
   * @param reg the register being queried
   * @return the 3 bit machine-level encoding of reg
   */
  private byte getMachineRegister(OPT_Register reg) {
    int type = OPT_PhysicalRegisterSet.getPhysicalRegisterType(reg);
    byte result;
    if (type == INT_REG) {
      result = (byte) (reg.number - FIRST_INT);
    } else {
      if (VM.VerifyAssertions) VM._assert(type == DOUBLE_REG);
      if (reg.number < FIRST_SPECIAL) {
        result = (byte) (reg.number - FIRST_DOUBLE);
      } else if (reg.number == ST0) {
        result = 0;
      } else {
        if (VM.VerifyAssertions) VM._assert(reg.number == ST1);
        result = 1;
      }
    }
    if (OPT_IR.PARANOID) VM._assert((result & 0x7) == result);
    return result;
  }

  /**
   * Given a register operand, return the 3 bit IA32 ISA encoding
   * of that register.  This function translates an optimizing
   * compiler register operand into the 3 bit IA32 ISA encoding that
   * can be passed to the VM_Assembler.  This function assumes its
   * operand is a register operand, and will blow up if it is not;
   * use isReg to check operands passed to this method.
   *
   * @see #isReg
   *
   * @param op the register operand being queried
   * @return the 3 bit IA32 ISA encoding of op
   */
  byte getReg(OPT_Operand op) {
    return getMachineRegister(op.asRegister().getRegister());
  }

  /**
   * Given a memory operand, return the 3 bit IA32 ISA encoding
   * of its base regsiter.  This function translates the optimizing
   * compiler register operand representing the base of the given
   * memory operand into the 3 bit IA32 ISA encoding that
   * can be passed to the VM_Assembler.  This function assumes its
   * operand is a memory operand, and will blow up if it is not;
   * one should confirm an operand really has a base register before
   * invoking this method on it.
   *
   * @see #isRegDisp
   * @see #isRegIdx
   * @see #isRegInd
   *
   * @param op the register operand being queried
   * @return the 3 bit IA32 ISA encoding of the base register of op
   */
  byte getBase(OPT_Operand op) {
    return getGPMachineRegister(((OPT_MemoryOperand) op).base.getRegister());
  }

  /**
   * Given a memory operand, return the 3 bit IA32 ISA encoding
   * of its index regsiter.  This function translates the optimizing
   * compiler register operand representing the index of the given
   * memory operand into the 3 bit IA32 ISA encoding that
   * can be passed to the VM_Assembler.  This function assumes its
   * operand is a memory operand, and will blow up if it is not;
   * one should confirm an operand really has an index register before
   * invoking this method on it.
   *
   * @see #isRegIdx
   * @see #isRegOff
   *
   * @param op the register operand being queried
   * @return the 3 bit IA32 ISA encoding of the index register of op
   */
  byte getIndex(OPT_Operand op) {
    return getGPMachineRegister(((OPT_MemoryOperand) op).index.getRegister());
  }

  /**
   *  Given a memory operand, return the 2 bit IA32 ISA encoding
   * of its scale, suitable for passing to the VM_Assembler to mask
   * into a SIB byte.  This function assumes its operand is a memory
   * operand, and will blow up if it is not; one should confirm an
   * operand really has a scale before invoking this method on it.
   *
   * @see #isRegIdx
   * @see #isRegOff
   *
   * @param op the register operand being queried
   * @return the IA32 ISA encoding of the scale of op
   */
  short getScale(OPT_Operand op) {
    return ((OPT_MemoryOperand) op).scale;
  }

  /**
   *  Given a memory operand, return the 2 bit IA32 ISA encoding
   * of its scale, suitable for passing to the VM_Assembler to mask
   * into a SIB byte.  This function assumes its operand is a memory
   * operand, and will blow up if it is not; one should confirm an
   * operand really has a scale before invoking this method on it.
   *
   * @see #isRegIdx
   * @see #isRegOff
   *
   * @param op the register operand being queried
   * @return the IA32 ISA encoding of the scale of op
   */
  Offset getDisp(OPT_Operand op) {
    return ((OPT_MemoryOperand) op).disp;
  }

  /**
   *  Determine if a given operand is a memory operand representing
   * register-displacement mode addressing.  This method takes an
   * arbitrary operand, checks whether it is a memory operand, and,
   * if it is, checks whether it should be assembled as IA32
   * register-displacement mode.  That is, does it have a non-zero
   * displacement and a base register, but no scale and no index
   * register?
   *
   * @param op the operand being queried
   * @return true if op should be assembled as register-displacement mode
   */
  boolean isRegDisp(OPT_Operand op) {
    if (op instanceof OPT_MemoryOperand) {
      OPT_MemoryOperand mop = (OPT_MemoryOperand) op;
      return (mop.base != null) && (mop.index == null) && (!mop.disp.isZero()) && (mop.scale == 0);
    } else {
      return false;
    }
  }

  /**
   * Determine if a given operand is a memory operand representing
   * absolute mode addressing.  This method takes an
   * arbitrary operand, checks whether it is a memory operand, and,
   * if it is, checks whether it should be assembled as IA32
   * absolute address mode.  That is, does it have a non-zero
   * displacement, but no scale, no scale and no index register?
   *
   * @param op the operand being queried
   * @return true if op should be assembled as absolute mode
   */
  boolean isAbs(OPT_Operand op) {
    if (op instanceof OPT_MemoryOperand) {
      OPT_MemoryOperand mop = (OPT_MemoryOperand) op;
      return (mop.base == null) && (mop.index == null) && (!mop.disp.isZero()) && (mop.scale == 0);
    } else {
      return false;
    }
  }

  /**
   *  Determine if a given operand is a memory operand representing
   * register-indirect mode addressing.  This method takes an
   * arbitrary operand, checks whether it is a memory operand, and,
   * if it is, checks whether it should be assembled as IA32
   * register-displacement mode.  That is, does it have a base
   * register, but no displacement, no scale and no index
   * register?
   *
   * @param op the operand being queried
   * @return true if op should be assembled as register-indirect mode
   */
  boolean isRegInd(OPT_Operand op) {
    if (op instanceof OPT_MemoryOperand) {
      OPT_MemoryOperand mop = (OPT_MemoryOperand) op;
      return (mop.base != null) && (mop.index == null) && (mop.disp.isZero()) && (mop.scale == 0);
    } else {
      return false;
    }
  }

  /**
   *  Determine if a given operand is a memory operand representing
   * register-offset mode addressing.  This method takes an
   * arbitrary operand, checks whether it is a memory operand, and,
   * if it is, checks whether it should be assembled as IA32
   * register-offset mode.  That is, does it have a non-zero
   * displacement, a scale parameter and an index register, but no
   * base register?
   *
   * @param op the operand being queried
   * @return true if op should be assembled as register-offset mode
   */
  boolean isRegOff(OPT_Operand op) {
    if (op instanceof OPT_MemoryOperand) {
      OPT_MemoryOperand mop = (OPT_MemoryOperand) op;
      return (mop.base == null) && (mop.index != null);
    } else {
      return false;
    }
  }

  /**
   *  Determine if a given operand is a memory operand representing
   * the full glory of scaled-index-base addressing.  This method takes an
   * arbitrary operand, checks whether it is a memory operand, and,
   * if it is, checks whether it should be assembled as IA32
   * SIB mode.  That is, does it have a non-zero
   * displacement, a scale parameter, a base register and an index
   * register?
   *
   * @param op the operand being queried
   * @return true if op should be assembled as SIB mode
   */
  boolean isRegIdx(OPT_Operand op) {
    if (op instanceof OPT_MemoryOperand) {
      return !(isAbs(op) || isRegInd(op) || isRegDisp(op) || isRegOff(op));
    } else {
      return false;
    }
  }

  /**
   *  Return the condition bits of a given optimizing compiler
   * condition operand.  This method returns the IA32 ISA bits
   * representing a given condition operand, suitable for passing to
   * the VM_Assembler to encode into the opcode of a SET, Jcc or
   * CMOV instruction.  This being IA32, there are of course
   * exceptions in the binary encoding of conditions (see FCMOV),
   * but the VM_Assembler handles that.  This function assumes its
   * argument is an OPT_IA32ConditionOperand, and will blow up if it
   * is not.
   *
   * @param op the operand being queried
   * @return the bits that (usually) represent the given condition
   * in the IA32 ISA */
  byte getCond(OPT_Operand op) {
    return ((OPT_IA32ConditionOperand) op).value;
  }

  /**
   *  Is the given operand an IA32 condition operand?
   *
   * @param op the operand being queried
   * @return true if op is an IA32 condition operand
   */
  boolean isCond(OPT_Operand op) {
    return (op instanceof OPT_IA32ConditionOperand);
  }

  /**
   *  Return the label representing the target of the given branch
   * operand.  These labels are used to represent branch targets
   * that have not yet been assembled, and so cannot be given
   * concrete machine code offsets.  All instructions are nunbered
   * just prior to assembly, and these numbers are used as labels.
   * This method also returns 0 (not a valid label) for int
   * constants to simplify generation of branches (the branch
   * generation code will ignore this invalid label; it is used to
   * prevent type exceptions).  This method assumes its operand is a
   * branch operand (or an int) and will blow up if it is not.
   *
   * @param op the branch operand being queried
   * @return the label representing the branch target
   */
  int getLabel(OPT_Operand op) {
    if (op instanceof OPT_IntConstantOperand) {
      // used by ImmOrLabel stuff
      return 0;
    } else {
      if (op.asBranch().target.getmcOffset() < 0) {
        return -op.asBranch().target.getmcOffset();
      } else {
        return -1;
      }
    }
  }

  /**
   *  Is the given operand a branch target that requires a label?
   *
   * @see #getLabel
   *
   * @param op the operand being queried
   * @return true if it represents a branch requiring a label target
   */
  boolean isLabel(OPT_Operand op) {
    return (op instanceof OPT_BranchOperand && op.asBranch().target.getmcOffset() < 0);
  }

  /**
   *  Is the given operand a branch target?
   *
   * @see #getLabel
   * @see #isLabel
   *
   * @param op the operand being queried
   * @return true if it represents a branch target
   */
  @NoInline
  boolean isImmOrLabel(OPT_Operand op) {
    // TODO: Remove NoInlinePragma, work around for leave SSA bug
    return (isImm(op) || isLabel(op));
  }

  /**
   * Does the given instruction operate upon byte-sized data?  The
   * opt compiler does not represent the size of register data, so
   * this method typically looks at the memory operand, if any, and
   * checks whether that is a byte.  This does not work for the
   * size-converting moves (MOVSX and MOVZX), and those instructions
   * use the operator convention that __b on the end of the operator
   * name means operate upon byte data.
   *
   * @param inst the instruction being queried
   * @return true if inst operates upon byte data
   */
  boolean isByte(OPT_Instruction inst) {
    for(OPT_Operator opr : byteSizeOperators){
      if (opr == inst.operator) {
        return true;
      }
    }

    for (int i = 0; i < inst.getNumberOfOperands(); i++) {
      OPT_Operand op = inst.getOperand(i);
      if (op instanceof OPT_MemoryOperand) {
        return (((OPT_MemoryOperand) op).size == 1);
      }
    }

    return false;
  }

  /**
   * Does the given instruction operate upon word-sized data?  The
   * opt compiler does not represent the size of register data, so
   * this method typically looks at the memory operand, if any, and
   * checks whether that is a word.  This does not work for the
   * size-converting moves (MOVSX and MOVZX), and those instructions
   * use the operator convention that __w on the end of the operator
   * name means operate upon word data.
   *
   * @param inst the instruction being queried
   * @return true if inst operates upon word data
   */
  boolean isWord(OPT_Instruction inst) {
    for(OPT_Operator opr : wordSizeOperators){
      if (opr == inst.operator) {
        return true;
      }
    }

    for (int i = 0; i < inst.getNumberOfOperands(); i++) {
      OPT_Operand op = inst.getOperand(i);
      if (op instanceof OPT_MemoryOperand) {
        return (((OPT_MemoryOperand) op).size == 2);
      }
    }

    return false;
  }

  /**
   *  Does the given instruction operate upon quad-sized data?  The
   * opt compiler does not represent the size of register data, so
   * this method typically looks at the memory operand, if any, and
   * checks whether that is a byte.  This method also recognizes
   * the operator convention that __q on the end of the operator
   * name means operate upon quad data; no operator currently uses
   * this convention.
   *
   * @param inst the instruction being queried
   * @return true if inst operates upon quad data
   */
  boolean isQuad(OPT_Instruction inst) {
    for(OPT_Operator opr : quadSizeOperators){
      if (opr == inst.operator) {
        return true;
      }
    }

    for (int i = 0; i < inst.getNumberOfOperands(); i++) {
      OPT_Operand op = inst.getOperand(i);
      if (op instanceof OPT_MemoryOperand) {
        return (((OPT_MemoryOperand) op).size == 8);
      }
    }

    return false;
  }

  /**
   * Given a forward branch instruction and its target,
   * determine (conservatively) if the relative offset to the
   * target is less than 127 bytes
   * @param start the branch instruction
   * @param target the value of the mcOffset of the target label
   * @return true if the relative offset will be less than 127, false otherwise
   */
  protected boolean targetIsClose(OPT_Instruction start, int target) {
    OPT_Instruction inst = start.nextInstructionInCodeOrder();
    int budget = 120; // slight fudge factor could be 127
    while (true) {
      if (budget <= 0) return false;
      if (inst.getmcOffset() == target) {
        return true;
      }
      budget -= estimateSize(inst);
      inst = inst.nextInstructionInCodeOrder();
    }
  }

  protected int estimateSize(OPT_Instruction inst) {
    switch (inst.getOpcode()) {
      case LABEL_opcode:
      case BBEND_opcode:
      case UNINT_BEGIN_opcode:
      case UNINT_END_opcode: {
        // these generate no code
        return 0;
      }
      // Generated from the same case in VM_Assembler
      case IA32_ADC_opcode:
      case IA32_ADD_opcode:
      case IA32_AND_opcode:
      case IA32_OR_opcode:
      case IA32_SBB_opcode:
      case IA32_XOR_opcode: {
        int size = 2; // opcode + modr/m
        size += operandCost(MIR_BinaryAcc.getResult(inst), true);
        size += operandCost(MIR_BinaryAcc.getValue(inst), true);
        return size;
      }
      case IA32_CMP_opcode: {
        int size = 2; // opcode + modr/m
        size += operandCost(MIR_Compare.getVal1(inst), true);
        size += operandCost(MIR_Compare.getVal2(inst), true);
        return size;
      }
      case IA32_TEST_opcode: {
        int size = 2; // opcode + modr/m
        size += operandCost(MIR_Test.getVal1(inst), true);
        size += operandCost(MIR_Test.getVal2(inst), true);
        return size;
      }
      case IA32_ADDSD_opcode:
      case IA32_SUBSD_opcode:
      case IA32_MULSD_opcode:
      case IA32_DIVSD_opcode:
      case IA32_XORPD_opcode:
      case IA32_ADDSS_opcode:
      case IA32_SUBSS_opcode:
      case IA32_MULSS_opcode:
      case IA32_DIVSS_opcode:
      case IA32_XORPS_opcode: {
        int size = 4; // opcode + modr/m
        OPT_Operand value = MIR_BinaryAcc.getValue(inst);
        size += operandCost(value, false);
        return size;
      }
      case IA32_UCOMISS_opcode: {
        int size = 3; // opcode + modr/m
        OPT_Operand val2 = MIR_Compare.getVal2(inst);
        size += operandCost(val2, false);
        return size;
      }
      case IA32_UCOMISD_opcode: {
        int size = 4; // opcode + modr/m
        OPT_Operand val2 = MIR_Compare.getVal2(inst);
        size += operandCost(val2, false);
        return size;
      }
      case IA32_CVTSI2SS_opcode:
      case IA32_CVTSI2SD_opcode:
      case IA32_CVTSS2SD_opcode:
      case IA32_CVTSD2SS_opcode:
      case IA32_CVTSD2SI_opcode:
      case IA32_CVTTSD2SI_opcode:
      case IA32_CVTSS2SI_opcode:
      case IA32_CVTTSS2SI_opcode: {
        int size = 4; // opcode + modr/m
        OPT_Operand result = MIR_Unary.getResult(inst);
        OPT_Operand value = MIR_Unary.getVal(inst);
        size += operandCost(result, false);
        size += operandCost(value, false);
        return size;
      }
      case IA32_CMPEQSD_opcode:
      case IA32_CMPLTSD_opcode:
      case IA32_CMPLESD_opcode:
      case IA32_CMPUNORDSD_opcode:
      case IA32_CMPNESD_opcode:
      case IA32_CMPNLTSD_opcode:
      case IA32_CMPNLESD_opcode:
      case IA32_CMPORDSD_opcode:
      case IA32_CMPEQSS_opcode:
      case IA32_CMPLTSS_opcode:
      case IA32_CMPLESS_opcode:
      case IA32_CMPUNORDSS_opcode:
      case IA32_CMPNESS_opcode:
      case IA32_CMPNLTSS_opcode:
      case IA32_CMPNLESS_opcode:
      case IA32_CMPORDSS_opcode: {
        int size = 5; // opcode + modr/m + type
        OPT_Operand value = MIR_BinaryAcc.getValue(inst);
        size += operandCost(value, false);
        return size;
      }
      case IA32_MOVD_opcode:
      case IA32_MOVQ_opcode:
      case IA32_MOVSS_opcode:
      case IA32_MOVSD_opcode: {
        int size = 4; // opcode + modr/m
        OPT_Operand result = MIR_Move.getResult(inst);
        OPT_Operand value = MIR_Move.getValue(inst);
        size += operandCost(result, false);
        size += operandCost(value, false);
        return size;
      }
      case IA32_PUSH_opcode: {
        OPT_Operand op = MIR_UnaryNoRes.getVal(inst);
        int size = 0;
        if (op instanceof OPT_RegisterOperand) {
          size += 1;
        } else if (op instanceof OPT_IntConstantOperand) {
          if (fits(((OPT_IntConstantOperand) op).value, 8)) {
            size += 2;
          } else {
            size += 5;
          }
        } else {
          size += (2 + operandCost(op, true));
        }
        return size;
      }
      case IA32_LEA_opcode: {
        int size = 2; // opcode + 1 byte modr/m
        size += operandCost(MIR_Lea.getResult(inst), false);
        size += operandCost(MIR_Lea.getValue(inst), false);
        return size;
      }
      case IA32_MOV_opcode: {
        int size = 2; // opcode + modr/m
        OPT_Operand result = MIR_Move.getResult(inst);
        OPT_Operand value = MIR_Move.getValue(inst);
        size += operandCost(result, false);
        size += operandCost(value, false);
        return size;
      }
      case IA32_OFFSET_opcode:
        return 4;
      case IA32_JCC_opcode:
      case IA32_JMP_opcode:
        return 6; // assume long form
      case IA32_LOCK_opcode:
        return 1;
      case IG_PATCH_POINT_opcode:
        return 6;
      case IA32_INT_opcode:
        return 2;
      case IA32_RET_opcode:
        return 3;
      case IA32_CALL_opcode:
        OPT_Operand target = MIR_Call.getTarget(inst);
        if (isImmOrLabel(target)) {
          return 5; // opcode + 32bit immediate
        } else {
          return 2 + operandCost(target, false); // opcode + modr/m
        }
      default: {
        int size = 3; // 2 bytes opcode + 1 byte modr/m
        for (OPT_OperandEnumeration opEnum = inst.getRootOperands(); opEnum.hasMoreElements();) {
          OPT_Operand op = opEnum.next();
          size += operandCost(op, false);
        }
        return size;
      }
    }
  }

  private int operandCost(OPT_Operand op, boolean shortFormImmediate) {
    if (op instanceof OPT_MemoryOperand) {
      OPT_MemoryOperand mop = (OPT_MemoryOperand) op;
      // If it's a 2byte mem location, we're going to need an override prefix
      int prefix = mop.size == 2 ? 1 : 0;

      // Deal with EBP wierdness
      if (mop.base != null && mop.base.getRegister() == EBP) {
        if (mop.index != null) {
          // forced into SIB + 32 bit displacement no matter what disp is
          return prefix + 5;
        }
        if (fits(mop.disp, 8)) {
          return prefix + 1;
        } else {
          return prefix + 4;
        }
      }
      if (mop.index != null && mop.index.getRegister() == EBP) {
        // forced into SIB + 32 bit displacement no matter what disp is
        return prefix + 5;
      }

      // Deal with ESP wierdness -- requires SIB byte even when index is null
      if (mop.base != null && mop.base.getRegister() == ESP) {
        if (fits(mop.disp, 8)) {
          return prefix + 2;
        } else {
          return prefix + 5;
        }
      }

      if (mop.index == null) {
        // just displacement to worry about
        if (mop.disp.isZero()) {
          return prefix + 0;
        } else if (fits(mop.disp, 8)) {
          return prefix + 1;
        } else {
          return prefix + 4;
        }
      } else {
        // have a SIB
        if (mop.base == null && mop.scale != 0) {
          // forced to 32 bit displacement even if it would fit in 8
          return prefix + 5;
        } else {
          if (mop.disp.isZero()) {
            return prefix + 1;
          } else if (fits(mop.disp, 8)) {
            return prefix + 2;
          } else {
            return prefix + 5;
          }
        }
      }
    } else if (op instanceof OPT_IntConstantOperand) {
      if (shortFormImmediate && fits(((OPT_IntConstantOperand) op).value, 8)) {
        return 1;
      } else {
        return 4;
      }
    } else {
      return 0;
    }
  }

  /**
   * Emit the given instruction, assuming that
   * it is a MIR_CondBranch instruction
   * and has a JCC operator
   *
   * @param inst the instruction to assemble
   */
  protected void doJCC(OPT_Instruction inst) {
    byte cond = getCond(MIR_CondBranch.getCond(inst));
    if (isImm(MIR_CondBranch.getTarget(inst))) {
      emitJCC_Cond_Imm(cond, getImm(MIR_CondBranch.getTarget(inst)));
    } else {
      if (VM.VerifyAssertions && !isLabel(MIR_CondBranch.getTarget(inst))) VM._assert(false, inst.toString());
      int sourceLabel = -inst.getmcOffset();
      int targetLabel = getLabel(MIR_CondBranch.getTarget(inst));
      int delta = targetLabel - sourceLabel;
      if (VM.VerifyAssertions) VM._assert(delta >= 0);
      if (delta < 10 || (delta < 90 && targetIsClose(inst, -targetLabel))) {
        int miStart = mi;
        VM_ForwardReference r = new VM_ForwardReference.ShortBranch(mi, targetLabel);
        forwardRefs = VM_ForwardReference.enqueue(forwardRefs, r);
        setMachineCodes(mi++, (byte) (0x70 + cond));
        mi += 1; // leave space for displacement
        if (lister != null) lister.I(miStart, "J" + CONDITION[cond], 0);
      } else {
        emitJCC_Cond_Label(cond, targetLabel);
      }
    }
  }

  /**
   *  Emit the given instruction, assuming that
   * it is a MIR_Branch instruction
   * and has a JMP operator
   *
   * @param inst the instruction to assemble
   */
  protected void doJMP(OPT_Instruction inst) {
    if (isImm(MIR_Branch.getTarget(inst))) {
      emitJMP_Imm(getImm(MIR_Branch.getTarget(inst)));
    } else if (isLabel(MIR_Branch.getTarget(inst))) {
      int sourceLabel = -inst.getmcOffset();
      int targetLabel = getLabel(MIR_Branch.getTarget(inst));
      int delta = targetLabel - sourceLabel;
      if (VM.VerifyAssertions) VM._assert(delta >= 0);
      if (delta < 10 || (delta < 90 && targetIsClose(inst, -targetLabel))) {
        int miStart = mi;
        VM_ForwardReference r = new VM_ForwardReference.ShortBranch(mi, targetLabel);
        forwardRefs = VM_ForwardReference.enqueue(forwardRefs, r);
        setMachineCodes(mi++, (byte) 0xEB);
        mi += 1; // leave space for displacement
        if (lister != null) lister.I(miStart, "JMP", 0);
      } else {
        emitJMP_Label(getLabel(MIR_Branch.getTarget(inst)));
      }
    } else if (isReg(MIR_Branch.getTarget(inst))) {
      emitJMP_Reg(getReg(MIR_Branch.getTarget(inst)));
    } else if (isAbs(MIR_Branch.getTarget(inst))) {
      emitJMP_Abs(getDisp(MIR_Branch.getTarget(inst)));
    } else if (isRegDisp(MIR_Branch.getTarget(inst))) {
      emitJMP_RegDisp(getBase(MIR_Branch.getTarget(inst)), getDisp(MIR_Branch.getTarget(inst)));
    } else if (isRegOff(MIR_Branch.getTarget(inst))) {
      emitJMP_RegOff(getIndex(MIR_Branch.getTarget(inst)),
                     getScale(MIR_Branch.getTarget(inst)),
                     getDisp(MIR_Branch.getTarget(inst)));
    } else if (isRegIdx(MIR_Branch.getTarget(inst))) {
      emitJMP_RegIdx(getBase(MIR_Branch.getTarget(inst)),
                     getIndex(MIR_Branch.getTarget(inst)),
                     getScale(MIR_Branch.getTarget(inst)),
                     getDisp(MIR_Branch.getTarget(inst)));
    } else if (isRegInd(MIR_Branch.getTarget(inst))) {
      emitJMP_RegInd(getBase(MIR_Branch.getTarget(inst)));
    } else {
      if (VM.VerifyAssertions) VM._assert(false, inst.toString());
    }
  }

  /**
   * Debugging support (return a printable representation of the machine code).
   *
   * @param instr  An integer to be interpreted as a PowerPC instruction
   * @param offset the mcoffset (in bytes) of the instruction
   */
  public String disasm(int instr, int offset) {
    OPT_OptimizingCompilerException.TODO("OPT_Assembler: disassembler");
    return null;
  }

  /**
   * generate machine code into ir.machinecode.
   * @param ir the IR to generate
   * @param shouldPrint should we print the machine code?
   * @return the number of machinecode instructions generated
   */
  public static int generateCode(OPT_IR ir, boolean shouldPrint) {
    int count = 0;
    OPT_Assembler asm = new OPT_Assembler(count, shouldPrint, ir);

    for (OPT_Instruction p = ir.firstInstructionInCodeOrder(); p != null; p = p.nextInstructionInCodeOrder()) {
      p.setmcOffset(-++count);
    }

    for (OPT_Instruction p = ir.firstInstructionInCodeOrder(); p != null; p = p.nextInstructionInCodeOrder()) {
      if (DEBUG_ESTIMATE) {
        int start = asm.getMachineCodeIndex();
        int estimate = asm.estimateSize(p);
        asm.doInst(p);
        int end = asm.getMachineCodeIndex();
        if (end - start > estimate) {
          VM.sysWriteln("Bad estimate: " + (end - start) + " " + estimate + " " + p);
          VM.sysWrite("\tMachine code: ");
          asm.writeLastInstruction(start);
          VM.sysWriteln();
        }
      } else {
        asm.doInst(p);
      }
    }

    ir.MIRInfo.machinecode = asm.getMachineCodes();

    return ir.MIRInfo.machinecode.length();
  }

}
