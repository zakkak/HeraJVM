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

package org.jikesrvm;

import org.jikesrvm.annotations.NoSubArchCompile;

/**
 * Flags that specify the configuration of our virtual machine.
 *
 * Note: Changing any <code>final</code> flags requires that the whole vm
 *       be recompiled and rebuilt after their values are changed.
 */
@NoSubArchCompile
public abstract class VM_Configuration {

  //TODO: Split target specific configuration into separate file
  public static final org.jikesrvm.ia32.VM_MachineSpecificIA.IA32 archHelper = org.jikesrvm.ia32.VM_MachineSpecificIA.IA32.singleton;
	public static final org.jikesrvm.cellspu.VM_MachineSpecificCellSpu.CellSpu subArchHelper = org.jikesrvm.cellspu.VM_MachineSpecificCellSpu.CellSpu.singleton;

  public static final boolean BuildForPowerPC = false;
  public static final boolean BuildForIA32 = true;
  public static final boolean BuildForSSE2 = BuildForIA32 && true;
  public static final boolean BuildForSSE2Full = BuildForSSE2 && true;

  public static final boolean HybridRVM	     = true;
  public static final boolean SubArchCellSpu = true;


  public static final boolean BuildFor32Addr = true;
  public static final boolean BuildFor64Addr = !BuildFor32Addr;

  public static final boolean BuildForAix = false;
  public static final boolean BuildForLinux = false;
  public static final boolean BuildForSolaris = false; 
  public static final boolean BuildForSubordinate = true; 
  public static final boolean BuildForOsx = true;

  public static final boolean LittleEndian = BuildForIA32;

  /* ABI selection.  Exactly one of these variables will be true in each build. */
  public static final boolean BuildForMachOABI = BuildForOsx;
  public static final boolean BuildForPowerOpenABI = BuildForAix || (BuildForLinux && BuildForPowerPC && BuildFor64Addr);
  public static final boolean BuildForSVR4ABI = !(BuildForPowerOpenABI || BuildForMachOABI);

  /** Do we have the facilities to intercept blocking system calls? */
  public static final boolean withoutInterceptBlockingSystemCalls = BuildForAix || BuildForOsx || BuildForSolaris;

  /** Are we using Classpath's portable native sync feature? */
  public static final boolean PortableNativeSync = true;

  /**
   * Can a dereference of a null pointer result in an access
   * to 'low' memory addresses that must be explicitly guarded because the
   * target OS doesn't allow us to read protect low memory?
   */
  public static final boolean ExplicitlyGuardLowMemory = BuildForAix;

  public static final boolean BuildWithAllClasses = false;


 /** Assertion checking.
      <dl>
      <dt>false</dt>  <dd> no assertion checking at runtime</dd>
      <dt>true  </dt> <dd> execute assertion checks at runtime</dd>
      <dl>

      Note: code your assertion checks as
      <pre>
        if (VM.VerifyAssertions)
          VM._assert(xxx);
      </pre>
  */
  public static final boolean VerifyAssertions = true;
  public static final boolean ExtremeAssertions = false;

  /**
   * If set, verify that Uninterruptible methods actually cannot be
   * interrupted.
   */
  public static final boolean VerifyUnint = VerifyAssertions;

  // If set, ignore the supression pragma and print all warning messages.
  public static final boolean ParanoidVerifyUnint = false;

  // Is this an adaptive build?
  public static final boolean BuildForAdaptiveSystem = false;

  // Is this an opt compiler build?
  public static final boolean BuildForOptCompiler = false;

   // build with Base boot image compiler
   public static final boolean BuildWithBaseBootImageCompiler = true;

  // Interface method invocation.
  // We have five mechanisms:
  //   IMT-based (Alpern, Cocchi, Fink, Grove, and Lieber).
  //    - embedded directly in the TIB
  //    - indirectly accessed off the TIB
  //   ITable-based
  //    - directly indexed (by interface id) iTables.
  //    - searched (at dispatch time);
  //   Naive, class object is searched for matching method on every dispatch.
  public static final boolean BuildForIMTInterfaceInvocation = true;
  public static final boolean BuildForIndirectIMT = BuildForIMTInterfaceInvocation;
  public static final boolean BuildForEmbeddedIMT = !BuildForIndirectIMT && BuildForIMTInterfaceInvocation;
  public static final boolean BuildForITableInterfaceInvocation = !BuildForIMTInterfaceInvocation;
  public static final boolean DirectlyIndexedITables = false;

  /** Epilogue yieldpoints increase sampling accuracy for adaptive
      recompilation.  In particular, they are key for large, leaf, loop-free
      methods.  */
  public static final boolean UseEpilogueYieldPoints = BuildForAdaptiveSystem;

  /* NUmber of allocations between gc's during stress testing. Set to 0 to disable. */
  public static final int StressGCAllocationInterval = 0;
  public static final boolean ForceFrequentGC = 0 != StressGCAllocationInterval;

  /*
   * We often need to slightly tweak the VM boot sequence and/or
   * the library/VM interface depending on the version of GNU classpath
   * we are building against.
   * We always have at least two versions we are supporting (CVS Head and
   * the most recent release).  Sometimes we also support some back-level
   * releases of GNU classpath.
   * For each supported released version, define a static final boolean.
   * We don't define a boolean for CVS head because we prefer to define
   * CVS head as the ! of all other variables.
   * This makes it easier to find an eliminate
   * old code when we move up to the next version.
   */
  private static final int ClasspathVersion = (int)96.1;
  public static final boolean BuildWithGCTrace = false;
  public static final boolean BuildWithGCSpy = false;

  /**
   * Alignment checking (for IA32 only; for debugging purposes only).
   * To enable, build with -Dconfig.alignment-checking=true.
   * Important: You'll also need to build without SSE (-Dtarget.arch.sse2=none) and
   * run Jikes with only one processor (-X:processors=1).
   */
  public static final boolean AlignmentChecking = false;
}
