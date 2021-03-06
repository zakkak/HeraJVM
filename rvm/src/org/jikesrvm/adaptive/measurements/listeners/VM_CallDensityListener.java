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
package org.jikesrvm.adaptive.measurements.listeners;

import org.jikesrvm.VM;
import org.jikesrvm.scheduler.VM_Thread;
import org.vmmagic.pragma.Uninterruptible;

/**
 * A simple listener to accumulate counts of total events
 * and the fraction of those events that occured at loop backedges.
 * In effect, this provides a mechanism for estimating the
 * call density of the program.  If most yieldpoints are being taken at
 * backedges, then call density is low.
 */
@Uninterruptible
public final class VM_CallDensityListener extends VM_NullListener {

  private double numSamples = 0;
  private double numBackedgeSamples = 0;

  /**
   * This method is called when its time to record that a
   * yield point has occurred.
   * @param whereFrom Was this a yieldpoint in a PROLOGUE, BACKEDGE, or
   *             EPILOGUE?
   */
  public void update(int whereFrom) {
    numSamples++;
    if (whereFrom == VM_Thread.BACKEDGE) numBackedgeSamples++;
  }

  public double callDensity() {
    return 1 - (numBackedgeSamples / numSamples);
  }

  public void reset() {
    numSamples = 0;
    numBackedgeSamples = 0;
  }

  public void report() {
    VM.sysWriteln("The call density of the program is ", callDensity());
  }
}
