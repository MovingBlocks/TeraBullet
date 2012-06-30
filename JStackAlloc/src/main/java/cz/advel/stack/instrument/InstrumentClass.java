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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 *
 * @author jezek2
 */
class InstrumentClass extends ClassAdapter {
	
	private Instrumenter instr;
	private Set<String> methods;
	private String className;
	
	private boolean disableMethodInstrumentation = false;
	private MethodNode clinitMethod;
	
	private List<String> tempStaticFields = new ArrayList<String>();

	public InstrumentClass(ClassVisitor cv, Instrumenter instr, Set<String> methods) {
		super(cv);
		this.instr = instr;
		this.methods = methods;
	}

	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		super.visit(version, access, name, signature, superName, interfaces);
		className = name;
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		if (disableMethodInstrumentation) {
			return super.visitMethod(access, name, desc, signature, exceptions);
		}
		
		if (methods.contains(name)) {
			InstrumentMethod im = new InstrumentMethod(access, name, desc, signature, exceptions, instr, this, className, cv);
			if (isInitializerInstrumentationNeeded() && name.equals("<clinit>")) {
				im.emitMethod = false;
				clinitMethod = im;
			}
			return im;
		}
		
		if (isInitializerInstrumentationNeeded() && name.equals("<clinit>")) {
			clinitMethod = new MethodNode(access, name, desc, signature, exceptions);
			return clinitMethod;
		}
		
		return super.visitMethod(access, name, desc, signature, exceptions);
	}

	@Override
	public void visitEnd() {
		if (isInitializerInstrumentationNeeded()) {
			if (tempStaticFields.size() > 0 && clinitMethod == null) {
				clinitMethod = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
				clinitMethod.instructions.add(new InsnNode(Opcodes.RETURN));
			}

			FieldVisitor fv;
			for (int i=0; i<tempStaticFields.size(); i++) {
				fv = visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC, "$stackTemp"+i, "L"+tempStaticFields.get(i)+";", null, null);
				fv.visitEnd();
			}

			if (clinitMethod != null) {
				InsnList list = new InsnList();
				for (int i=0; i<tempStaticFields.size(); i++) {
					list.add(new TypeInsnNode(Opcodes.NEW, tempStaticFields.get(i)));
					list.add(new InsnNode(Opcodes.DUP));
					list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, tempStaticFields.get(i), "<init>", "()V"));
					list.add(new FieldInsnNode(Opcodes.PUTSTATIC, className, "$stackTemp"+i, "L"+tempStaticFields.get(i)+";"));
				}
				clinitMethod.instructions.insertBefore(clinitMethod.instructions.getFirst(), list);

				disableMethodInstrumentation = true;
				clinitMethod.accept(this);
			}
		}
		
		super.visitEnd();
	}
	
	private boolean isInitializerInstrumentationNeeded() {
		return instr.isSingleThread();
	}
	
	public int registerStaticAlloc(String type) {
		tempStaticFields.add(type);
		return tempStaticFields.size() - 1;
	}

}
