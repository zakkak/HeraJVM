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

import org.jikesrvm.VM;
import org.jikesrvm.classloader.VM_Field;
import org.jikesrvm.classloader.VM_FieldReference;
import org.jikesrvm.classloader.VM_TypeReference;
import org.jikesrvm.compilers.opt.OPT_ClassLoaderProxy;
import org.jikesrvm.compilers.opt.OPT_OptimizingCompilerException;
import org.vmmagic.unboxed.Offset;

//TODO - Deal with Subarch

/**
 * Represents a location in memory. Used to keep track of memory aliasing.
 *
 * @see OPT_Operand
 */
public final class OPT_LocationOperand extends OPT_Operand implements org.jikesrvm.compilers.opt.OPT_Constants {

  /*
   * TODO: Now that we don't pay a large penalty for dynamic type checks
   * of non-final classes, redesign this mess to have separate subclasses
   * of location operands for each type of memory access.
   * In the process, also switch to using synthetic VM_Fields
   * for the various pieces of the object header
   * (something like the following might work):
   *   (VM_Field) VM.getMember("[I", "length", "I");   .
   *      . . .                                        } all primitive types
   * (VM_Field) VM.getMember("[J", "length", "I");   '
   * (VM_Field) VM.getMember("[Ljava/lang/Object;", "length", "I");
   * (VM_Field) VM.getMember("Ljava/lang/Object;", "TIB", "[I");
   */

  /** Enumeration of Access type */
  public static final int FIELD_ACCESS = 0;
  /** Enumeration of Access type */
  public static final int ARRAY_ACCESS = 1;
  /** Enumeration of Access type */
  public static final int JTOC_ACCESS = 2;
  /** Enumeration of Access type */
  public static final int SPILL_ACCESS = 3;
  /** Enumeration of Access type */
  public static final int ALENGTH_ACCESS = 4;
  /** Enumeration of Access type */
  public static final int METHOD_ACCESS = 5;

  /**
   * The type of this location.
   */
  int type;

  /**
   * Field that corresponds to this location;
   * null if this is not a field access.
   */
  VM_FieldReference fieldRef;

  /**
   * Method operand that corresponds to this location;
   * null if this is not a method access.
   */
  OPT_MethodOperand methOp;

  /**
   * Array element type that corresponds to the type of the array that contains
   * this location; null if this is not an array access.
   */
  VM_TypeReference arrayElementType;

  /**
   * JTOC index that corresponds to this location.
   * -1 if this is not a JTOC access.
   */
  Offset JTOCoffset = Offset.max();

  /**
   * Spill offset that corresponds to this location.
   * -1 if this is not a spill access.
   */
  int spillOffset = -1;

  /**
   * Reference number.  Set by alias analysis.
   * Used to distinguish same-type accesses.
   */
  int refNumber;

  /**
   * Constructs a new location operand with the given field.
   * @param loc location
   */
  public OPT_LocationOperand(VM_FieldReference loc) {
    type = FIELD_ACCESS;
    fieldRef = loc;
  }

  /**
   * Constructs a new location operand with the given field
   * @param loc location
   */
  public OPT_LocationOperand(VM_Field loc) {
    type = FIELD_ACCESS;
    fieldRef = loc.getMemberRef().asFieldReference();
  }

  /**
   * Constructs a new location operand with the given method
   *
   * @param m  Method operand that corresponds to this location
   */
  public OPT_LocationOperand(OPT_MethodOperand m) {
    type = METHOD_ACCESS;
    methOp = m;
  }

  /**
   * Constructs a new location operand with the given array element type.
   *
   * @param t    Array element type
   */
  public OPT_LocationOperand(VM_TypeReference t) {
    type = ARRAY_ACCESS;
    arrayElementType = t;
  }

  /**
   * Constructs a new location operand with the given JTOC offset
   */
  public OPT_LocationOperand(Offset jtocOffset) {
    type = JTOC_ACCESS;
    JTOCoffset = jtocOffset;
  }

  /**
   * Constructs a new location operand with the given spill offset.
   */
  public OPT_LocationOperand(int index) {
    if (VM.VerifyAssertions) VM._assert(index <= 0);
    type = SPILL_ACCESS;
    spillOffset = index;
  }

  /**
   * Constructs a new location operand for array length access.
   */
  public OPT_LocationOperand() {
    type = ALENGTH_ACCESS;
  }

  /**
   * Return the {@link VM_TypeReference} of the value represented by the operand.
   *
   * @return this method shouldn't be called and will throw an {@link
   * OPT_OptimizingCompilerException}
   */
  public VM_TypeReference getType() {
    throw new OPT_OptimizingCompilerException("Getting the type for this operand has no defined meaning");
  }

