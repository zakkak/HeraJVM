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
package sun.misc;

import java.lang.reflect.Field;

import org.jikesrvm.classloader.VM_Field;
import org.jikesrvm.classloader.VM_Type;
import org.jikesrvm.runtime.VM_Magic;
import org.jikesrvm.scheduler.VM_Synchronization;
import org.jikesrvm.scheduler.VM_Thread;
import org.vmmagic.unboxed.Offset;

public final class Unsafe {
  private static final Unsafe unsafe = new Unsafe();

  private Unsafe() {}

  public static Unsafe getUnsafe() {
    SecurityManager sm = System.getSecurityManager();
    if (sm != null)
      sm.checkPropertiesAccess();
    return unsafe;
  }

  private Offset longToOffset(long offset) {
    return Offset.fromIntSignExtend((int)offset);
  }

  public long objectFieldOffset(Field field) {
    VM_Field vmfield = java.lang.reflect.JikesRVMSupport.getFieldOf(field);
    return vmfield.getOffset().toLong();
  }

  public boolean compareAndSwapInt(Object obj,long offset,int expect,int update) {
    Offset off = longToOffset(offset);
    return VM_Synchronization.tryCompareAndSwap(obj, off, expect, update);
  }

  public boolean compareAndSwapLong(Object obj,long offset,long expect,long update) {
    Offset off = Offset.fromIntSignExtend((int)offset);
    return VM_Synchronization.tryCompareAndSwap(obj, off, expect, update);
  }

  public boolean compareAndSwapObject(Object obj,long offset,Object expect,Object update) {
    Offset off = Offset.fromIntSignExtend((int)offset);
    return VM_Synchronization.tryCompareAndSwap(obj, off, expect, update);
  }

  public void putOrderedInt(Object obj,long offset,int value) {
    Offset off = longToOffset(offset);
    VM_Magic.setIntAtOffset(obj,off,value);
  }

  public void putOrderedLong(Object obj,long offset,long value) {
    Offset off = longToOffset(offset);
    VM_Magic.setLongAtOffset(obj,off,value);
  }

  public void putOrderedObject(Object obj,long offset,Object value) {
    Offset off = longToOffset(offset);
    VM_Magic.setObjectAtOffset(obj,off,value);
   }

  public void putIntVolatile(Object obj,long offset,int value) {
    Offset off = longToOffset(offset);
    VM_Magic.setIntAtOffset(obj,off,value);
  }

  public int getIntVolatile(Object obj,long offset) {
    Offset off = longToOffset(offset);
    return VM_Magic.getIntAtOffset(obj,off);
  }

  public void putLongVolatile(Object obj,long offset,long value) {
    Offset off = longToOffset(offset);
    VM_Magic.setLongAtOffset(obj,off,value);
   }

  public void putLong(Object obj,long offset,long value) {
    Offset off = longToOffset(offset);
    VM_Magic.setLongAtOffset(obj,off,value);
  }

  public long getLongVolatile(Object obj,long offset) {
    Offset off = longToOffset(offset);
    return VM_Magic.getLongAtOffset(obj,off);
  }

  public long getLong(Object obj,long offset) {
    Offset off = longToOffset(offset);
    return VM_Magic.getLongAtOffset(obj,off);
  }

  public void putObjectVolatile(Object obj,long offset,Object value) {
    Offset off = longToOffset(offset);
    VM_Magic.setObjectAtOffset(obj,off,value);
  }

  public void putObject(Object obj,long offset,Object value) {
    Offset off = longToOffset(offset);
    VM_Magic.setObjectAtOffset(obj,off,value);
  }

  public Object getObjectVolatile(Object obj,long offset) {
    Offset off = longToOffset(offset);
    return VM_Magic.getObjectAtOffset(obj,off);
  }

  public int arrayBaseOffset(Class<?> arrayClass) {
    return 0;
  }

  public int arrayIndexScale(Class<?> arrayClass) {
    VM_Type arrayType = java.lang.JikesRVMSupport.getTypeForClass(arrayClass);
    if (!arrayType.isArrayType()) {
      return 0;
    } else {
      return 1 << arrayType.asArray().getLogElementSize();
    }
  }

  public void unpark(Object thread) {
    VM_Thread vmthread = java.lang.JikesRVMSupport.getThread((Thread)thread);
    if (vmthread != null) {
      vmthread.unpark();
    }
  }

  public void park(boolean isAbsolute,long time) throws Throwable  {
    VM_Thread vmthread = java.lang.JikesRVMSupport.getThread(Thread.currentThread());
    vmthread.park(isAbsolute, time);
  }
}
