package io.stamethyst.agent.util;

import io.stamethyst.agent.channel.AgentDataChannel;
import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.List;

public class AsmMethodInterceptor extends ClassVisitor {

    private final String className;
    private final String[] methodFilter;
    private final AgentDataChannel channel;
    private final boolean captureLocals;

    public AsmMethodInterceptor(ClassVisitor cv, String className, String[] methodFilter,
                                 AgentDataChannel channel) {
        this(cv, className, methodFilter, channel, false);
    }

    public AsmMethodInterceptor(ClassVisitor cv, String className, String[] methodFilter,
                                 AgentDataChannel channel, boolean captureLocals) {
        super(Opcodes.ASM9, cv);
        this.className = className;
        this.methodFilter = methodFilter;
        this.channel = channel;
        this.captureLocals = captureLocals;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        if ((access & Opcodes.ACC_ABSTRACT) != 0) return mv;
        if ((access & Opcodes.ACC_NATIVE) != 0) return mv;
        if ("<clinit>".equals(name) || "<init>".equals(name)) return mv;

        if (methodFilter != null) {
            boolean match = false;
            for (String filter : methodFilter) {
                if (filter.equals(name)) {
                    match = true;
                    break;
                }
            }
            if (!match) return mv;
        }

        return new TracingMethodAdapter(mv, access, name, descriptor, className, channel, captureLocals);
    }

    private static class TracingMethodAdapter extends MethodVisitor implements Opcodes {
        private final String className;
        private final String methodName;
        private final String agentId;
        private final int startTimeSlot;
        private final int durationSlot;
        private final boolean captureLocals;
        private int exceptionSlot;
        private int namesArrSlot;
        private int valuesArrSlot;
        private final Label tryStart = new Label();
        private final Label tryEnd = new Label();
        private final Label catchStart = new Label();
        private final Label afterCatch = new Label();
        private final List<LocalVarInfo> localVars = new ArrayList<LocalVarInfo>();

        TracingMethodAdapter(MethodVisitor mv, int access, String name, String descriptor,
                             String className, AgentDataChannel channel, boolean captureLocals) {
            super(Opcodes.ASM9, mv);
            this.className = className;
            this.methodName = name;
            this.agentId = channel.getAgentId();
            this.captureLocals = captureLocals;
            int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
            for (org.objectweb.asm.Type t : org.objectweb.asm.Type.getArgumentTypes(descriptor)) {
                slot += t.getSize();
            }
            this.startTimeSlot = slot;
            this.durationSlot = slot + 2;
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                                        Label start, Label end, int index) {
            super.visitLocalVariable(name, descriptor, signature, start, end, index);
            if (captureLocals) {
                localVars.add(new LocalVarInfo(name, descriptor, index));
            }
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (captureLocals) {
                mv.visitTryCatchBlock(tryStart, tryEnd, catchStart, "java/lang/Throwable");
                mv.visitLabel(tryStart);
            }
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LSTORE, startTimeSlot);

            mv.visitLdcInsn(agentId);
            mv.visitLdcInsn(className);
            mv.visitLdcInsn(methodName);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
            mv.visitMethodInsn(INVOKESTATIC,
                "io/stamethyst/agent/util/AgentBytecodeBridge",
                "onMethodEntry",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V",
                false);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            if (captureLocals) {
                // Place exception/arrays AFTER the original maxLocals to avoid
                // overwriting the method's own local variables.
                this.exceptionSlot = maxLocals;
                this.namesArrSlot = maxLocals + 1;
                this.valuesArrSlot = maxLocals + 2;
                mv.visitLabel(tryEnd);
                mv.visitJumpInsn(GOTO, afterCatch);
                mv.visitLabel(catchStart);
                emitCatchHandler();
                mv.visitLabel(afterCatch);
            }
            mv.visitMaxs(maxStack, maxLocals);
        }

        @Override
        public void visitInsn(int opcode) {
            if (isReturnOpcode(opcode)) {
                insertExitTracing();
            }
            super.visitInsn(opcode);
        }

        private void insertExitTracing() {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LLOAD, startTimeSlot);
            mv.visitInsn(LSUB);
            mv.visitVarInsn(LSTORE, durationSlot);

