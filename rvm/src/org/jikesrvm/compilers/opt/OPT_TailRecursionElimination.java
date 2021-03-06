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

import java.lang.reflect.Constructor;
import org.jikesrvm.VM;
import org.jikesrvm.compilers.opt.ir.Call;
import org.jikesrvm.compilers.opt.ir.Goto;
import org.jikesrvm.compilers.opt.ir.Move;
import org.jikesrvm.compilers.opt.ir.OPT_BasicBlock;
import org.jikesrvm.compilers.opt.ir.OPT_IR;
import org.jikesrvm.compilers.opt.ir.OPT_IRTools;
import org.jikesrvm.compilers.opt.ir.OPT_Instruction;
import org.jikesrvm.compilers.opt.ir.OPT_MethodOperand;
import org.jikesrvm.compilers.opt.ir.OPT_Operand;
import static org.jikesrvm.compilers.opt.ir.OPT_Operators.BBEND;
import static org.jikesrvm.compilers.opt.ir.OPT_Operators.CALL_opcode;
import static org.jikesrvm.compilers.opt.ir.OPT_Operators.GOTO;
import static org.jikesrvm.compilers.opt.ir.OPT_Operators.IR_PROLOGUE_opcode;
import static org.jikesrvm.compilers.opt.ir.OPT_Operators.LABEL;
import static org.jikesrvm.compilers.opt.ir.OPT_Operators.SYSCALL_opcode;
import static org.jikesrvm.compilers.opt.ir.OPT_Operators.UNINT_BEGIN;
import static org.jikesrvm.compilers.opt.ir.OPT_Operators.UNINT_END;
import org.jikesrvm.compilers.opt.ir.OPT_RegisterOperand;
import org.jikesrvm.compilers.opt.ir.Prologue;
import org.jikesrvm.compilers.opt.ir.Return;

/**
 * Transform tail recursive calls into loops.
 * <p>
 * NOTES:
 * <ul>
 * <li> This pass does not attempt to optimize all tail calls, just those
 *      that are directly recursive.
 * <li> Even the small optimization we are doing here destroys the ability
 *      to accurately support stack frame inspection.
 * <li> This phase assumes that is run before OPT_Yieldpoints and thus
 *      does not need to insert a yieldpoint in the newly created loop header.
 * </ul>
 */
public final class OPT_TailRecursionElimination extends OPT_CompilerPhase {

  private static final boolean DEBUG = false;
  private final OPT_BranchOptimizations branchOpts = new OPT_BranchOptimizations(-1, true, false);

  /**
   * Constructor for this compiler phase
   */
  private static final Constructor<OPT_CompilerPhase> constructor =
      getCompilerPhaseConstructor(OPT_TailRecursionElimination.class);

  /**
   * Get a constructor object for this compiler phase
   * @return compiler phase constructor
   */
  public Constructor<OPT_CompilerPhase> getClassConstructor() {
    return constructor;
  }

  public boolean shouldPerform(OPT_Options options) {
    return options.getOptLevel() >= 1;
  }

  public String getName() { return "Tail Recursion Elimination"; }

  public OPT_CompilerPhase newExecution(OPT_IR ir) { return this; }

  /**
   * Perform tail recursion elimination.
   *
   * @param ir the IR to optimize
   */
  public void perform(OPT_IR ir) {
    OPT_BasicBlock target = null;
    OPT_Instruction prologue = null;
    boolean didSomething = false;

    for (OPT_Instruction instr = ir.firstInstructionInCodeOrder(),
        nextInstr = null; instr != null; instr = nextInstr) {
      nextInstr = instr.nextInstructionInCodeOrder();

      switch (instr.getOpcode()) {
        case IR_PROLOGUE_opcode:
          prologue = instr;
          break;
        case SYSCALL_opcode:
        case CALL_opcode:
          if (isTailRecursion(instr, ir)) {
            if (target == null) {
              target = prologue.getBasicBlock().splitNodeWithLinksAt(prologue, ir);
            }
            if (DEBUG) dumpIR(ir, "Before transformation of " + instr);
            nextInstr = transform(instr, prologue, target, ir);
            if (DEBUG) dumpIR(ir, "After transformation of " + instr);
            didSomething = true;
          }
          break;
        default:
          break;
      }
    }

    if (didSomething) {
      branchOpts.perform(ir, true);
      if (DEBUG) dumpIR(ir, "After cleanup");
      if (DEBUG) {
        VM.sysWrite("Eliminated tail calls in " + ir.method + "\n");
      }
    }
  }

