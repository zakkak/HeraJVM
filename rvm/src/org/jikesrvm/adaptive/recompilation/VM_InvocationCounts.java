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
package org.jikesrvm.adaptive.recompilation;

import org.jikesrvm.VM;
import org.jikesrvm.adaptive.controller.VM_Controller;
import org.jikesrvm.adaptive.controller.VM_ControllerPlan;
import org.jikesrvm.adaptive.controller.VM_RecompilationStrategy;
import org.jikesrvm.adaptive.recompilation.instrumentation.VM_AOSInstrumentationPlan;
import org.jikesrvm.classloader.VM_NormalMethod;
import org.jikesrvm.compilers.baseline.VM_BaselineCompiler;
import org.jikesrvm.compilers.common.VM_CompiledMethod;
import org.jikesrvm.compilers.common.VM_CompiledMethods;
import org.jikesrvm.compilers.opt.OPT_CompilationPlan;
import org.jikesrvm.compilers.opt.OPT_OptimizationPlanElement;
import org.jikesrvm.compilers.opt.OPT_OptimizationPlanner;
import org.jikesrvm.compilers.opt.OPT_Options;
import org.jikesrvm.runtime.VM_Magic;

/**
 * Runtime system support for using invocation counters in baseline
 * compiled code to select methods for optimizing recompilation
 * by the adaptive system.  Bypasses the normal controller logic:
 * If an invocation counter trips, then the method is enqueued for
 * recompilation at a default optimization level.
 */
public final class VM_InvocationCounts {

  private static int[] counts;
  private static boolean[] processed;

  public static synchronized void allocateCounter(int id) {
    if (counts == null) {
      counts = new int[id + 500];
      processed = new boolean[counts.length];
    }
    if (id >= counts.length) {
      int newSize = counts.length * 2;
      if (newSize <= id) newSize = id + 500;
      int[] tmp = new int[newSize];
      System.arraycopy(counts, 0, tmp, 0, counts.length);
      boolean[] tmp2 = new boolean[newSize];
      System.arraycopy(processed, 0, tmp2, 0, processed.length);
      VM_Magic.sync();
      counts = tmp;
      processed = tmp2;
    }
    counts[id] = VM_Controller.options.INVOCATION_COUNT_THRESHOLD;
  }

  /**
   * Called from baseline compiled code when a method's invocation counter
   * becomes negative and thus must be handled
   */
  static synchronized void counterTripped(int id) {
    counts[id] = 0x7fffffff; // set counter to max int to avoid lots of redundant calls.
    if (processed[id]) return;
    processed[id] = true;
    VM_CompiledMethod cm = VM_CompiledMethods.getCompiledMethod(id);
    if (cm == null) return;
    if (VM.VerifyAssertions) VM._assert(cm.getCompilerType() == VM_CompiledMethod.BASELINE);
    VM_NormalMethod m = (VM_NormalMethod) cm.getMethod();
    OPT_CompilationPlan compPlan = new OPT_CompilationPlan(m, _optPlan, null, _options);
    VM_ControllerPlan cp =
        new VM_ControllerPlan(compPlan, VM_Controller.controllerClock, id, 2.0, 2.0, 2.0); // 2.0 is a bogus number....
    cp.execute();
  }

  /**
   * Create the compilation plan according to the default set
   * of <optimization plan, options> pairs
   */
  public static OPT_CompilationPlan createCompilationPlan(VM_NormalMethod method) {
    return new OPT_CompilationPlan(method, _optPlan, null, _options);
  }

  public static OPT_CompilationPlan createCompilationPlan(VM_NormalMethod method, VM_AOSInstrumentationPlan instPlan) {
    return new OPT_CompilationPlan(method, _optPlan, instPlan, _options);
  }

  /**
   *  Initialize the recompilation strategy.
   *
   *  Note: This uses the command line options to set up the
   *  optimization plans, so this must be run after the command line
   *  options are available.
   */
  public static void init() {
    createOptimizationPlan();
    VM_BaselineCompiler.options.INVOCATION_COUNTERS = true;
  }

  private static OPT_OptimizationPlanElement[] _optPlan;
  private static OPT_Options _options;

  /**
   * Create the default set of <optimization plan, options> pairs
   * Process optimizing compiler command line options.
   */
  static void createOptimizationPlan() {
    _options = new OPT_Options();

    int optLevel = VM_Controller.options.INVOCATION_COUNT_OPT_LEVEL;
    String[] optCompilerOptions = VM_Controller.getOptCompilerOptions();
    _options.setOptLevel(optLevel);
    VM_RecompilationStrategy.processCommandLineOptions(_options, optLevel, optLevel, optCompilerOptions);
    _optPlan = OPT_OptimizationPlanner.createOptimizationPlan(_options);
  }

}
