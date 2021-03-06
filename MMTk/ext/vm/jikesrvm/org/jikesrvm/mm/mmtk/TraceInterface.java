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
package org.jikesrvm.mm.mmtk;

import org.jikesrvm.classloader.VM_Method;
import org.jikesrvm.classloader.VM_MemberReference;
import org.jikesrvm.classloader.VM_Type;

import org.jikesrvm.compilers.opt.VM_OptCompiledMethod;
import org.jikesrvm.compilers.opt.VM_OptMachineCodeMap;
import org.jikesrvm.compilers.opt.VM_OptEncodedCallSiteTree;

import org.jikesrvm.VM;
import org.jikesrvm.VM_Services;
import org.jikesrvm.runtime.VM_Magic;
import org.jikesrvm.compilers.baseline.VM_BaselineCompiledMethod;
import org.jikesrvm.compilers.common.VM_CompiledMethod;
import org.jikesrvm.compilers.common.VM_CompiledMethods;

import org.jikesrvm.objectmodel.VM_MiscHeader;
import org.jikesrvm.objectmodel.VM_ObjectModel;
import org.jikesrvm.scheduler.VM_Scheduler;
import org.jikesrvm.ArchitectureSpecific;
import org.jikesrvm.objectmodel.VM_TIBLayoutConstants;
import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * Class that supports scanning Objects or Arrays for references
 * during tracing, handling those references, and computing death times
 */
@Uninterruptible public final class TraceInterface extends org.mmtk.vm.TraceInterface implements ArchitectureSpecific.VM_ArchConstants {

  /***********************************************************************
   *
   * Class variables
   */
  private static byte[][] allocCallMethods;

  static {
    /* Build the list of "own methods" */
    allocCallMethods = new byte[13][];
    allocCallMethods[0] = "postAlloc".getBytes();
    allocCallMethods[1] = "traceAlloc".getBytes();
    allocCallMethods[2] = "allocateScalar".getBytes();
    allocCallMethods[3] = "allocateArray".getBytes();
    allocCallMethods[4] = "clone".getBytes();
    allocCallMethods[5] = "alloc".getBytes();
    allocCallMethods[6] = "buildMultiDimensionalArray".getBytes();
    allocCallMethods[7] = "resolvedNewScalar".getBytes();
    allocCallMethods[8] = "resolvedNewArray".getBytes();
    allocCallMethods[9] = "unresolvedNewScalar".getBytes();
    allocCallMethods[10] = "unresolvedNewArray".getBytes();
    allocCallMethods[11] = "cloneScalar".getBytes();
    allocCallMethods[12] = "cloneArray".getBytes();
  }

  /***********************************************************************
   *
   * Public Methods
   */

  /**
   * Returns if the VM is ready for a garbage collection.
   *
   * @return True if the RVM is ready for GC, false otherwise.
   */
  public boolean gcEnabled() {
    return VM_Scheduler.gcEnabled();
  }

  /**
   * Given a method name, determine if it is a "real" method or one
   * used for allocation/tracing.
   *
   * @param name The method name to test as an array of bytes
   * @return True if the method is a "real" method, false otherwise.
   */
  private boolean isAllocCall(byte[] name) {
    for (int i = 0; i < allocCallMethods.length; i++) {
      byte[] funcName = VM_Services.getArrayNoBarrier(allocCallMethods, i);
      if (VM_Magic.getArrayLength(name) == VM_Magic.getArrayLength(funcName)) {
        /* Compare the letters in the allocCallMethod */
        int j = VM_Magic.getArrayLength(funcName) - 1;
        while (j >= 0) {
          if (VM_Services.getArrayNoBarrier(name, j) !=
              VM_Services.getArrayNoBarrier(funcName, j))
            break;
          j--;
        }
        if (j == -1)
          return true;
      }
    }
    return false;
  }

  /**
   * This adjusts the offset into an object to reflect what it would look like
   * if the fields were laid out in memory space immediately after the object
   * pointer.
   *
   * @param isScalar If this is a pointer store to a scalar object
   * @param src The address of the source object
   * @param slot The address within <code>src</code> into which
   * the update will be stored
   * @return The easy to understand offset of the slot
   */
  public Offset adjustSlotOffset(boolean isScalar,
                                              ObjectReference src,
                                              Address slot) {
    /* Offset scalar objects so that the fields appear to begin at offset 0
       of the object. */
    Offset offset = slot.diff(src.toAddress());
    if (isScalar)
      return offset.minus(getHeaderEndOffset());
    else
      return offset;
  }