  public OPT_LocationOperand asFieldAccess() { return this; }

  public OPT_LocationOperand asArrayAccess() { return this; }

  public OPT_LocationOperand asJTOCAccess() { return this; }

  public OPT_LocationOperand asSpillAccess() { return this; }

  public OPT_LocationOperand asALengthAccess() { return this; }

  public OPT_LocationOperand asMethodAccess() { return this; }

  public VM_FieldReference getFieldRef() { return fieldRef; }

  public VM_TypeReference getElementType() { return arrayElementType; }

  //public final int getIndex() { return JTOCoffset; }
  public Offset getJTOCoffset() { return JTOCoffset; }

  public int getOffset() { return spillOffset; }

  public boolean isFieldAccess() { return type == FIELD_ACCESS; }

  public boolean isArrayAccess() { return type == ARRAY_ACCESS; }

  public boolean isJTOCAccess() { return type == JTOC_ACCESS; }

  public boolean isSpillAccess() { return type == SPILL_ACCESS; }

  public boolean isALengthAccess() { return type == ALENGTH_ACCESS; }

  public boolean isMethodAccess() { return type == METHOD_ACCESS; }

  /**
   * Is the accessed location possibly volatile?
   */
  public boolean mayBeVolatile() {
    if (!isFieldAccess()) return false;
    VM_Field f = fieldRef.peekResolvedField(false);
    return f == null || f.isVolatile();
  }

  /**
   * Return a new operand that is semantically equivalent to <code>this</code>.
   *
   * @return a copy of <code>this</code>
   */
  public OPT_Operand copy() {
    OPT_LocationOperand o = null;
    switch (type) {
      case FIELD_ACCESS:
        o = new OPT_LocationOperand(fieldRef);
        break;
      case ARRAY_ACCESS:
        o = new OPT_LocationOperand(arrayElementType);
        break;
      case JTOC_ACCESS:
        o = new OPT_LocationOperand(JTOCoffset);
        break;
      case SPILL_ACCESS:
        o = new OPT_LocationOperand(spillOffset);
        break;
      case ALENGTH_ACCESS:
        o = new OPT_LocationOperand();
        break;
      case METHOD_ACCESS:
        o = new OPT_LocationOperand(methOp);
        break;
      default:
        o = new OPT_LocationOperand();
        break;
    }
    return o;
  }

  // NOTE: not checking for (t1==null xor t2==null) for efficiency
  private static boolean arrayMayBeAliased(VM_TypeReference t1, VM_TypeReference t2) {
    return ((t1 == t2) ||
            (OPT_ClassLoaderProxy.includesType(t1, t2) != NO) ||
            (OPT_ClassLoaderProxy.includesType(t2, t1) != NO));
  }

  /**
   * Returns true if operands op1 and op2 may be aliased.
   *
   * @param op1 the first operand
   * @param op2 the second operand
   * @return <code>true</code> if the operands might be aliased or
   *         <code>false</code> if they are definitely not aliased
   */
  public static boolean mayBeAliased(OPT_LocationOperand op1, OPT_LocationOperand op2) {
    if (op1 == null || op2 == null) return true;        // be conservative
    if (op1.type != op2.type) return false;
    if (op1.fieldRef != null) {
      return !op1.fieldRef.definitelyDifferent(op2.fieldRef);
    } else {
      return arrayMayBeAliased(op1.arrayElementType, op2.arrayElementType) &&
             (op1.JTOCoffset.EQ(op2.JTOCoffset)) &&
             (op1.spillOffset == op2.spillOffset);
    }
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
    return (op instanceof OPT_LocationOperand) && mayBeAliased(this, (OPT_LocationOperand) op);
  }

  /**
   * Returns the string representation of this operand.
   *
   * @return a string representation of this operand.
   */
  public String toString() {
    if (methOp != null) return methOp.toString();
    switch (type) {
      case METHOD_ACCESS:
        return "<mem loc: methOp is null!>";
      case FIELD_ACCESS:
        return "<mem loc: " + fieldRef.getType().getName() + "." + fieldRef.getName() + ">";
      case ARRAY_ACCESS:
        return "<mem loc: array " + arrayElementType + "[]>";
      case JTOC_ACCESS:
        return "<mem loc: JTOC @" + VM.addressAsHexString(JTOCoffset.toWord().toAddress()) + ">";
      case SPILL_ACCESS:
        return "<mem loc: spill FP " + spillOffset + ">";
      case ALENGTH_ACCESS:
        return "<mem loc: array length>";
    }
    return "<mem loc: no aliases>";
  }
}
