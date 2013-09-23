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
package org.jikesrvm.runtime;

import org.vmmagic.pragma.Uninterruptible;

/**
 * Primitives from which to build interval and absolute timers.
 */
@Uninterruptible
public class VM_Time {

  /**
   * Convert a long representing a time in nanoseconds into
   * a double representing the same time in milliseconds.
   * @param nt a time in nanoseconds
   * @return nt converted to milliseconds
   */
  public static double nanosToMillis(long c) {
    return ((double)c)/1e6;
  }

  /**
   * Return the value of a hardware cycle counter (RDTSC on IA32, time base register on PPC).
   * This is a very cheap, but also unreliable "timing" mechanism.
   * There is absolutely no guarantee that the values returned from this method will
   * either by monotonic (ie, "time" can go backwards) or
   * smooth ("time" can appear to move at a variable rate).
   * This method should only be used for approximate timing in frequently executed code.
   * We intentionally do not provide an API for converting from cycles to seconds because
   * the conversion cannot be reliably supported on all of our platforms.
   */
  public static long cycles() {
    return VM_Magic.getTimeBase();
  }

  /**
   * Same semantics as java.lang.System.nanoTime();
   * This (or java.lang.System.nanoTime) is the
   * preferred API for VM internal timing functions.
   * @return a monotonic timer value in nanoseconds.
   */
  public static long nanoTime() {
    return VM_SysCall.sysCall.sysNanoTime();
  }

  /**
   * Time in milliseconds (epoch Jan 1 1970).
   */
  public static long currentTimeMillis() {
    return VM_SysCall.sysCall.sysCurrentTimeMillis();
  }

  public static double nanosToSecs(long nanos) {
    return ((double)nanos)/1E9;
  }

  public static long secsToNanos(double secs) {
    return (long)(secs*1E9);
  }
}