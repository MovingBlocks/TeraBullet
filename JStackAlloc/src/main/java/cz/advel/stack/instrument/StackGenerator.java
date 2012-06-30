/*
 * JStackAlloc (c) 2008 Martin Dvorak <jezek2@advel.cz>
 *
 * This software is provided 'as-is', without any express or implied warranty.
 * In no event will the authors be held liable for any damages arising from
 * the use of this software.
 * 
 * Permission is granted to anyone to use this software for any purpose, 
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 * 
 * 1. The origin of this software must not be misrepresented; you must not
 *    claim that you wrote the original software. If you use this software
 *    in a product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. Altered source versions must be plainly marked as such, and must not be
 *    misrepresented as being the original software.
 * 3. This notice may not be removed or altered from any source distribution.
 */

package cz.advel.stack.instrument;

import java.lang.reflect.Method;
import org.objectweb.asm.*;

class StackGenerator implements Opcodes {
	
	private static int getParentDistance(Class concrete, Class parent) {
		int cnt = 0;
		while (concrete != null) {
			if (concrete == parent) {
				break;
			}
			cnt++;
			concrete = concrete.getSuperclass();
		}
		return cnt;
	}
	
	public static Method findGetMethodType(String type) {
		try {
			Class cls = Class.forName(type.replace('/', '.'));
			
			Method bestMethod = null;
			int bestDist = Integer.MAX_VALUE;
			
			for (Method method : cls.getMethods()) {
				if (method.getName().equals("set") && method.getParameterTypes().length == 1) {
					if (method.getParameterTypes()[0].isAssignableFrom(cls)) {
						int dist = getParentDistance(cls, method.getParameterTypes()[0]);
						if (bestMethod == null || dist < bestDist) {
							bestMethod = method;
							bestDist = dist;
						}
					}
				}
			}
			
			if (bestMethod == null) {
				throw new IllegalStateException("can't find set method for "+cls);
			}
			
			return bestMethod;
		}
		catch (ClassNotFoundException e) {
			throw new IllegalStateException(e);
		}
	}

