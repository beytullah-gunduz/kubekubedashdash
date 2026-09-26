package com.kubekubedashdash.releasecheck;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Fails the release build when a jar contains an {@code invokespecial} of an interface
 * method whose owner is neither the calling class nor one of its DIRECT interfaces.
 *
 * <p>HotSpot's verifier rejects that instruction ("Bad invokespecial instruction: interface
 * method reference is in an indirect superinterface") the first time the class loads.
 * javac and kotlinc never emit it, but ProGuard can create it: when it drops an interface
 * from a class's {@code implements} list because another listed interface already extends
 * it, an existing {@code Owner.super.m()} call is left pointing at an indirect
 * superinterface. That is how the Ktor 3.6.0 / MCP SDK 0.14.0 bump broke the shrunk app
 * (JobSupport lost Job; JobSupport.cancel() calls Job.cancel() with invokespecial) while
 * every test and ProGuard itself stayed green.
 *
 * <p>Resolving a {@code REF_invokeSpecial} method-handle constant applies the same rule at
 * link time, so those are checked too.
 *
 * <p>Usage: {@code IndirectSuperCallScan <dir-or-jar>...}. A directory contributes its
 * {@code *.jar} files. Exits 1 on any hit, on zero jars or classes, on an unreadable
 * class, or when the built-in self-test (a synthetic class with the bad call) is not
 * flagged.
 */
public final class IndirectSuperCallScan {
    private IndirectSuperCallScan() {
    }

    public static void main(String[] args) throws IOException {
        selfTest();

        List<Path> jars = new ArrayList<>();
        for (String arg : args) {
            Path path = Path.of(arg);
            if (Files.isDirectory(path)) {
                try (Stream<Path> listing = Files.list(path)) {
                    listing.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
                }
            } else {
                jars.add(path);
            }
        }

        Scan scan = new Scan();
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.endsWith(".class") || name.endsWith("module-info.class")) continue;
                    try (InputStream in = zip.getInputStream(entry)) {
                        scan.scanClass(jar.getFileName() + "!" + name, in.readAllBytes());
                    }
                }
            }
        }

        scan.hits.forEach(System.out::println);
        scan.unreadable.forEach(u -> System.out.println("UNREADABLE " + u));
        System.out.println("Scanned " + jars.size() + " jars, " + scan.classes + " classes, "
            + scan.interfaceSuperCalls + " interface super-calls and handles: " + scan.hits.size()
            + " to an indirect superinterface, " + scan.unreadable.size() + " unreadable classes");

        if (jars.isEmpty() || scan.classes == 0) {
            System.out.println("FAIL: nothing was scanned");
            System.exit(1);
        }
        if (!scan.hits.isEmpty() || !scan.unreadable.isEmpty()) {
            System.out.println("FAIL: the JVM verifier would reject the classes above");
            System.exit(1);
        }
    }

    /** Collects hits across every class it is given. */
    static final class Scan {
        final Set<String> hits = new TreeSet<>();
        final Set<String> unreadable = new TreeSet<>();
        int classes;
        int interfaceSuperCalls;

        void scanClass(String location, byte[] bytes) {
            ClassReader reader;
            try {
                reader = new ClassReader(bytes);
            } catch (RuntimeException e) {
                // An unsupported class-file version: ASM needs a bump. Never skip silently.
                unreadable.add(location + " (" + e + ")");
                return;
            }
            classes++;
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                private String self;
                private Set<String> directInterfaces;

                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    self = name;
                    directInterfaces = Set.of(interfaces == null ? new String[0] : interfaces);
                }

                @Override
                public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
                            if (opcode == Opcodes.INVOKESPECIAL && isInterface) check(owner, "invokespecial " + owner + "." + name + desc);
                        }

                        // Resolving a REF_invokeSpecial method handle applies the same rule at link
                        // time (IncompatibleClassChangeError), so handle constants count too.
                        @Override
                        public void visitLdcInsn(Object value) {
                            checkHandle(value);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String desc, Handle bootstrap, Object... bootstrapArgs) {
                            checkHandle(bootstrap);
                            for (Object arg : bootstrapArgs) checkHandle(arg);
                        }

                        private void checkHandle(Object value) {
                            if (value instanceof Handle handle && handle.getTag() == Opcodes.H_INVOKESPECIAL && handle.isInterface()) {
                                check(handle.getOwner(), "method handle REF_invokeSpecial " + handle.getOwner() + "." + handle.getName() + handle.getDesc());
                            }
                        }

                        private void check(String owner, String what) {
                            if (owner.equals(self)) return;
                            interfaceSuperCalls++;
                            if (!directInterfaces.contains(owner)) hits.add(location + ": " + self + "." + methodName + " -> " + what);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
    }

    /**
     * Proves the detector still works before its "0 hits" is trusted: {@code Bad} implements
     * {@code Sub} (which extends {@code Base}) and calls {@code Base.m()} with invokespecial
     * — exactly JobSupport's shape after shrinking — and {@code BadHandle} loads the same
     * call as a method handle. {@code Good} and {@code GoodHandle} implement {@code Base}
     * directly, which the JVM accepts.
     */
    private static void selfTest() {
        Scan scan = new Scan();
        scan.scanClass("self-test", callerClass("selftest/Bad", "selftest/Sub", false));
        scan.scanClass("self-test", callerClass("selftest/BadHandle", "selftest/Sub", true));
        scan.scanClass("self-test", callerClass("selftest/Good", "selftest/Base", false));
        scan.scanClass("self-test", callerClass("selftest/GoodHandle", "selftest/Base", true));
        Set<String> flagged = new TreeSet<>();
        scan.hits.forEach(hit -> flagged.add(hit.substring(hit.indexOf("selftest/"), hit.indexOf(".call"))));
        if (!flagged.equals(Set.of("selftest/Bad", "selftest/BadHandle")) || scan.hits.size() != 2 || scan.interfaceSuperCalls != 4) {
            System.out.println("FAIL: self-test expected exactly selftest/Bad and selftest/BadHandle, got " + scan.hits);
            System.exit(1);
        }
    }

    private static byte[] callerClass(String name, String directInterface, boolean asHandle) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", new String[] {directInterface});
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "call", "()V", null, null);
        method.visitCode();
        if (asHandle) {
            method.visitLdcInsn(new Handle(Opcodes.H_INVOKESPECIAL, "selftest/Base", "m", "()V", true));
            method.visitInsn(Opcodes.POP);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "selftest/Base", "m", "()V", true);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
