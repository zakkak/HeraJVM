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
package org.jikesrvm.compilers.opt.ir;

import static org.jikesrvm.compilers.opt.ir.OPT_Operators.BOUNDS_CHECK;

/**
 * General utilities to summarize an IR
 */
public final class OPT_IRSummary {

  /**
   * Does this IR have a bounds check expression?
   */
  public static boolean hasBoundsCheck(OPT_IR ir) {
    for (OPT_InstructionEnumeration e = ir.forwardInstrEnumerator(); e.hasMoreElements();) {
      OPT_Instruction s = e.next();
      if (s.operator == BOUNDS_CHECK) {
        return true;
      }
    }
    return false;
  }
}