	public static byte[] generateStackClass(Instrumenter instr, String[] types) {
		ClassWriter cw = new ClassWriter(0);
		FieldVisitor fv;
		MethodVisitor mv;

		cw.visit(V1_5, ACC_PUBLIC + ACC_FINAL + ACC_SUPER, instr.getStackInternalName(), null, "java/lang/Object", null);

		cw.visitInnerClass(instr.getStackInternalName()+"$1", null, null, ACC_FINAL + ACC_STATIC);

		// fields:
		if (instr.isSingleThread()) {
			fv = cw.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC, "INSTANCE", "L"+instr.getStackInternalName()+";", null, null);
			fv.visitEnd();
		}
		else {
			fv = cw.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC, "threadLocal", "Ljava/lang/ThreadLocal;", null, null);
			fv.visitEnd();
		}
		
		for (String type : types) {
			String mangled = Instrumenter.mangleInternalName(type);
			
			fv = cw.visitField(ACC_PRIVATE, "list$"+mangled, "Ljava/util/ArrayList;", null, null);
			fv.visitEnd();
			
			fv = cw.visitField(ACC_PRIVATE, "stack$"+mangled, "[I", null, null);
			fv.visitEnd();
			
			fv = cw.visitField(ACC_PRIVATE, "count$"+mangled, "I", null, null);
			fv.visitEnd();
			
			fv = cw.visitField(ACC_PRIVATE, "pos$"+mangled, "I", null, null);
			fv.visitEnd();
		}
		
		// constructor:
		{
			mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V");
			
			for (String type : types) {
				String mangled = Instrumenter.mangleInternalName(type);
				
				mv.visitVarInsn(ALOAD, 0);
				mv.visitTypeInsn(NEW, "java/util/ArrayList");
				mv.visitInsn(DUP);
				mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V");
				mv.visitFieldInsn(PUTFIELD, instr.getStackInternalName(), "list$"+mangled, "Ljava/util/ArrayList;");

				mv.visitVarInsn(ALOAD, 0);
				mv.visitIntInsn(BIPUSH, 16);
				mv.visitIntInsn(NEWARRAY, T_INT);
				mv.visitFieldInsn(PUTFIELD, instr.getStackInternalName(), "stack$"+mangled, "[I");

				mv.visitVarInsn(ALOAD, 0);
				mv.visitInsn(ICONST_0);
				mv.visitFieldInsn(PUTFIELD, instr.getStackInternalName(), "count$"+mangled, "I");

				mv.visitVarInsn(ALOAD, 0);
				mv.visitInsn(ICONST_0);
				mv.visitFieldInsn(PUTFIELD, instr.getStackInternalName(), "pos$"+mangled, "I");
			}
			
			mv.visitInsn(RETURN);
			mv.visitMaxs(3, 1);
			mv.visitEnd();
		}
		
		// static stack get method:
		if (!instr.isSingleThread()) {
			mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "get", "()L"+instr.getStackInternalName()+";", null, null);
			mv.visitCode();
			mv.visitFieldInsn(GETSTATIC, instr.getStackInternalName(), "threadLocal", "Ljava/lang/ThreadLocal;");
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;");
			mv.visitTypeInsn(CHECKCAST, instr.getStackInternalName());
			mv.visitInsn(ARETURN);
			mv.visitMaxs(1, 0);
			mv.visitEnd();
		}
		
		// per-type methods:
		for (String type : types) {
			String mangled = Instrumenter.mangleInternalName(type);
			
			{
				mv = cw.visitMethod(ACC_PUBLIC, "push$"+mangled, "()V", null, null);
				mv.visitCode();
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "count$"+mangled, "I");
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "stack$"+mangled, "[I");
				mv.visitInsn(ARRAYLENGTH);
				Label l0 = new Label();
				mv.visitJumpInsn(IF_ICMPNE, l0);
				mv.visitVarInsn(ALOAD, 0);
				mv.visitMethodInsn(INVOKESPECIAL, instr.getStackInternalName(), "resize$"+mangled, "()V");
				mv.visitLabel(l0);
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "stack$"+mangled, "[I");
				mv.visitVarInsn(ALOAD, 0);
				mv.visitInsn(DUP);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "count$"+mangled, "I");
				mv.visitInsn(DUP_X1);
				mv.visitInsn(ICONST_1);
				mv.visitInsn(IADD);
				mv.visitFieldInsn(PUTFIELD, instr.getStackInternalName(), "count$"+mangled, "I");
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "pos$"+mangled, "I");
				mv.visitInsn(IASTORE);
				mv.visitInsn(RETURN);
				mv.visitMaxs(5, 1);
				mv.visitEnd();
			}
			{
				mv = cw.visitMethod(ACC_PRIVATE, "resize$"+mangled, "()V", null, null);
				mv.visitCode();
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "stack$"+mangled, "[I");
				mv.visitInsn(ARRAYLENGTH);
				mv.visitInsn(ICONST_1);
				mv.visitInsn(ISHL);
				mv.visitIntInsn(NEWARRAY, T_INT);
				mv.visitVarInsn(ASTORE, 1);
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "stack$"+mangled, "[I");
				mv.visitInsn(ICONST_0);
				mv.visitVarInsn(ALOAD, 1);
				mv.visitInsn(ICONST_0);
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "stack$"+mangled, "[I");
				mv.visitInsn(ARRAYLENGTH);
				mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V");
				mv.visitVarInsn(ALOAD, 0);
				mv.visitVarInsn(ALOAD, 1);
				mv.visitFieldInsn(PUTFIELD, instr.getStackInternalName(), "stack$"+mangled, "[I");
				mv.visitInsn(RETURN);
				mv.visitMaxs(5, 2);
				mv.visitEnd();
			}
			{
				mv = cw.visitMethod(ACC_PUBLIC, "pop$"+mangled, "()V", null, null);
				mv.visitCode();
				mv.visitVarInsn(ALOAD, 0);
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "stack$"+mangled, "[I");
				mv.visitVarInsn(ALOAD, 0);
				mv.visitInsn(DUP);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "count$"+mangled, "I");
				mv.visitInsn(ICONST_1);
				mv.visitInsn(ISUB);
				mv.visitInsn(DUP_X1);
				mv.visitFieldInsn(PUTFIELD, instr.getStackInternalName(), "count$"+mangled, "I");
				mv.visitInsn(IALOAD);
				mv.visitFieldInsn(PUTFIELD, instr.getStackInternalName(), "pos$"+mangled, "I");
				mv.visitInsn(RETURN);
				mv.visitMaxs(5, 1);
				mv.visitEnd();
			}
			{
				mv = cw.visitMethod(ACC_PUBLIC, "get$"+mangled, "()L"+type+";", null, null);
				mv.visitCode();
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "pos$"+mangled, "I");
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "list$"+mangled, "Ljava/util/ArrayList;");
				mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I");
				Label l0 = new Label();
				mv.visitJumpInsn(IF_ICMPNE, l0);
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "list$"+mangled, "Ljava/util/ArrayList;");
				mv.visitTypeInsn(NEW, type);
				mv.visitInsn(DUP);
				mv.visitMethodInsn(INVOKESPECIAL, type, "<init>", "()V");
				mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z");
				mv.visitInsn(POP);
				mv.visitLabel(l0);
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "list$"+mangled, "Ljava/util/ArrayList;");
				mv.visitVarInsn(ALOAD, 0);
				mv.visitInsn(DUP);
				mv.visitFieldInsn(GETFIELD, instr.getStackInternalName(), "pos$"+mangled, "I");
				mv.visitInsn(DUP_X1);
				mv.visitInsn(ICONST_1);
				mv.visitInsn(IADD);
				mv.visitFieldInsn(PUTFIELD, instr.getStackInternalName(), "pos$"+mangled, "I");
				mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "get", "(I)Ljava/lang/Object;");
				mv.visitTypeInsn(CHECKCAST, type);
				mv.visitInsn(ARETURN);
				mv.visitMaxs(5, 1);
				mv.visitEnd();
			}
			{
				mv = cw.visitMethod(ACC_PUBLIC, "get$"+mangled, "(L"+type+";)L"+type+";", null, null);
				mv.visitCode();
				mv.visitVarInsn(ALOAD, 0);
				mv.visitMethodInsn(INVOKEVIRTUAL, instr.getStackInternalName(), "get$"+mangled, "()L"+type+";");
				mv.visitVarInsn(ASTORE, 2);
				mv.visitVarInsn(ALOAD, 2);
				mv.visitVarInsn(ALOAD, 1);
				
				Method m = findGetMethodType(type);
				mv.visitMethodInsn(INVOKEVIRTUAL, type, "set", "(L"+Type.getInternalName(m.getParameterTypes()[0])+";)V");
				
				mv.visitVarInsn(ALOAD, 2);
				mv.visitInsn(ARETURN);
				mv.visitMaxs(2, 3);
				mv.visitEnd();
			}
		}
		
		// class init:
		{
			mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
			mv.visitCode();
			if (instr.isSingleThread()) {
				mv.visitTypeInsn(NEW, instr.getStackInternalName());
				mv.visitInsn(DUP);
				mv.visitMethodInsn(INVOKESPECIAL, instr.getStackInternalName(), "<init>", "()V");
				mv.visitFieldInsn(PUTSTATIC, instr.getStackInternalName(), "INSTANCE", "L"+instr.getStackInternalName()+";");
				mv.visitInsn(RETURN);
				mv.visitMaxs(2, 0);
			}
			else {
				mv.visitTypeInsn(NEW, instr.getStackInternalName()+"$1");
				mv.visitInsn(DUP);
				mv.visitMethodInsn(INVOKESPECIAL, instr.getStackInternalName()+"$1", "<init>", "()V");
				mv.visitFieldInsn(PUTSTATIC, instr.getStackInternalName(), "threadLocal", "Ljava/lang/ThreadLocal;");
				if (!instr.isIsolated()) {
					mv.visitFieldInsn(GETSTATIC, instr.getStackInternalName(), "threadLocal", "Ljava/lang/ThreadLocal;");
					mv.visitMethodInsn(INVOKESTATIC, Instrumenter.STACK_NAME, "internalRegisterThreadLocal", "(Ljava/lang/ThreadLocal;)V");
				}
				mv.visitInsn(RETURN);
				mv.visitMaxs(2, 0);
			}
			mv.visitEnd();
		}
		cw.visitEnd();

		return cw.toByteArray();
	}
	
	public static byte[] generateStackClass1(Instrumenter instr) {
		ClassWriter cw = new ClassWriter(0);
		MethodVisitor mv;

		cw.visit(V1_5, ACC_FINAL + ACC_SUPER, instr.getStackInternalName()+"$1", null, "java/lang/ThreadLocal", null);

		cw.visitOuterClass(instr.getStackInternalName(), null, null);

		cw.visitInnerClass(instr.getStackInternalName()+"$1", null, null, ACC_FINAL + ACC_STATIC);

		{
			mv = cw.visitMethod(0, "<init>", "()V", null, null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitMethodInsn(INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V");
			mv.visitInsn(RETURN);
			mv.visitMaxs(1, 1);
			mv.visitEnd();
		}
		{
			mv = cw.visitMethod(ACC_PROTECTED, "initialValue", "()Ljava/lang/Object;", null, null);
			mv.visitCode();
			mv.visitTypeInsn(NEW, instr.getStackInternalName());
			mv.visitInsn(DUP);
			mv.visitMethodInsn(INVOKESPECIAL, instr.getStackInternalName(), "<init>", "()V");
			mv.visitInsn(ARETURN);
			mv.visitMaxs(2, 1);
			mv.visitEnd();
		}
		cw.visitEnd();

		return cw.toByteArray();
	}
	
}