  /**
   * Is the argument call instruction a tail recursive call?
   *
   * @param call the call in question
   * @param ir the enclosing IR
   * @return <code>true</code> if call is tail recursive and
   *         <code>false</code> if it is not.
   */
  boolean isTailRecursion(OPT_Instruction call, OPT_IR ir) {
    if (!Call.hasMethod(call)) return false;
    OPT_MethodOperand methOp = Call.getMethod(call);
    if (!methOp.hasPreciseTarget()) return false;
    if (methOp.getTarget() != ir.method) return false;
    OPT_RegisterOperand result = Call.getResult(call);
    OPT_Instruction s = call.nextInstructionInCodeOrder();
    while (true) {
      if (s.isMove()) {
        if (Move.getVal(s).similar(result)) {
          result = Move.getResult(s);
          if (DEBUG) VM.sysWrite("Updating result to " + result + "\n");
        } else {
          return false; // move of a value that isn't the result blocks us
        }
      } else
      if (s.operator() == LABEL || s.operator() == BBEND || s.operator() == UNINT_BEGIN || s.operator() == UNINT_END) {
        if (DEBUG) VM.sysWrite("Falling through " + s + "\n");
        // skip over housekeeping instructions and follow the code order.
      } else if (s.operator() == GOTO) {
        // follow the unconditional branch to its target LABEL
        s = s.getBranchTarget().firstInstruction();
        if (DEBUG) VM.sysWrite("Following goto to " + s + "\n");
      } else if (s.isReturn()) {
        OPT_Operand methodResult = Return.getVal(s);
        if (DEBUG) VM.sysWrite("Found return " + s + "\n");
        return methodResult == null || methodResult.similar(result);
      } else {
        // any other instruction blocks us
        return false;
      }
      s = s.nextInstructionInCodeOrder();
    }
  }

  /**
   * Transform the tail recursive call into a loop.
   *
   * @param call     The recursive call
   * @param prologue The IR_Prologue instruction
   * @param target   The loop head
   * @param ir       the containing IR
   */
  OPT_Instruction transform(OPT_Instruction call, OPT_Instruction prologue, OPT_BasicBlock target, OPT_IR ir) {
    // (1) insert move instructions to assign fresh temporaries
    //     the actuals of the call.
    int numParams = Call.getNumberOfParams(call);
    OPT_RegisterOperand[] temps = new OPT_RegisterOperand[numParams];
    for (int i = 0; i < numParams; i++) {
      OPT_Operand actual = Call.getClearParam(call, i);
      temps[i] = ir.regpool.makeTemp(actual);
      OPT_Instruction move = Move.create(OPT_IRTools.getMoveOp(temps[i].getType()), temps[i], actual);
      move.copyPosition(call);
      call.insertBefore(move);
    }

    // (2) insert move instructions to assign the formal parameters
    //     the corresponding fresh temporary
    for (int i = 0; i < numParams; i++) {
      OPT_RegisterOperand formal = Prologue.getFormal(prologue, i).copyD2D();
      OPT_Instruction move = Move.create(OPT_IRTools.getMoveOp(formal.getType()), formal, temps[i].copyD2U());
      move.copyPosition(call);
      call.insertBefore(move);
    }

    // (3) Blow away all instructions below the call in the basic block
    //     (should only be moves and other housekeeping instructions
    //      skipped over in isTailRecursion loop above)
    OPT_BasicBlock myBlock = call.getBasicBlock();
    OPT_Instruction dead = myBlock.lastRealInstruction();
    while (dead != call) {
      dead = dead.remove();
    }

    // (4) Insert a goto to jump from the call to the loop head
    OPT_Instruction gotoInstr = Goto.create(GOTO, target.makeJumpTarget());
    gotoInstr.copyPosition(call);
    call.insertAfter(gotoInstr);

    // (5) Remove the call instruction
    call.remove();

    // (6) Update the CFG
    myBlock.deleteNormalOut();
    myBlock.insertOut(target);

    return myBlock.lastInstruction();
  }
}
