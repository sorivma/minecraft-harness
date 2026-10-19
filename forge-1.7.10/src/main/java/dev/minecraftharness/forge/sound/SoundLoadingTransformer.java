package dev.minecraftharness.forge.sound;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Wait for the vanilla loader before resource reload can unload/recreate OpenAL. */
public final class SoundLoadingTransformer implements IClassTransformer {
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !"net.minecraft.client.audio.SoundManager".equals(transformedName))
            return bytes;
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, 0);
        int patched = 0;
        for (Object entry : type.methods) {
            MethodNode method = (MethodNode) entry;
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/Thread".equals(call.owner)
                        && "start".equals(call.name)
                        && "()V".equals(call.desc)) {
                    method.instructions.set(
                            call,
                            new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    "dev/minecraftharness/forge/sound/SoundLoadingTransformer",
                                    "startAndWait",
                                    "(Ljava/lang/Thread;)V",
                                    false));
                    patched++;
                }
            }
        }
        if (patched != 1)
            throw new IllegalStateException("Expected one SoundManager loader, found " + patched);
        ClassWriter output = new ClassWriter(0);
        type.accept(output);
        return output.toByteArray();
    }

    public static void startAndWait(Thread loader) {
        loader.start();
        boolean interrupted = false;
        while (loader.isAlive()) {
            try {
                loader.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
