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

import org.jikesrvm.ArchitectureSpecific.VM_OutOfLineMachineCode;
import org.jikesrvm.ArchitectureSpecific.VM_ProcessorLocalState;
import org.jikesrvm.SubordinateArchitecture.VM_SubArchBootRecord;
import org.jikesrvm.adaptive.controller.VM_Controller;
import org.jikesrvm.adaptive.util.VM_CompilerAdvice;
import org.jikesrvm.annotations.NoSubArchCompile;
import org.jikesrvm.classloader.VM_Atom;
import org.jikesrvm.classloader.VM_BootstrapClassLoader;
import org.jikesrvm.classloader.VM_Class;
import org.jikesrvm.classloader.VM_ClassLoader;
import org.jikesrvm.classloader.VM_Member;
import org.jikesrvm.classloader.VM_MemberReference;
import org.jikesrvm.classloader.VM_Method;
import org.jikesrvm.classloader.VM_TypeDescriptorParsing;
import org.jikesrvm.classloader.VM_TypeReference;
import org.jikesrvm.compilers.baseline.VM_BaselineCompiler;
import org.jikesrvm.compilers.baseline.VM_EdgeCounts;
import org.jikesrvm.compilers.common.VM_BootImageCompiler;
import org.jikesrvm.compilers.common.VM_RuntimeCompiler;
import org.jikesrvm.memorymanagers.mminterface.MM_Interface;
import org.jikesrvm.runtime.VM_BootRecord;
import org.jikesrvm.runtime.VM_DynamicLibrary;
import org.jikesrvm.runtime.VM_EntrypointHelper;
import org.jikesrvm.runtime.VM_Entrypoints;
import org.jikesrvm.runtime.VM_ExitStatus;
import org.jikesrvm.runtime.VM_Magic;
import org.jikesrvm.runtime.VM_Runtime;
import org.jikesrvm.runtime.VM_SubArchStatics;
import org.jikesrvm.runtime.VM_SysCall;

import static org.jikesrvm.runtime.VM_SysCall.sysCall;
import org.jikesrvm.scheduler.VM_Lock;
import org.jikesrvm.scheduler.VM_MainThread;
import org.jikesrvm.scheduler.VM_Processor;
import org.jikesrvm.scheduler.VM_Scheduler;
import org.jikesrvm.scheduler.VM_Synchronization;
import org.jikesrvm.scheduler.VM_Thread;
import org.jikesrvm.scheduler.greenthreads.JikesRVMSocketImpl;
import org.jikesrvm.scheduler.greenthreads.VM_FileSystem;
import org.jikesrvm.scheduler.greenthreads.VM_GreenScheduler;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.LogicallyUninterruptible;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Extent;
import org.vmmagic.unboxed.LocalAddress;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/**
 * A virtual machine.
 */
@Uninterruptible
public class VM extends VM_Properties implements VM_Constants, VM_ExitStatus {

  /**
   * Reference to the main thread that is the first none VM thread run
   */
  public static VM_MainThread mainThread;
  
  
  /**
   * Details required to boot up subarch processors
   */
  public static VM_SubArchBootRecord subArchBootRecord;

  //----------------------------------------------------------------------//
  //                          Initialization.                             //
  //----------------------------------------------------------------------//