            mv.visitLdcInsn(agentId);
            mv.visitLdcInsn(className);
            mv.visitLdcInsn(methodName);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
            mv.visitVarInsn(LLOAD, durationSlot);
            mv.visitMethodInsn(INVOKESTATIC,
                "io/stamethyst/agent/util/AgentBytecodeBridge",
                "onMethodExit",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)V",
                false);
        }

        private void emitCatchHandler() {
            int N = localVars.size();

            // 1) exceptionSlot = caught exception (top of stack)
            mv.visitVarInsn(ASTORE, exceptionSlot);

            // 2) namesArr = new String[N]
            pushInt(N);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
            mv.visitVarInsn(ASTORE, namesArrSlot);
            for (int i = 0; i < N; i++) {
                mv.visitVarInsn(ALOAD, namesArrSlot);
                pushInt(i);
                mv.visitLdcInsn(localVars.get(i).name);
                mv.visitInsn(AASTORE);
            }

            // 3) valuesArr = new Object[N]
            pushInt(N);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            mv.visitVarInsn(ASTORE, valuesArrSlot);
            for (int i = 0; i < N; i++) {
                mv.visitVarInsn(ALOAD, valuesArrSlot);
                pushInt(i);
                emitLoadLocal(localVars.get(i).index, localVars.get(i).descriptor);
                emitBox(localVars.get(i).descriptor);
                mv.visitInsn(AASTORE);
            }

            // 4) onMethodExceptionSimple(agentId, className, methodName, ts, exception, names, values)
            mv.visitLdcInsn(agentId);
            mv.visitLdcInsn(className);
            mv.visitLdcInsn(methodName);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
            mv.visitVarInsn(ALOAD, exceptionSlot);
            mv.visitVarInsn(ALOAD, namesArrSlot);
            mv.visitVarInsn(ALOAD, valuesArrSlot);
            mv.visitMethodInsn(INVOKESTATIC,
                "io/stamethyst/agent/util/AgentBytecodeBridge",
                "onMethodExceptionSimple",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/Throwable;[Ljava/lang/String;[Ljava/lang/Object;)V",
                false);

            // 5) Rethrow
            mv.visitVarInsn(ALOAD, exceptionSlot);
            mv.visitInsn(ATHROW);
        }

        private void pushInt(int value) {
            if (value >= 0 && value <= 5) {
                mv.visitInsn(ICONST_0 + value);
            } else if (value <= Byte.MAX_VALUE) {
                mv.visitIntInsn(BIPUSH, value);
            } else if (value <= Short.MAX_VALUE) {
                mv.visitIntInsn(SIPUSH, value);
            } else {
                mv.visitLdcInsn(value);
            }
        }

        private void emitLoadLocal(int index, String descriptor) {
            switch (descriptor.charAt(0)) {
                case 'J': mv.visitVarInsn(LLOAD, index); break;
                case 'D': mv.visitVarInsn(DLOAD, index); break;
                case 'F': mv.visitVarInsn(FLOAD, index); break;
                default:
                    if ("I".equals(descriptor) || "Z".equals(descriptor)
                        || "B".equals(descriptor) || "C".equals(descriptor) || "S".equals(descriptor)) {
                        mv.visitVarInsn(ILOAD, index);
                    } else {
                        mv.visitVarInsn(ALOAD, index);
                    }
                    break;
            }
        }

        private void emitBox(String descriptor) {
            switch (descriptor.charAt(0)) {
                case 'I': case 'Z': case 'B': case 'C': case 'S':
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                        "(I)Ljava/lang/Integer;", false);
                    break;
                case 'J':
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false);
                    break;
                case 'F':
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf",
                        "(F)Ljava/lang/Float;", false);
                    break;
                case 'D':
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf",
                        "(D)Ljava/lang/Double;", false);
                    break;
                default:
                    break;
            }
        }

        private static boolean isReturnOpcode(int opcode) {
            return opcode == RETURN || opcode == IRETURN || opcode == FRETURN
                || opcode == ARETURN || opcode == LRETURN || opcode == DRETURN;
        }
    }

    private static class LocalVarInfo {
        final String name;
        final String descriptor;
        final int index;

        LocalVarInfo(String name, String descriptor, int index) {
            this.name = name;
            this.descriptor = descriptor;
            this.index = index;
        }
    }
}
