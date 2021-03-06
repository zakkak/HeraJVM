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
package org.jikesrvm.osr;

/**
 * aconst_null
 */
public class BC_AConstNull extends OSR_PseudoBytecode {
  public byte[] getBytes() {
    byte[] codes = new byte[1];
    codes[0] = 1;
    return codes;
  }

  public int getSize() {
    return 1;
  }

  public int stackChanges() {
    return 1;
  }

  public String toString() {
    return "aconst_null";
  }
}