  /**
   * Prepare vm classes for use by boot image writer.
   * @param classPath class path to be used by VM_ClassLoader
   * @param bootCompilerArgs command line arguments for the bootimage compiler
   */
  @NoSubArchCompile
  @Interruptible
  public static void initForBootImageWriter(String classPath, String[] bootCompilerArgs) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    if (VM.VerifyAssertions) VM._assert(!VM.runningTool);
    writingBootImage = true;
    init(classPath, bootCompilerArgs);
  }

  /**
   * Prepare vm classes for use by tools.
   */
  @NoSubArchCompile
  @Interruptible
  public static void initForTool() {
    initForTool(System.getProperty("java.class.path"));
  }

  /**
   * Prepare vm classes for use by tools.
   * @param classpath class path to be used by VM_ClassLoader
   */
  @NoSubArchCompile
  @Interruptible
  public static void initForTool(String classpath) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    if (VM.VerifyAssertions) VM._assert(!VM.writingBootImage);
    runningTool = true;
    init(classpath, null);
  }

  /**
   * Begin vm execution.
   * Uninterruptible because we are not setup to execute a yieldpoint
   * or stackoverflow check in the prologue this early in booting.
   *
   * The following machine registers are set by "C" bootstrap program
   * before calling this method:
   *    JTOC_POINTER        - required for accessing globals
   *    FRAME_POINTER       - required for accessing locals
   *    THREAD_ID_REGISTER  - required for method prolog (stack overflow check)
   * @exception Exception
   */
  @NoSubArchCompile
  @UninterruptibleNoWarn
  public static void boot() {
    writingBootImage = false;
    runningVM = true;
    runningAsSubsystem = false;
    verboseBoot = VM_BootRecord.the_boot_record.verboseBoot;

    sysWriteLockOffset = VM_Entrypoints.sysWriteLockField.getOffset();
    if (verboseBoot >= 1) VM.sysWriteln("Booting");

    // Set up the current VM_Processor object.  The bootstrap program
    // has placed a pointer to the current VM_Processor in a special
    // register.
    if (verboseBoot >= 1) VM.sysWriteln("Setting up current VM_Processor");
    VM_ProcessorLocalState.boot();

    // Finish thread initialization that couldn't be done in boot image.
    // The "stackLimit" must be set before any interruptible methods are called
    // because it's accessed by compiler-generated stack overflow checks.
    //
    if (verboseBoot >= 1) VM.sysWriteln("Doing thread initialization");
    VM_Thread currentThread = VM_Processor.getCurrentProcessor().activeThread;
    currentThread.stackLimit = VM_Magic.objectAsLocalAddress(
        currentThread.getStack()).plus(ArchitectureSpecific.VM_StackframeLayoutConstants.STACK_SIZE_GUARD);

    VM_Processor.getCurrentProcessor().activeThreadStackLimit = currentThread.stackLimit;

    finishBooting();
  }

  /**
   * Complete the task of booting Jikes RVM.
   * Done in a secondary method mainly because this code
   * doesn't have to be uninterruptible and this is the cleanest
   * way to make that distinction.
   */
  @NoSubArchCompile
  @Interruptible
  private static void finishBooting() {

    // get pthread_id from OS and store into vm_processor field
    //
    sysCall.sysPthreadSetupSignalHandling();
    VM_Processor.getCurrentProcessor().pthread_id = sysCall.sysPthreadSelf();

    // Set up buffer locks used by VM_Thread for logging and status dumping.
    //    This can happen at any point before we start running
    //    multi-threaded.
    VM_Services.boot();

    // Initialize memory manager.
    //    This must happen before any uses of "new".
    //
    if (verboseBoot >= 1) {
      VM.sysWriteln("Setting up memory manager: bootrecord = ",
                    VM_Magic.objectAsAddress(VM_BootRecord.the_boot_record));
    }
    MM_Interface.boot(VM_BootRecord.the_boot_record);

    // Reset the options for the baseline compiler to avoid carrying
    // them over from bootimage writing time.
    //
    if (verboseBoot >= 1) VM.sysWriteln("Initializing baseline compiler options to defaults");
    VM_BaselineCompiler.initOptions();

    // Fetch arguments from program command line.
    //
    if (verboseBoot >= 1) VM.sysWriteln("Fetching command-line arguments");
    VM_CommandLineArgs.fetchCommandLineArguments();

    // Process most virtual machine command line arguments.
    //
    if (verboseBoot >= 1) VM.sysWriteln("Early stage processing of command line");
    VM_CommandLineArgs.earlyProcessCommandLineArguments();

    // Allow Memory Manager to respond to its command line arguments
    //
    if (verboseBoot >= 1) VM.sysWriteln("Collector processing rest of boot options");
    MM_Interface.postBoot();

    // Initialize class loader.
    //
    if (verboseBoot >= 1) VM.sysWriteln("Initializing bootstrap class loader");
    String bootstrapClasses = VM_CommandLineArgs.getBootstrapClasses();
    VM_ClassLoader.boot();      // Wipe out cached application class loader
    VM_BootstrapClassLoader.boot(bootstrapClasses);

    // Initialize statics that couldn't be placed in bootimage, either
    // because they refer to external state (open files), or because they
    // appear in fields that are unique to Jikes RVM implementation of
    // standard class library (not part of standard jdk).
    // We discover the latter by observing "host has no field" and
    // "object not part of bootimage" messages printed out by bootimage
    // writer.
    //
    if (verboseBoot >= 1) VM.sysWriteln("Running various class initializers");
    runClassInitializer("gnu.classpath.SystemProperties");

    runClassInitializer("java.lang.Runtime");
    runClassInitializer("java.lang.System");
    runClassInitializer("sun.misc.Unsafe");

    runClassInitializer("java.lang.Character");
    runClassInitializer("java.util.WeakHashMap"); // Need for ThreadLocal
    // Turn off security checks; about to hit EncodingManager.
    // Commented out because we haven't incorporated this into the CVS head
    // yet.
    // java.security.JikesRVMSupport.turnOffChecks();
    runClassInitializer("java.lang.ThreadGroup");
    runClassInitializer("java.lang.Thread");

    /* We can safely allocate a java.lang.Thread now.  The boot
       thread (running right now, as a VM_Thread) has to become a full-fledged
       Thread, since we're about to encounter a security check:

       EncodingManager checks a system property,
        which means that the permissions checks have to be working,
        which means that VMAccessController will be invoked,
        which means that ThreadLocal.get() will be called,
        which calls Thread.getCurrentThread().

        So the boot VM_Thread needs to be associated with a real Thread for
        Thread.getCurrentThread() to return. */
    VM.safeToAllocateJavaThread = true;

    runClassInitializer("java.lang.ThreadLocal");
    runClassInitializer("java.lang.ThreadLocalMap");
    // Possibly fix VMAccessController's contexts and inGetContext fields
    runClassInitializer("java.security.VMAccessController");

    runClassInitializer("java.io.File"); // needed for when we initialize the
    // system/application class loader.
    runClassInitializer("java.lang.String");
    runClassInitializer("java.lang.VMString");
    runClassInitializer("gnu.java.security.provider.DefaultPolicy");
    runClassInitializer("java.net.URL"); // needed for URLClassLoader
    /* Needed for VM_ApplicationClassLoader, which in turn is needed by
       VMClassLoader.getSystemClassLoader()  */
    runClassInitializer("java.net.URLClassLoader");

    /* Used if we start up Jikes RVM with the -jar argument; that argument
* means that we need a working -jar before we can return an
* Application Class Loader. */
    runClassInitializer("java.net.URLConnection");
    runClassInitializer("gnu.java.net.protocol.jar.Connection$JarFileCache");

    runClassInitializer("java.lang.ClassLoader$StaticData");

    runClassInitializer("java.lang.Class$StaticData");

    runClassInitializer("java.nio.charset.CharsetEncoder");
    runClassInitializer("java.nio.charset.CoderResult");

    runClassInitializer("java.io.PrintWriter"); // Uses System.getProperty
    runClassInitializer("java.io.PrintStream"); // Uses System.getProperty
    runClassInitializer("java.util.SimpleTimeZone");
    runClassInitializer("java.util.Locale");
    runClassInitializer("java.util.Calendar");
    runClassInitializer("java.util.GregorianCalendar");
    runClassInitializer("java.util.ResourceBundle");
    runClassInitializer("java.util.zip.Inflater");
    runClassInitializer("java.util.zip.DeflaterHuffman");
    runClassInitializer("java.util.zip.InflaterDynHeader");
    runClassInitializer("java.util.zip.InflaterHuffmanTree");
    runClassInitializer("java.util.Date");
    runClassInitializer("java.lang.Throwable$StaticData");
    if (VM.BuildWithAllClasses) {
      runClassInitializer("java.util.jar.Attributes$Name");
    }

    if (verboseBoot >= 1) VM.sysWriteln("Booting VM_Lock");
    VM_Lock.boot();

    // Enable multiprocessing.
    // Among other things, after this returns, GC and dynamic class loading are enabled.
    //
    if (verboseBoot >= 1) VM.sysWriteln("Booting scheduler");
    VM_Scheduler.boot();
    VM_DynamicLibrary.boot();

    if (verboseBoot >= 1) VM.sysWriteln("Setting up boot thread");
    VM_Scheduler.getCurrentThread().setupBootThread();

    // Create JNI Environment for boot thread.
    // After this point the boot thread can invoke native methods.
    org.jikesrvm.jni.VM_JNIEnvironment.boot();
    if (verboseBoot >= 1) VM.sysWriteln("Initializing JNI for boot thread");
    VM_Scheduler.getCurrentThread().initializeJNIEnv();

    // Run class intializers that require JNI
    if (verboseBoot >= 1) VM.sysWriteln("Running late class initializers");
    System.loadLibrary("javaio");
    runClassInitializer("java.lang.Math");
    runClassInitializer("gnu.java.nio.VMChannel");
    runClassInitializer("gnu.java.nio.FileChannelImpl");

    runClassInitializer("java.io.FileDescriptor");
    runClassInitializer("java.util.jar.JarFile");

    runClassInitializer("java.lang.VMDouble");
    runClassInitializer("java.util.PropertyPermission");
    runClassInitializer("org.jikesrvm.scheduler.greenthreads.VM_Process");
    runClassInitializer("org.jikesrvm.classloader.VM_Annotation");
    runClassInitializer("java.lang.VMClassLoader");

    if (VM.HybridRVM) {
      runClassInitializer("org.jikesrvm.SubordinateArchitecture$VM_RuntimeMethods");
      VM_GreenScheduler.bootSubArch();
    }
    
    // Initialize java.lang.System.out, java.lang.System.err, java.lang.System.in
    VM_FileSystem.initializeStandardStreams();
    	
    ///////////////////////////////////////////////////////////////
    // The VM is now fully booted.                               //
    // By this we mean that we can execute arbitrary Java code.  //
    ///////////////////////////////////////////////////////////////
    if (verboseBoot >= 1) VM.sysWriteln("VM is now fully booted");
    
    // Inform interested subsystems that VM is fully booted.
    VM.fullyBooted = true;
    MM_Interface.fullyBootedVM();
    VM_BaselineCompiler.fullyBootedVM();    
    
    // Initialize compiler that compiles dynamically loaded classes.
    //
    if (verboseBoot >= 1) VM.sysWriteln("Initializing runtime compiler");
    VM_RuntimeCompiler.boot();

    // Process remainder of the VM's command line arguments.
    if (verboseBoot >= 1) VM.sysWriteln("Late stage processing of command line");
    String[] applicationArguments = VM_CommandLineArgs.lateProcessCommandLineArguments();

    if (VM.verboseClassLoading || verboseBoot >= 1) VM.sysWrite("[VM booted]\n");

    // set up JikesRVM socket I/O
    if (verboseBoot >= 1) VM.sysWriteln("Initializing socket factories");
    JikesRVMSocketImpl.boot();

    if (VM.BuildForAdaptiveSystem) {
      if (verboseBoot >= 1) VM.sysWriteln("Initializing adaptive system");
      VM_Controller.boot();
    }

    // The first argument must be a class name.
    if (verboseBoot >= 1) VM.sysWriteln("Extracting name of class to execute");
    if (applicationArguments.length == 0) {
      pleaseSpecifyAClass();
    }
    if (applicationArguments.length > 0 && !VM_TypeDescriptorParsing.isJavaClassName(applicationArguments[0])) {
      VM.sysWrite("vm: \"");
      VM.sysWrite(applicationArguments[0]);
      VM.sysWrite("\" is not a legal Java class name.\n");
      pleaseSpecifyAClass();
    }

    if (verboseBoot >= 1) VM.sysWriteln("Initializing Application Class Loader");
    VM_ClassLoader.getApplicationClassLoader();
    VM_ClassLoader.declareApplicationClassLoaderIsReady();

    if (verboseBoot >= 1) {
      VM.sysWriteln("Turning back on security checks.  Letting people see the VM_ApplicationClassLoader.");
    }
    // Turn on security checks again.
    // Commented out because we haven't incorporated this into the main CVS
    // tree yet.
    // java.security.JikesRVMSupport.fullyBootedVM();

    runClassInitializer("java.lang.ClassLoader$StaticData");

    // Allow profile information to be read in from a file
    //
    VM_EdgeCounts.boot(EdgeCounterFile);

    if (VM.BuildForAdaptiveSystem) {
      VM_CompilerAdvice.postBoot();
    }

    // enable alignment checking
    if (VM.AlignmentChecking) {
      VM_SysCall.sysCall.sysEnableAlignmentChecking();
    }
    
    // Schedule "main" thread for execution.
    if (verboseBoot >= 2) VM.sysWriteln("Creating main thread");
    // Create main thread.
    if (verboseBoot >= 1) VM.sysWriteln("Constructing mainThread");
    mainThread = new VM_MainThread(applicationArguments);

    // Schedule "main" thread for execution.
    if (verboseBoot >= 1) VM.sysWriteln("Starting main thread");
    mainThread.start();

    if (verboseBoot >= 1) VM.sysWriteln("Starting debugger thread");
    VM_Scheduler.startDebuggerThread();

    // End of boot thread.
    //
    if (VM.TraceThreads) VM_GreenScheduler.trace("VM.boot", "completed - terminating");
    if (verboseBoot >= 2) {
      VM.sysWriteln("Boot sequence completed; finishing boot thread");
    }

    VM_Scheduler.getCurrentThread().terminate();
    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

  @NoSubArchCompile
  @Interruptible
  private static void pleaseSpecifyAClass() {
    VM.sysWrite("vm: Please specify a class to execute.\n");
    VM.sysWrite("vm:   You can invoke the VM with the \"-help\" flag for usage information.\n");
    VM.sysExit(VM.EXIT_STATUS_BOGUS_COMMAND_LINE_ARG);
  }

  /**
   * Run <clinit> method of specified class, if that class appears
   * in bootimage and actually has a clinit method (we are flexible to
   * allow one list of classes to work with different bootimages and
   * different version of classpath (eg 0.05 vs. cvs head).
   *
   * This method is called only while the VM boots.
   *
   * @param className
   */
  @NoSubArchCompile
  @Interruptible
  static void runClassInitializer(String className) {
    if (verboseBoot >= 2) {
      sysWrite("running class intializer for ");
      sysWriteln(className);
    }
    VM_Atom classDescriptor = VM_Atom.findOrCreateAsciiAtom(className.replace('.', '/')).descriptorFromClassName();
    VM_TypeReference tRef =
        VM_TypeReference.findOrCreate(VM_BootstrapClassLoader.getBootstrapClassLoader(), classDescriptor);
    VM_Class cls = (VM_Class) tRef.peekType();
    if (null == cls) {
      sysWrite("Failed to run class intializer for ");
      sysWrite(className);
      sysWriteln(" as the class does not exist.");
    } else if (!cls.isInBootImage()) {
      sysWrite("Failed to run class intializer for ");
      sysWrite(className);
      sysWriteln(" as the class is not in the boot image.");
    } else {
      VM_Method clinit = cls.getClassInitializerMethod();
      if (clinit != null) {
        clinit.compile(false);
        if (verboseBoot >= 10) VM.sysWriteln("invoking method " + clinit);
        try {
          VM_Magic.invokeClassInitializer((ArchitectureSpecific.VM_CodeArray) clinit.getCurrentEntryCodeArray(false));
        } catch (Error e) {
          throw e;
        } catch (Throwable t) {
          ExceptionInInitializerError eieio =
              new ExceptionInInitializerError("Caught exception while invoking the class initializer for " + className);
          eieio.initCause(t);
          throw eieio;
        }
        // <clinit> is no longer needed: reclaim space by removing references to it
        clinit.invalidateCompiledMethod(clinit.getCurrentCompiledMethod(false), false);
      } else {
        if (verboseBoot >= 10) VM.sysWriteln("has no clinit method ");
      }
      cls.setAllFinalStaticJTOCEntries(false);
    }
  }

  //----------------------------------------------------------------------//
  //                         Execution environment.                       //
  //----------------------------------------------------------------------//

  /**
   * Verify a runtime assertion (die w/traceback if assertion fails).
   * Note: code your assertion checks as
   * "if (VM.VerifyAssertions) VM._assert(xxx);"
   * @param b the assertion to verify
   */
  @Inline
  public static void _assert(boolean b) {
    _assert(b, null, null);
  }

  /**
   * Verify a runtime assertion (die w/message and traceback if
   * assertion fails).   Note: code your assertion checks as
   * "if (VM.VerifyAssertions) VM._assert(xxx,yyy);"
   * @param b the assertion to verify
   * @param message the message to print if the assertion is false
   */
  @Inline
  public static void _assert(boolean b, String message) {
    _assert(b, message, null);
  }

  @Inline
  public static void _assert(boolean b, String msg1, String msg2) {
    if (!VM.VerifyAssertions) {
      sysWriteln("vm: somebody forgot to conditionalize their call to assert with");
      sysWriteln("vm: if (VM.VerifyAssertions)");
      _assertionFailure("vm internal error: assert called when !VM.VerifyAssertions", null);
    }
    if (!b) _assertionFailure(msg1, msg2);
  }

  @NoInline
  @UninterruptibleNoWarn
  private static void _assertionFailure(String msg1, String msg2) {
    if (msg1 == null && msg2 == null) {
      msg1 = "vm internal error at:";
    }
    if (msg2 == null) {
      msg2 = msg1;
      msg1 = null;
    }
    if (VM.runningVM) {
      if (msg1 != null) {
        sysWrite(msg1);
      }
      sysFail(msg2);
    }
    throw new RuntimeException((msg1 != null ? msg1 : "") + msg2);
  }

  /**
   * Format a 32 bit number as "0x" followed by 8 hex digits.
   * Do this without referencing Integer or Character classes,
   * in order to avoid dynamic linking.
   * TODO: move this method to VM_Services.
   * @param number
   * @return a String with the hex representation of the integer
   */
  @NoSubArchCompile
  @Interruptible
  public static String intAsHexString(int number) {
    char[] buf = new char[10];
    int index = 10;
    while (--index > 1) {
      int digit = number & 0x0000000f;
      buf[index] = digit <= 9 ? (char) ('0' + digit) : (char) ('a' + digit - 10);
      number >>= 4;
    }
    buf[index--] = 'x';
    buf[index] = '0';
    return new String(buf);
  }

  /**
   * Format a 64 bit number as "0x" followed by 16 hex digits.
   * Do this without referencing Long or Character classes,
   * in order to avoid dynamic linking.
   * TODO: move this method to VM_Services.
   * @param number
   * @return a String with the hex representation of the long
   */
  @NoSubArchCompile
  @Interruptible
  public static String longAsHexString(long number) {
    char[] buf = new char[18];
    int index = 18;
    while (--index > 1) {
      int digit = (int) (number & 0x000000000000000fL);
      buf[index] = digit <= 9 ? (char) ('0' + digit) : (char) ('a' + digit - 10);
      number >>= 4;
    }
    buf[index--] = 'x';
    buf[index] = '0';
    return new String(buf);
  }

  /**
   * Format a 32/64 bit number as "0x" followed by 8/16 hex digits.
   * Do this without referencing Integer or Character classes,
   * in order to avoid dynamic linking.
   * TODO: move this method to VM_Services.
   * @param addr  The 32/64 bit number to format.
   * @return a String with the hex representation of an Address
   */
  @NoSubArchCompile
  @Interruptible
  public static String addressAsHexString(Address addr) {
    int len = 2 + (BITS_IN_ADDRESS >> 2);
    char[] buf = new char[len];
    while (--len > 1) {
      int digit = addr.toInt() & 0x0F;
      buf[len] = digit <= 9 ? (char) ('0' + digit) : (char) ('a' + digit - 10);
      addr = addr.toWord().rshl(4).toAddress();
    }
    buf[len--] = 'x';
    buf[len] = '0';
    return new String(buf);
  }

  @SuppressWarnings({"unused", "CanBeFinal", "UnusedDeclaration"})
  // accessed via VM_EntryPoints
  @Entrypoint
  private static int sysWriteLock = 0;
  private static Offset sysWriteLockOffset = Offset.max();

  private static void swLock() {
    // FIXME - if (sysWriteLockOffset.isMax()) return;
  	if (!runningVM) return;  // remove
    while (!VM_Synchronization.testAndSet(VM_Magic.getJTOC(), sysWriteLockOffset, 1)) {
      ;
    }
  }

  private static void swUnlock() {
    // FIXME - if (sysWriteLockOffset.isMax()) return;
  	if (!runningVM) return;  // remove
    VM_Synchronization.fetchAndStore(VM_Magic.getJTOC(), sysWriteLockOffset, 0);
  }

  /**
   * Low level print to console.
   * @param value  what is printed
   */
  @NoSubArchCompile
  @NoInline
  /* don't waste code space inlining these --dave */
  private static void write(VM_Atom value) {
    value.sysWrite();
  }

  /**
   * Low level print to console.
   * @param value  what is printed
   */
  @NoSubArchCompile
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(VM_Member value) {
    write(value.getMemberRef());
  }

  /**
   * Low level print to console.
   * @param value  what is printed
   */
  @NoSubArchCompile
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(VM_MemberReference value) {
    write(value.getType().getName());
    write(".");
    write(value.getName());
    write(" ");
    write(value.getDescriptor());
  }

  /**
   * Low level print to console.
   * @param value   what is printed
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(String value) {
    if (value == null) {
      write("null");
    } else {
      if (runningVM) {
        char[] chars = java.lang.JikesRVMSupport.getBackingCharArray(value);
        int numChars = java.lang.JikesRVMSupport.getStringLength(value);
        int offset = java.lang.JikesRVMSupport.getStringOffset(value);
        for (int i = 0; i < numChars; i++) {
          write(chars[offset + i]);
        }
      } else {
        writeNotRunningVM(value);
      }
    }
  }
  @NoSubArchCompile
  @UninterruptibleNoWarn
  private static void writeNotRunningVM(String value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(value);
  }

  /**
   * Low level print to console.
   * @param value character array that is printed
   * @param len number of characters printed
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(char[] value, int len) {
    for (int i = 0, n = len; i < n; ++i) {
      if (runningVM)
        /*  Avoid triggering a potential read barrier
         *
         *  TODO: Convert this to use org.mmtk.vm.Barriers.getArrayNoBarrier
         */ {
        // FIXME - write(VM_Magic.getCharAtOffset(value, Offset.fromIntZeroExtend(i << LOG_BYTES_IN_CHAR)));
      	write(value[i]);
      } else {
        write(value[i]);
      }
    }
  }

  /**
   * Low level print of a <code>char</code>to console.
   * @param value       The character to print
   */
  @Uninterruptible
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(char value) {
    if (runningVM) {
    	if (VM_Magic.runningOnSubArch()) {
    		SubordinateArchitecture.VM_RuntimeMethods.write(value);
    	} else {
    		sysCall.sysConsoleWriteChar(value);
    	}
    } else {
      writeNotRunningVM(value);
    }
  }

  @NoSubArchCompile
  @UninterruptibleNoWarn
  private static void writeNotRunningVM(char value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(value);
  }

  /**
   * Low level print of <code>double</code> to console.
   *
   * @param value               <code>double</code> to be printed
   * @param postDecimalDigits   Number of decimal places
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(double value, int postDecimalDigits) {
    if (runningVM) {
    	if (VM_Magic.runningOnSubArch()) {
    		SubordinateArchitecture.VM_RuntimeMethods.writeDouble(value, postDecimalDigits);
    	} else {
    		sysCall.sysConsoleWriteDouble(value, postDecimalDigits);
    	}
    } else {
      writeNotRunningVM(value);
    }
  }
  @NoSubArchCompile
  @UninterruptibleNoWarn
  private static void writeNotRunningVM(double value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(value);
  }

  /**
   * Low level print of an <code>int</code> to console.
   * @param value       what is printed
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(int value) {
    if (runningVM) {
      int mode = (value < -(1 << 20) || value > (1 << 20)) ? 2 : 0; // hex only or decimal only
    	if (VM_Magic.runningOnSubArch()) {
    		SubordinateArchitecture.VM_RuntimeMethods.write(value, mode);
    	} else {
    		sysCall.sysConsoleWriteInteger(value, mode);
    	}
    } else {
      writeNotRunningVM(value);
    }
  }
  @NoSubArchCompile
  @UninterruptibleNoWarn
  private static void writeNotRunningVM(int value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(value);
  }

  /**
   * Low level print to console.
   * @param value       What is printed, as hex only
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(int value) {
    if (runningVM) {
    	if (VM_Magic.runningOnSubArch()) {
    		SubordinateArchitecture.VM_RuntimeMethods.write(value, 2 /*just hex*/);
    	} else {
    		sysCall.sysConsoleWriteInteger(value, 2 /*just hex*/);
    	}
    } else {
      writeHexNotRunningVM(value);
    }
  }
  @NoSubArchCompile
  @UninterruptibleNoWarn
  private static void writeHexNotRunningVM(int value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(Integer.toHexString(value));
  }

  /**
   * Low level print to console.
   * @param value       what is printed, as hex only
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(long value) {
    if (runningVM) {
    	if (VM_Magic.runningOnSubArch()) {
    		SubordinateArchitecture.VM_RuntimeMethods.write(value, 2);
    	} else {   	
    		sysCall.sysConsoleWriteLong(value, 2);
    	}
    } else {
      writeHexNotRunningVM(value);
    }
  }

  @UninterruptibleNoWarn
  private static void writeHexNotRunningVM(long value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(Long.toHexString(value));
  }

  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeDec(Word value) {
    if (VM.BuildFor32Addr) {
      write(value.toInt());
    } else {
      write(value.toLong());
    }
  }

  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(Word value) {
    if (VM.BuildFor32Addr) {
      writeHex(value.toInt());
    } else {
      writeHex(value.toLong());
    }
  }

  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(Address value) {
    writeHex(value.toWord());
  }

  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(LocalAddress value) {
    writeHex(value.toWord());
  }
  
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(ObjectReference value) {
    writeHex(value.toAddress().toWord());
  }

  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(Extent value) {
    writeHex(value.toWord());
  }

  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(Offset value) {
    writeHex(value.toWord());
  }

  /**
   * Low level print to console.
   * @param value       what is printed, as int only
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeInt(int value) {
    if (runningVM) {
    	if (VM_Magic.runningOnSubArch()) {
    		SubordinateArchitecture.VM_RuntimeMethods.write(value, 0 /*just decimal*/);
    	} else {
    		sysCall.sysConsoleWriteInteger(value, 0 /*just decimal*/);
    	}
    } else {
      writeNotRunningVM(value);
    }
  }
  @NoSubArchCompile
  @UninterruptibleNoWarn
  private static void writeNotRunningVM(long value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(value);
  }

  /**
   * Low level print to console.
   * @param value   what is printed
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(long value) {
    write(value, true);
  }

  /**
   * Low level print to console.
   * @param value   what is printed
   * @param hexToo  how to print: true  - print as decimal followed by hex
   *                              false - print as decimal only
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(long value, boolean hexToo) {
    if (runningVM) {
    	if (VM_Magic.runningOnSubArch()) {
    		SubordinateArchitecture.VM_RuntimeMethods.write(value, hexToo ? 1 : 0);
    	} else {
    		sysCall.sysConsoleWriteLong(value, hexToo ? 1 : 0);
    	}
    } else {
      writeNotRunningVM(value);
    }
  }

  @NoSubArchCompile
  @LogicallyUninterruptible
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeField(int fieldWidth, String s) {
    write(s);
    int len = s.length();
    while (fieldWidth > len++) write(" ");
  }

  /**
   * Low level print to console.
   * @param value       print value and left-fill with enough spaces to print at least fieldWidth characters
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeField(int fieldWidth, int value) {
    int len = 1, temp = value;
    if (temp < 0) {
      len++;
      temp = -temp;
    }
    while (temp >= 10) {
      len++;
      temp /= 10;
    }
    while (fieldWidth > len++) write(" ");
    if (runningVM) {
    	if (VM_Magic.runningOnSubArch()) {
    		SubordinateArchitecture.VM_RuntimeMethods.write(value, 0);
    	} else {
    		sysCall.sysConsoleWriteInteger(value, 0);
    	}
    } else {
      writeNotRunningVM(value);
    }
  }

  /**
   * Low level print of the {@link VM_Atom} <code>s</code> to the console.
   * Left-fill with enough spaces to print at least <code>fieldWidth</code>
   * characters
   * @param fieldWidth  Minimum width to print.
   * @param s       The {@link VM_Atom} to print.
   */
  @NoSubArchCompile
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeField(int fieldWidth, VM_Atom s) {
    int len = s.length();
    while (fieldWidth > len++) write(" ");
    write(s);
  }

  public static void writeln() {
    write('\n');
  }

  public static void write(double d) {
    write(d, 2);
  }

  public static void write(Word addr) {
    writeHex(addr);
  }

  public static void write(LocalAddress addr) {
    writeHex(addr);
  }
  
  public static void write(Address addr) {
    writeHex(addr);
  }

  public static void write(ObjectReference object) {
    writeHex(object);
  }

  public static void write(Offset addr) {
    writeHex(addr);
  }

  public static void write(Extent addr) {
    writeHex(addr);
  }
  
  public static void write(boolean b) {
    write(b ? "true" : "false");
  }

  /**
   * A group of multi-argument sysWrites with optional newline.  Externally visible methods.
   */
  @NoInline
  public static void sysWrite(VM_Atom a) {
    swLock();
    write(a);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(VM_Atom a) {
    swLock();
    write(a);
    write("\n");
    swUnlock();
  }

  @NoInline
  public static void sysWrite(VM_Member m) {
    swLock();
    write(m);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(VM_MemberReference mr) {
    swLock();
    write(mr);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln() {
    swLock();
    write("\n");
    swUnlock();
  }

  @NoInline
  public static void sysWrite(char c) { write(c); }

  @NoInline
  public static void sysWriteField(int w, int v) {
    swLock();
    writeField(w, v);
    swUnlock();
  }

  @NoInline
  public static void sysWriteField(int w, String s) {
    swLock();
    writeField(w, s);
    swUnlock();
  }

  @NoInline
  public static void sysWriteHex(int v) {
    swLock();
    writeHex(v);
    swUnlock();
  }

  @NoInline
  public static void sysWriteHex(long v) {
    swLock();
    writeHex(v);
    swUnlock();
  }

  @NoInline
  public static void sysWriteHex(Address v) {
    swLock();
    writeHex(v);
    swUnlock();
  }

  @NoInline
  public static void sysWriteInt(int v) {
    swLock();
    writeInt(v);
    swUnlock();
  }

  @NoInline
  public static void sysWriteLong(long v) {
    swLock();
    write(v, false);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(double d, int p) {
    swLock();
    write(d, p);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(double d) {
    swLock();
    write(d);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s) {
    swLock();
    write(s);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(char[] c, int l) {
    swLock();
    write(c, l);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(Address a) {
    swLock();
    write(a);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(LocalAddress a) {
    swLock();
    write(a);
    swUnlock();
  }
  
  @NoInline
  public static void sysWriteln(Address a) {
    swLock();
    write(a);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(ObjectReference o) {
    swLock();
    write(o);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(ObjectReference o) {
    swLock();
    write(o);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(Offset o) {
    swLock();
    write(o);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(Offset o) {
    swLock();
    write(o);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(Word w) {
    swLock();
    write(w);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(Word w) {
    swLock();
    write(w);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(Extent e) {
    swLock();
    write(e);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(Extent e) {
    swLock();
    write(e);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(boolean b) {
    swLock();
    write(b);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(int i) {
    swLock();
    write(i);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(int i) {
    swLock();
    write(i);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(double d) {
    swLock();
    write(d);
    writeln();
    swUnlock();
  }
  
  @NoInline
  public static void sysWriteln(long l) {
    swLock();
    write(l);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(boolean b) {
    swLock();
    write(b);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s) {
    swLock();
    write(s);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, int i) {
    swLock();
    write(s);
    write(i);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, int i) {
    swLock();
    write(s);
    write(i);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, boolean b) {
    swLock();
    write(s);
    write(b);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, boolean b) {
    swLock();
    write(s);
    write(b);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, double d) {
    swLock();
    write(s);
    write(d);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, double d) {
    swLock();
    write(s);
    write(d);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(double d, String s) {
    swLock();
    write(d);
    write(s);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(double d, String s) {
    swLock();
    write(d);
    write(s);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, long i) {
    swLock();
    write(s);
    write(i);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, long i) {
    swLock();
    write(s);
    write(i);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(int i, String s) {
    swLock();
    write(i);
    write(s);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(int i, String s) {
    swLock();
    write(i);
    write(s);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2) {
    swLock();
    write(s1);
    write(s2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2) {
    swLock();
    write(s1);
    write(s2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, Address a) {
    swLock();
    write(s);
    write(a);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, LocalAddress a) {
    swLock();
    write(s);
    write(a);
    swUnlock();
  }
  
  @NoInline
  public static void sysWriteln(String s, Address a) {
    swLock();
    write(s);
    write(a);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, LocalAddress a) {
    swLock();
    write(s);
    write(a);
    writeln();
    swUnlock();
  }
  
  @NoInline
  public static void sysWrite(String s, ObjectReference r) {
    swLock();
    write(s);
    write(r);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, ObjectReference r) {
    swLock();
    write(s);
    write(r);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, Offset o) {
    swLock();
    write(s);
    write(o);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, Offset o) {
    swLock();
    write(s);
    write(o);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, Word w) {
    swLock();
    write(s);
    write(w);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, Word w) {
    swLock();
    write(s);
    write(w);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, Address a) {
    swLock();
    write(s1);
    write(s2);
    write(a);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, Address a) {
    swLock();
    write(s1);
    write(s2);
    write(a);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, LocalAddress a) {
    swLock();
    write(s1);
    write(s2);
    write(a);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, LocalAddress a) {
    swLock();
    write(s1);
    write(s2);
    write(a);
    writeln();
    swUnlock();
  }
  @NoInline
  public static void sysWrite(String s1, String s2, int i) {
    swLock();
    write(s1);
    write(s2);
    write(i);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, int i) {
    swLock();
    write(s1);
    write(s2);
    write(i);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, int i, String s2) {
    swLock();
    write(s1);
    write(i);
    write(s2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, int i, String s2) {
    swLock();
    write(s1);
    write(i);
    write(s2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, Offset o, String s2) {
    swLock();
    write(s1);
    write(o);
    write(s2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, Offset o, String s2) {
    swLock();
    write(s1);
    write(o);
    write(s2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, String s3) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, String s3) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(int i1, String s, int i2) {
    swLock();
    write(i1);
    write(s);
    write(i2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(int i1, String s, int i2) {
    swLock();
    write(i1);
    write(s);
    write(i2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(int i1, String s1, String s2) {
    swLock();
    write(i1);
    write(s1);
    write(s2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(int i1, String s1, String s2) {
    swLock();
    write(i1);
    write(s1);
    write(s2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, String s3, String s4) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, String s3, String s4) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, String s3, String s4, String s5) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    write(s5);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, String s3, String s4, String s5) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    write(s5);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, int i1, String s2, int i2) {
    swLock();
    write(s1);
    write(i1);
    write(s2);
    write(i2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, int i1, String s2, int i2) {
    swLock();
    write(s1);
    write(i1);
    write(s2);
    write(i2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, int i1, String s2, long l1) {
    swLock();
    write(s1);
    write(i1);
    write(s2);
    write(l1);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, int i1, String s2, long l1) {
    swLock();
    write(s1);
    write(i1);
    write(s2);
    write(l1);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, Offset o, String s2, int i) {
    swLock();
    write(s1);
    write(o);
    write(s2);
    write(i);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, Offset o, String s2, int i) {
    swLock();
    write(s1);
    write(o);
    write(s2);
    write(i);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, double d, String s2) {
    swLock();
    write(s1);
    write(d);
    write(s2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, double d, String s2) {
    swLock();
    write(s1);
    write(d);
    write(s2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, int i1, String s3) {
    swLock();
    write(s1);
    write(s2);
    write(i1);
    write(s3);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, int i1, String s3) {
    swLock();
    write(s1);
    write(s2);
    write(i1);
    write(s3);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, String s3, int i1) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(i1);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, String s3, int i1) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(i1);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, String s3, String s4, int i5, String s6) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    write(i5);
    write(s6);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, String s3, String s4, int i5, String s6) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    write(i5);
    write(s6);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(int i, String s1, double d, String s2) {
    swLock();
    write(i);
    write(s1);
    write(d);
    write(s2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(int i, String s1, double d, String s2) {
    swLock();
    write(i);
    write(s1);
    write(d);
    write(s2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, String s3, int i1, String s4) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(i1);
    write(s4);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, String s3, int i1, String s4) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(i1);
    write(s4);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, Address a1, String s2, Address a2) {
    swLock();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, Address a1, String s2, Address a2) {
    swLock();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, Address a, String s2, int i) {
    swLock();
    write(s1);
    write(a);
    write(s2);
    write(i);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, Address a, String s2, int i) {
    swLock();
    write(s1);
    write(a);
    write(s2);
    write(i);
    writeln();
    swUnlock();
  }


  @NoInline
  public static void sysWrite(String s1, LocalAddress a1, String s2, LocalAddress a2) {
    swLock();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, LocalAddress a1, String s2, LocalAddress a2) {
    swLock();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, LocalAddress a, String s2, int i) {
    swLock();
    write(s1);
    write(a);
    write(s2);
    write(i);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, LocalAddress a, String s2, int i) {
    swLock();
    write(s1);
    write(a);
    write(s2);
    write(i);
    writeln();
    swUnlock();
  }
  
  @NoSubArchCompile
  private static void showProc() {
    VM_Processor p = VM_Processor.getCurrentProcessor();
    write("Proc ");
    write(p.id);
    write(": ");
  }

  @NoSubArchCompile
  private static void showThread() {
    write("Thread ");
    write(VM_Scheduler.getCurrentThread().getIndex());
    write(": ");
  }

  @NoSubArchCompile
  @NoInline
  public static void ptsysWriteln(String s) {
    swLock();
    showProc();
    showThread();
    write(s);
    writeln();
    swUnlock();
  }

  @NoSubArchCompile
  @NoInline
  public static void ptsysWriteln(String s1, String s2, String s3, int i4, String s5, String s6) {
    swLock();
    showProc();
    showThread();
    write(s1);
    write(s2);
    write(s3);
    write(i4);
    write(s5);
    write(s6);
    writeln();
    swUnlock();
  }

  @NoSubArchCompile
  @NoInline
  public static void ptsysWriteln(String s1, String s2, String s3, String s4, String s5, String s6, String s7, int i8,
                                  String s9, String s10, String s11, String s12, String s13) {
    swLock();
    showProc();
    showThread();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    write(s5);
    write(s6);
    write(s7);
    write(i8);
    write(s9);
    write(s10);
    write(s11);
    write(s12);
    write(s13);
    writeln();
    swUnlock();
  }

  @NoSubArchCompile
  @NoInline
  public static void ptsysWriteln(String s1, String s2, String s3, String s4, String s5, String s6, String s7, int i8,
                                  String s9, String s10, String s11, String s12, String s13, int i14) {
    swLock();
    showProc();
    showThread();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    write(s5);
    write(s6);
    write(s7);
    write(i8);
    write(s9);
    write(s10);
    write(s11);
    write(s12);
    write(s13);
    write(i14);
    writeln();
    swUnlock();
  }

  @NoSubArchCompile
  @NoInline
  public static void psysWrite(char[] c, int l) {
    swLock();
    showProc();
    write(c, l);
    swUnlock();
  }

  @NoSubArchCompile
  @NoInline
  public static void psysWriteln(Address a) {
    swLock();
    showProc();
    write(a);
    writeln();
    swUnlock();
  }

  @NoSubArchCompile
  @NoInline
  public static void psysWriteln(String s) {
    swLock();
    showProc();
    write(s);
    writeln();
    swUnlock();
  }

  @NoSubArchCompile
  @NoInline
  public static void psysWriteln(String s, int i) {
    swLock();
    showProc();
    write(s);
    write(i);
    writeln();
    swUnlock();
  }

  @NoSubArchCompile
  @NoInline
  public static void psysWriteln(String s, Address a) {
    swLock();
    showProc();
    write(s);
    write(a);
    writeln();
    swUnlock();
  }

  @NoSubArchCompile
  @NoInline
  public static void psysWriteln(String s1, Address a1, String s2, Address a2) {
    swLock();
    showProc();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    writeln();
    swUnlock();
  }

  @NoSubArchCompile
  @NoInline
  public static void psysWriteln(String s1, Address a1, String s2, Address a2, String s3, Address a3) {
    swLock();
    showProc();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    write(s3);
    write(a3);
    writeln();
    swUnlock();
  }

  @NoSubArchCompile
  @NoInline
  public static void psysWriteln(String s1, Address a1, String s2, Address a2, String s3, Address a3, String s4,
                                 Address a4) {
    swLock();
    showProc();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    write(s3);
    write(a3);
    write(s4);
    write(a4);
    writeln();
    swUnlock();
  }

  @NoSubArchCompile
  @NoInline
  public static void psysWriteln(String s1, Address a1, String s2, Address a2, String s3, Address a3, String s4,
                                 Address a4, String s5, Address a5) {
    swLock();
    showProc();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    write(s3);
    write(a3);
    write(s4);
    write(a4);
    write(s5);
    write(a5);
    writeln();
    swUnlock();
  }

  /**
   * Exit virtual machine due to internal failure of some sort.
   * @param message  error message describing the problem
   */
  @NoSubArchCompile
  @NoInline
  public static void sysFail(String message) {
    handlePossibleRecursiveCallToSysFail(message);

    // print a traceback and die
    if(!VM_Scheduler.getCurrentThread().isGCThread()) {
      VM_GreenScheduler.traceback(message);
    } else {
      VM.sysWriteln("Died in GC:");
      VM_GreenScheduler.traceback(message);
      VM.sysWriteln("Virtual machine state:");
      VM_Scheduler.dumpVirtualMachine();
    }
    if (VM.runningVM) {
      VM.shutdown(EXIT_STATUS_SYSFAIL);
    } else {
      VM.sysExit(EXIT_STATUS_SYSFAIL);
    }
    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

  /**
   * Exit virtual machine due to internal failure of some sort.  This
   * two-argument form is  needed for us to call before the VM's Integer class
   * is initialized.
   *
   * @param message  error message describing the problem
   * @param number  an integer to append to <code>message</code>.
   */
  @NoSubArchCompile
  @NoInline
  public static void sysFail(String message, int number) {
    handlePossibleRecursiveCallToSysFail(message, number);

    // print a traceback and die
    VM_GreenScheduler.traceback(message, number);
    if (VM.runningVM) {
      VM.shutdown(EXIT_STATUS_SYSFAIL);
    } else {
      VM.sysExit(EXIT_STATUS_SYSFAIL);
    }
    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

//   /* This could be made public. */
//   private static boolean alreadyShuttingDown() {
//     return (inSysExit != 0) || (inShutdown != 0);
//   }

  /**
   * Exit virtual machine.
   * @param value  value to pass to host o/s
   */
  @NoSubArchCompile
  @LogicallyUninterruptible /* TODO: This is completely wrong.  This method is very much Interruptible */
  @NoInline
  public static void sysExit(int value) {
    handlePossibleRecursiveCallToSysExit();
    if (VM_Options.stackTraceAtExit) {
      VM.sysWriteln("[Here is the context of the call to VM.sysExit(", value, ")...:");
      VM.disableGC();
      VM_GreenScheduler.dumpStack();
      VM.enableGC();
      VM.sysWriteln("... END context of the call to VM.sysExit]");
    }

    if (runningVM) {
      VM_Scheduler.sysExit();
      VM_Callbacks.notifyExit(value);
      VM.shutdown(value);
    } else {
      System.exit(value);
    }
    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

  /**
   * Shut down the virtual machine.
   * Should only be called if the VM is running.
   * @param value  exit value
   */
  @NoSubArchCompile
  public static void shutdown(int value) {
    handlePossibleRecursiveShutdown();

    if (VM.VerifyAssertions) VM._assert(VM.runningVM);
    if (VM.runningAsSubsystem) {
      // Terminate only the system threads that belong to the VM
      VM_GreenScheduler.processorExit(value);
    } else {
      sysCall.sysExit(value);
    }
    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

  private static int inSysFail = 0;

  @NoSubArchCompile
  public static boolean sysFailInProgress() {
    return inSysFail > 0;
  }

  @NoSubArchCompile
  private static void handlePossibleRecursiveCallToSysFail(String message) {
    handlePossibleRecursiveExit("sysFail", ++inSysFail, message);
  }

  @NoSubArchCompile
  private static void handlePossibleRecursiveCallToSysFail(String message, int number) {
    handlePossibleRecursiveExit("sysFail", ++inSysFail, message, number);
  }

  private static int inSysExit = 0;

  @NoSubArchCompile
  private static void handlePossibleRecursiveCallToSysExit() {
    handlePossibleRecursiveExit("sysExit", ++inSysExit);
  }

  private static int inShutdown = 0;

  /** Used only by VM.shutdown() */
  @NoSubArchCompile
  private static void handlePossibleRecursiveShutdown() {
    handlePossibleRecursiveExit("shutdown", ++inShutdown);
  }

  @NoSubArchCompile
  private static void handlePossibleRecursiveExit(String called, int depth) {
    handlePossibleRecursiveExit(called, depth, null);
  }

  @NoSubArchCompile
  private static void handlePossibleRecursiveExit(String called, int depth, String message) {
    handlePossibleRecursiveExit(called, depth, message, false, -9999999);
  }

  @NoSubArchCompile
  private static void handlePossibleRecursiveExit(String called, int depth, String message, int number) {
    handlePossibleRecursiveExit(called, depth, message, true, number);
  }

  /** @param called Name of the function called: "sysExit", "sysFail", or
   *    "shutdown".
   * @param depth How deep are we in that function?
   * @param message What message did it have?  null means this particular
   *    shutdown function  does not come with a message.
   * @param showNumber Print <code>number</code> following
   *    <code>message</code>?
   * @param number Print this number, if <code>showNumber</code> is true. */
  @NoSubArchCompile
  private static void handlePossibleRecursiveExit(String called, int depth, String message, boolean showNumber,
                                                  int number) {
    /* We adjust up by nProcessorAdjust since we do not want to prematurely
       abort.  Consider the case where VM_Scheduler.numProcessors is greater
       than maxSystemTroubleRecursionDepth.  (This actually happened.)

       Possible change: Instead of adjusting by the # of processors, make the
       "depth" variable a per-processor variable. */
    int nProcessors = VM_GreenScheduler.numProcessors;
    int nProcessorAdjust = nProcessors - 1;
    if (depth > 1 &&
        (depth <=
         maxSystemTroubleRecursionDepth + nProcessorAdjust + VM.maxSystemTroubleRecursionDepthBeforeWeStopVMSysWrite)) {
      if (showNumber) {
        ptsysWriteln("VM.",
                     called,
                     "(): We're in a",
                     depth > nProcessors ? "n (unambiguously)" : " (likely)",
                     " recursive call to VM.",
                     called,
                     "(), ",
                     depth,
                     " deep\n",
                     message == null ? "" : "   ",
                     message == null ? "" : called,
                     message == null ? "" : " was called with the message: ",
                     message == null ? "" : message,
                     number);
      } else {
        ptsysWriteln("VM.",
                     called,
                     "(): We're in a",
                     depth > nProcessors ? "n (unambiguously)" : " (likely)",
                     " recursive call to VM.",
                     called,
                     "(), ",
                     depth,
                     " deep\n",
                     message == null ? "" : "   ",
                     message == null ? "" : called,
                     message == null ? "" : " was called with the message: ",
                     message == null ? "" : message);
      }
    }
    if (depth > maxSystemTroubleRecursionDepth + nProcessorAdjust) {
      dieAbruptlyRecursiveSystemTrouble();
      if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
    }
  }

  /** Have we already called dieAbruptlyRecursiveSystemTrouble()?
   Only for use if we're recursively shutting down!  Used by
   dieAbruptlyRecursiveSystemTrouble() only.  */

  private static boolean inDieAbruptlyRecursiveSystemTrouble = false;

  @NoSubArchCompile
  public static void dieAbruptlyRecursiveSystemTrouble() {
    if (!inDieAbruptlyRecursiveSystemTrouble) {
      inDieAbruptlyRecursiveSystemTrouble = true;
      sysWriteln("VM.dieAbruptlyRecursiveSystemTrouble(): Dying abruptly",
                 "; we're stuck in a recursive shutdown/exit.");
    }
    /* Emergency death. */
    sysCall.sysExit(EXIT_STATUS_RECURSIVELY_SHUTTING_DOWN);
    /* And if THAT fails, go into an inf. loop.  Ugly, but it's better than
       returning from this function and leading to yet more cascading errors.
       and misleading error messages.   (To the best of my knowledge, we have
       never yet reached this point.)  */
    while (true) {
      ;
    }
  }

  //----------------//
  // implementation //
  //----------------//

  /**
   * Create class instances needed for boot image or initialize classes
   * needed by tools.
   * @param bootstrapClasspath places where vm implemention class reside
   * @param bootCompilerArgs command line arguments to pass along to the
   *                         boot compiler's init routine.
   */
  @Interruptible
  @NoSubArchCompile
  private static void init(String bootstrapClasspath, String[] bootCompilerArgs) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);

    // create dummy boot record
    //
    VM_BootRecord.the_boot_record = new VM_BootRecord();

    // initialize type subsystem and classloader
    VM_ClassLoader.init(bootstrapClasspath);

    // initialize remaining subsystems needed for compilation
    //
    VM_OutOfLineMachineCode.init();
    if (writingBootImage) {
      // initialize compiler that builds boot image
      VM_BootImageCompiler.init(bootCompilerArgs);
    }
    VM_Runtime.init();
    VM_Scheduler.init();
    MM_Interface.init();
  }

  @Interruptible
  @NoSubArchCompile
  public static void initSubArchBootRecord() {
    if (VM.HybridRVM) {
    	VM_SubArchStatics.init();
    	SubordinateArchitecture.VM_OutOfLineMachineCode.init();
    	VM_EntrypointHelper.resolveEntryPointsInSubArch ();
    	VM_EntrypointHelper.initEntryPointsInSubArch();
    	
    	// create details for booting of the subordinate architecture
    	subArchBootRecord = new VM_SubArchBootRecord();
    	subArchBootRecord.init();  // This should always happen last
    }
  }

  /**
   * The disableGC() and enableGC() methods are for use as guards to protect
   * code that must deal with raw object addresses in a collection-safe manner
   * (ie. code that holds raw pointers across "gc-sites").
   *
   * Authors of code running while gc is disabled must be certain not to
   * allocate objects explicitly via "new", or implicitly via methods that,
   * in turn, call "new" (such as string concatenation expressions that are
   * translated by the java compiler into String() and StringBuffer()
   * operations). Furthermore, to prevent deadlocks, code running with gc
   * disabled must not lock any objects. This means the code must not execute
   * any bytecodes that require runtime support (eg. via VM_Runtime)
   * such as:
   *   - calling methods or accessing fields of classes that haven't yet
   *     been loaded/resolved/instantiated
   *   - calling synchronized methods
   *   - entering synchronized blocks
   *   - allocating objects with "new"
   *   - throwing exceptions
   *   - executing trap instructions (including stack-growing traps)
   *   - storing into object arrays, except when runtime types of lhs & rhs
   *     match exactly
   *   - typecasting objects, except when runtime types of lhs & rhs
   *     match exactly
   *
   * Recommendation: as a debugging aid, VM_Allocator implementations
   * should test "VM_Thread.disallowAllocationsByThisThread" to verify that
   * they are never called while gc is disabled.
   */
  @Inline
  @Interruptible
  @NoSubArchCompile
  public static void disableGC() {
    disableGC(false);           // Recursion is not allowed in this context.
  }

  /**
   * disableGC: Disable GC if it hasn't already been disabled.  This
   * enforces a stack discipline; we need it for the JNI Get*Critical and
   * Release*Critical functions.  Should be matched with a subsequent call to
   * enableGC().
   */
  @Inline
  @Interruptible
  @NoSubArchCompile
  public static void disableGC(boolean recursiveOK) {
    // current (non-gc) thread is going to be holding raw addresses, therefore we must:
    //
    // 1. make sure we have enough stack space to run until gc is re-enabled
    //    (otherwise we might trigger a stack reallocation)
    //    (We can't resize the stack if there's a native frame, so don't
    //     do it and hope for the best)
    //
    // 2. force all other threads that need gc to wait until this thread
    //    is done with the raw addresses
    //
    // 3. ensure that this thread doesn't try to allocate any objects
    //    (because an allocation attempt might trigger a collection that
    //    would invalidate the addresses we're holding)
    //

    VM_Thread myThread = VM_Scheduler.getCurrentThread();

    // 0. Sanity Check; recursion
    int gcDepth = myThread.getDisableGCDepth();
    if (VM.VerifyAssertions) VM._assert(gcDepth >= 0);
    gcDepth++;
    myThread.setDisableGCDepth(gcDepth);
    if (gcDepth > 1) {
      return;                   // We've already disabled it.
    }

    // 1.
    //
    if (VM_Magic.getFramePointer().minus(ArchitectureSpecific.VM_StackframeLayoutConstants.STACK_SIZE_GCDISABLED)
        .LT(myThread.stackLimit) && !myThread.hasNativeStackFrame()) {
      VM_Thread.resizeCurrentStack(myThread.getStackLength()+
          ArchitectureSpecific.VM_StackframeLayoutConstants.STACK_SIZE_GCDISABLED, null);
    }

    // 2.
    //
    VM_Processor.getCurrentProcessor().disableThreadSwitching("disabling GC");

    // 3.
    //
    if (VM.VerifyAssertions) {
      if (!recursiveOK) {
        VM._assert(!myThread.getDisallowAllocationsByThisThread()); // recursion not allowed
      }
      myThread.setDisallowAllocationsByThisThread();
    }
  }

  /**
   * enable GC; entry point when recursion is not OK.
   */
  @Inline
  @NoSubArchCompile
  public static void enableGC() {
    enableGC(false);            // recursion not OK.
  }

  /**
   * enableGC(): Re-Enable GC if we're popping off the last
   * possibly-recursive {@link #disableGC} request.  This enforces a stack discipline;
   * we need it for the JNI Get*Critical and Release*Critical functions.
   * Should be matched with a preceding call to {@link #disableGC}.
   */
  @Inline
  @NoSubArchCompile
  public static void enableGC(boolean recursiveOK) {
    VM_Thread myThread = VM_Scheduler.getCurrentThread();
    int gcDepth = myThread.getDisableGCDepth();
    if (VM.VerifyAssertions) {
      VM._assert(gcDepth >= 1);
      VM._assert(myThread.getDisallowAllocationsByThisThread());
    }
    gcDepth--;
    myThread.setDisableGCDepth(gcDepth);
    if (gcDepth > 0) {
      return;
    }

    // Now the actual work of re-enabling GC.
    myThread.clearDisallowAllocationsByThisThread();
    VM_Processor.getCurrentProcessor().enableThreadSwitching();
  }
}
