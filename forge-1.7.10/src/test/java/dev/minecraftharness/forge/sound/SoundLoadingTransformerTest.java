package dev.minecraftharness.forge.sound;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class SoundLoadingTransformerTest {
    @Test
    public void startupCompletesBeforeReloadReturnsEvenWhenInterrupted() {
        AtomicBoolean complete = new AtomicBoolean();
        Thread.currentThread().interrupt();
        try {
            SoundLoadingTransformer.startAndWait(new Thread(() -> complete.set(true)));
            assertTrue(complete.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void patchesVanillaLoaderWithoutDependingOnObfuscatedMethodNames() {
        ClassWriter input = new ClassWriter(0);
        input.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "Fixture", null, "java/lang/Object", null);
        MethodVisitor method =
                input.visitMethod(
                        Opcodes.ACC_PUBLIC, "arbitraryName", "(Ljava/lang/Thread;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "start", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 2);
        method.visitEnd();
        input.visitEnd();
        byte[] original = input.toByteArray();
        SoundLoadingTransformer transformer = new SoundLoadingTransformer();
        assertSame(original, transformer.transform("Fixture", "Fixture", original));
        ClassNode result = new ClassNode();
        new ClassReader(
                        transformer.transform(
                                "anything", "net.minecraft.client.audio.SoundManager", original))
                .accept(result, 0);
        MethodInsnNode call =
                (MethodInsnNode) ((MethodNode) result.methods.get(0)).instructions.get(1);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals("startAndWait", call.name);
    }
}
