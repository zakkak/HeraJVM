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

import org.jikesrvm.classloader.VM_TypeReference;
import org.jikesrvm.compilers.opt.OPT_Bits;
import org.vmmagic.unboxed.Offset;

/**
 * Represents a constant long operand.
 *
 * @see OPT_Operand
 */
public final class OPT_LongConstantOperand extends OPT_ConstantOperand {

  /**
   * Value of this operand.
   */
  public long value;

  /**
   * Offset in JTOC where this long constant lives. (0 for constants
   * obtained from constant folding)
   * //KV: is this field still necessary
   */
  public Offset offset;

  /**
   * Constructs a new long constant operand with the specified value.
   *
   * @param v value
   */
  public OPT_LongConstantOperand(long v) {
    value = v;
    offset = Offset.zero();
  }

  /**
   * Constructs a new long constant operand with the specified value and JTOC offset.
   * //KV: is this method still necessary
   * @param v value
   * @param i offset in the jtoc
   */
  public OPT_LongConstantOperand(long v, Offset i) {
    value = v;
    offset = i;
  }

  /**
   * Return the {@link VM_TypeReference} of the value represented by the operand.
   *
   * @return VM_TypeReference.Long
   */
  public VM_TypeReference getType() {
    return VM_TypeReference.Long;
  }

  /**
   * Does the operand represent a value of the long data type?
   *
   * @return <code>true</code>
   */
  public boolean isLong() {
    return true;
  }

  /**
   * Return the lower 32 bits (as an int) of value
   */
  public int lower32() {
    return OPT_Bits.lower32(value);
  }

  /**
   * Return the upper 32 bits (as an int) of value
   */
  public int upper32() {
    return OPT_Bits.upper32(value);
  }

  /**
   * Return a new operand that is semantically equivalent to <code>this</code>.
   *
   * @return a copy of <code>this</code>
   */
  public OPT_Operand copy() {
    return new OPT_LongConstantOperand(value, offset);
  }

  /**
   * Are two operands semantically equivalent?
   *
   * @param op other operand
   * @return   <code>true</code> if <code>this</code> and <code>op</code>
   *           are semantically equivalent or <code>false</code>
   *           if they are not.
   */
  public boolean similar(OPT_Operand op) {
    return (op instanceof OPT_LongConstantOperand) && (value == ((OPT_LongConstantOperand) op).value);
  }

  /**
   * Returns the string representation of this operand.
   *
   * @return a string representation of this operand.
   */
  public String toString() {
    return Long.toString(value) + "L";
  }

}