  /**
   * This skips over the frames added by the tracing algorithm, outputs
   * information identifying the method the containts the "new" call triggering
   * the allocation, and returns the address of the first non-trace, non-alloc
   * stack frame.
   *
   * @param typeRef The type reference (tib) of the object just allocated
   * @return The frame pointer address for the method that allocated the object
   */
  @NoInline
  @Interruptible // This can't be uninterruptible --- it is an IO routine
  public LocalAddress skipOwnFramesAndDump(ObjectReference typeRef) {
    Object[] tib = VM_Magic.addressAsObjectArray(typeRef.toAddress());
    VM_Method m = null;
    int bci = -1;
    int compiledMethodID = 0;
    Offset ipOffset = Offset.zero();
    LocalAddress fp = VM_Magic.getFramePointer();
    LocalAddress ip = VM_Magic.getReturnAddress(fp);
    fp = VM_Magic.getCallerFramePointer(fp);
    // This code borrows heavily from VM_Scheduler.dumpStack
    while (VM_Magic.getCallerFramePointer(fp).NE(VM_Magic.addressAsLocalAddress(STACKFRAME_SENTINEL_FP))) {
      compiledMethodID = VM_Magic.getCompiledMethodID(fp);
      if (compiledMethodID != INVISIBLE_METHOD_ID) {
        // normal java frame(s)
        VM_CompiledMethod compiledMethod =
          VM_CompiledMethods.getCompiledMethod(compiledMethodID);
        if (compiledMethod.getCompilerType() != VM_CompiledMethod.TRAP) {
          ipOffset = compiledMethod.getInstructionOffset(ip);
          m = compiledMethod.getMethod();
          if (VM.BuildForOptCompiler && compiledMethod.getCompilerType() == VM_CompiledMethod.OPT) {
            VM_OptCompiledMethod optInfo = (VM_OptCompiledMethod)compiledMethod;
            /* Opt stack frames may contain multiple inlined methods. */
            VM_OptMachineCodeMap map = optInfo.getMCMap();
            int iei = map.getInlineEncodingForMCOffset(ipOffset);
            if (iei >= 0) {
              int[] inlineEncoding = map.inlineEncoding;
              boolean allocCall = true;
              bci = map.getBytecodeIndexForMCOffset(ipOffset);
              for (int j = iei; j >= 0 && allocCall;
                   j = VM_OptEncodedCallSiteTree.getParent(j,inlineEncoding)) {
                int mid = VM_OptEncodedCallSiteTree.getMethodID(j, inlineEncoding);
                m = VM_MemberReference.getMemberRef(mid).asMethodReference().getResolvedMember();
                if (!isAllocCall(m.getName().getBytes()))
                  allocCall = false;
                if (j > 0)
                  bci = VM_OptEncodedCallSiteTree.getByteCodeOffset(j,
                                                                    inlineEncoding);
              }
              if (!allocCall)
                break;
            }
          } else {
            if (!isAllocCall(m.getName().getBytes())) {
              VM_BaselineCompiledMethod baseInfo =
                (VM_BaselineCompiledMethod)compiledMethod;
              bci = baseInfo.findBytecodeIndexForInstruction(ipOffset.toWord().lsh(INSTRUCTION_WIDTH).toOffset());
              break;
            }
          }
        }
      }
      ip = VM_Magic.getReturnAddress(fp);
      fp = VM_Magic.getCallerFramePointer(fp);
    }
    if (m != null) {
      int allocid = (((compiledMethodID & 0x0000ffff) << 15) ^
                     ((compiledMethodID & 0xffff0000) >> 16) ^
                     ipOffset.toInt()) & ~0x80000000;

      /* Now print the location string. */
      VM.sysWrite('\n');
      VM.writeHex(allocid);
      VM.sysWrite('-');
      VM.sysWrite('>');
      VM.sysWrite('[');
      VM.writeHex(compiledMethodID);
      VM.sysWrite(']');
      m.getDeclaringClass().getDescriptor().sysWrite();
      VM.sysWrite(':');
      m.getName().sysWrite();
      m.getDescriptor().sysWrite();
      VM.sysWrite(':');
      VM.writeHex(bci);
      VM.sysWrite('\t');
      VM_Type type = VM_Magic.objectAsType(tib[VM_TIBLayoutConstants.TIB_TYPE_INDEX]);
      type.getDescriptor().sysWrite();
      VM.sysWrite('\n');
    }
    return fp;
  }

  /***********************************************************************
   *
   * Wrapper methods
   */

  @Inline
  public void updateDeathTime(Object obj) {
    VM_MiscHeader.updateDeathTime(obj);
  }

  @Inline
  public void setDeathTime(ObjectReference ref, Word time_) {
    VM_MiscHeader.setDeathTime(ref.toObject(), time_);
  }

  @Inline
  public void setLink(ObjectReference ref, ObjectReference link) {
    VM_MiscHeader.setLink(ref.toObject(), link);
  }

  @Inline
  public void updateTime(Word time_) {
    VM_MiscHeader.updateTime(time_);
  }

  @Inline
  public Word getOID(ObjectReference ref) {
    return VM_MiscHeader.getOID(ref.toObject());
  }

  @Inline
  public Word getDeathTime(ObjectReference ref) {
    return VM_MiscHeader.getDeathTime(ref.toObject());
  }

  @Inline
  public ObjectReference getLink(ObjectReference ref) {
    return VM_MiscHeader.getLink(ref.toObject());
  }

  @Inline
  public Address getBootImageLink() {
    return VM_MiscHeader.getBootImageLink();
  }

  @Inline
  public Word getOID() {
    return VM_MiscHeader.getOID();
  }

  @Inline
  public void setOID(Word oid) {
    VM_MiscHeader.setOID(oid);
  }

  @Inline
  public int getHeaderSize() {
    return VM_MiscHeader.getHeaderSize();
  }

  @Inline
  public int getHeaderEndOffset() {
    return VM_ObjectModel.getHeaderEndOffset();
  }
}
