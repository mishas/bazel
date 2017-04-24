// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.android.desugar;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import java.util.HashSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Fixer of classes that extend interfaces with default methods to declare any missing methods
 * explicitly and call the corresponding companion method generated by {@link InterfaceDesugaring}.
 */
public class DefaultMethodClassFixer extends ClassVisitor {

  private final ClassReaderFactory classpath;
  private final ClassReaderFactory bootclasspath;
  private final HashSet<String> instanceMethods = new HashSet<>();
  private final HashSet<String> seenInterfaces = new HashSet<>();

  private boolean isInterface;
  private ImmutableList<String> interfaces;
  private String superName;

  public DefaultMethodClassFixer(ClassVisitor dest, ClassReaderFactory classpath,
      ClassReaderFactory bootclasspath) {
    super(Opcodes.ASM5, dest);
    this.classpath = classpath;
    this.bootclasspath = bootclasspath;
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    checkState(this.interfaces == null);
    isInterface = BitFlags.isSet(access, Opcodes.ACC_INTERFACE);
    checkArgument(superName != null || "java/lang/Object".equals(name), // ASM promises this
        "Type without superclass: %s", name);
    this.interfaces = ImmutableList.copyOf(interfaces);
    this.superName = superName;
    super.visit(version, access, name, signature, superName, interfaces);
  }

  @Override
  public void visitEnd() {
    if (!isInterface && !interfaces.isEmpty()) {
      // Inherited methods take precedence over default methods, so visit all superclasses and
      // figure out what methods they declare before stubbing in any missing default methods.
      recordInheritedMethods();
      stubMissingDefaultMethods(interfaces);
    }
    super.visitEnd();
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String desc, String signature, String[] exceptions) {
    // Keep track of instance methods implemented in this class for later.
    if (!isInterface && !interfaces.isEmpty()) {
      recordIfInstanceMethod(access, name, desc);
    }
    return super.visitMethod(access, name, desc, signature, exceptions);
  }

  private void recordInheritedMethods() {
    InstanceMethodRecorder recorder = new InstanceMethodRecorder();
    String internalName = superName;
    while (internalName != null) {
      ClassReader bytecode = bootclasspath.readIfKnown(internalName);
      if (bytecode == null) {
        bytecode = checkNotNull(classpath.readIfKnown(internalName), "Not found: %s", internalName);
      }
      bytecode.accept(recorder, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
      internalName = bytecode.getSuperName();
    }
  }

  private void recordIfInstanceMethod(int access, String name, String desc) {
    if (BitFlags.noneSet(access, Opcodes.ACC_STATIC)) {
      // Record all declared instance methods, including abstract, bridge, and native methods, as
      // they all take precedence over default methods.
      instanceMethods.add(name + ":" + desc);
    }
  }

  private void stubMissingDefaultMethods(ImmutableList<String> interfaces) {
    for (String implemented : interfaces) {
      if (!seenInterfaces.add(implemented)) {
        // Skip: a superclass already implements this interface, or we've seen it here
        continue;
      }
      ClassReader bytecode = classpath.readIfKnown(implemented);
      if (bytecode != null && !bootclasspath.isKnown(implemented)) {
        // Class in classpath and bootclasspath is a bad idea but in any event, assume the
        // bootclasspath will take precedence like in a classloader.
        // We can skip code attributes as we just need to find default methods to stub.
        bytecode.accept(new DefaultMethodStubber(), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
      }
    }
  }

  /**
   * Visitor for interfaces that produces delegates in the class visited by the outer
   * {@link DefaultMethodClassFixer} for every default method encountered.
   */
  public class DefaultMethodStubber extends ClassVisitor {

    @SuppressWarnings("hiding") private ImmutableList<String> interfaces;
    private String interfaceName;

    public DefaultMethodStubber() {
      super(Opcodes.ASM5);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      checkArgument(BitFlags.isSet(access, Opcodes.ACC_INTERFACE));
      checkState(this.interfaces == null);
      this.interfaces = ImmutableList.copyOf(interfaces);
      interfaceName = name;
    }

    @Override
    public void visitEnd() {
      stubMissingDefaultMethods(this.interfaces);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      if (BitFlags.noneSet(access, Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_BRIDGE)
          && instanceMethods.add(name + ":" + desc)) {
        // Add this method to the class we're desugaring and stub in a body to call the default
        // implementation in the interface's companion class. ijar omits these methods when setting
        // ACC_SYNTHETIC modifier, so don't. Don't do this for bridge methods, which we handle
        // separately.
        // Signatures can be wrong, e.g., when type variables are introduced, instantiated, or
        // refined in the class we're processing, so drop them.
        MethodVisitor stubMethod =
            DefaultMethodClassFixer.this.visitMethod(access, name, desc, (String) null, exceptions);

        int slot = 0;
        stubMethod.visitVarInsn(Opcodes.ALOAD, slot++); // load the receiver
        Type neededType = Type.getMethodType(desc);
        for (Type arg : neededType.getArgumentTypes()) {
          stubMethod.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
          slot += arg.getSize();
        }
        stubMethod.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            interfaceName + InterfaceDesugaring.COMPANION_SUFFIX,
            name,
            InterfaceDesugaring.companionDefaultMethodDescriptor(interfaceName, desc),
            /*itf*/ false);
        stubMethod.visitInsn(neededType.getReturnType().getOpcode(Opcodes.IRETURN));

        stubMethod.visitMaxs(0, 0); // rely on class writer to compute these
        stubMethod.visitEnd();
      }
      return null; // we don't care about the actual code in these methods
    }
  }

  private class InstanceMethodRecorder extends ClassVisitor {

    public InstanceMethodRecorder() {
      super(Opcodes.ASM5);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      checkArgument(BitFlags.noneSet(access, Opcodes.ACC_INTERFACE));
      for (String inheritedInterface : interfaces) {
        // No point copying default methods that we'll also copy for a superclass.  Note we may
        // be processing a class in the bootclasspath, in which case the interfaces must also
        // be in the bootclasspath and we can skip those as well.  Also note this is best-effort,
        // since these interfaces may extend other interfaces that we're not recording here.
        seenInterfaces.add(inheritedInterface);
      }
      super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      recordIfInstanceMethod(access, name, desc);
      return null;
    }
  }
}