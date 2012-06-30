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

import cz.advel.stack.Stack;
import cz.advel.stack.StaticAlloc;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 *
 * @author jezek2
 */
class InstrumentMethod extends MethodNode {
	
	private static String STACK_NAME = Type.getInternalName(Stack.class);
	private static String STACK_ALLOC_CLASS_DESC = Type.getMethodDescriptor(Type.getType(Object.class), new Type[] { Type.getType(Class.class) } );
	private static String STACK_ALLOC_OBJECT_DESC = Type.getMethodDescriptor(Type.getType(Object.class), new Type[] { Type.getType(Object.class) } );
	private static String STATIC_ALLOC_DESC = Type.getDescriptor(StaticAlloc.class);
	
	// Matthias Mann's Continuations library, see: http://www.matthiasmann.de/content/view/24/26/
	private static String CONTINUATIONS_SUSPEND_EXECUTION_NAME = "de/matthiasmann/continuations/SuspendExecution";
	
	private Instrumenter instr;
	private InstrumentClass inscls;
	private String className;
	private ClassVisitor cv;
	
	private List<Frame> frames;
	
	private boolean disableAllocation;
	private boolean staticAllocation = false;
	
	boolean emitMethod = true;

	public InstrumentMethod(int access, String name, String desc, String signature, String[] exceptions, Instrumenter instr, InstrumentClass inscls, String className, ClassVisitor cv) {
		super(access, name, desc, signature, exceptions);
		this.instr = instr;
		this.inscls = inscls;
		this.className = className;
		this.cv = cv;
		
		disableAllocation = instr.isDisabled();
	}

	@Override
	public void visitEnd() {
		try {
			// always disable stack allocation in suspendable methods:
			for (int i=0; i<exceptions.size(); i++) {
				String exceptionName = (String)exceptions.get(i);
				if (exceptionName.equals(CONTINUATIONS_SUSPEND_EXECUTION_NAME)) {
					disableAllocation = true;
				}
			}
			
			// check for StaticAlloc annotation, if present set staticAllocation to true and remove it:
			if (invisibleAnnotations != null) {
				for (int i=0; i<invisibleAnnotations.size(); i++) {
					AnnotationNode an = (AnnotationNode)invisibleAnnotations.get(i);
					if (an.desc.equals(STATIC_ALLOC_DESC)) {
						if (instr.isSingleThread()) {
							staticAllocation = true;
						}
						invisibleAnnotations.remove(i);
						break;
					}
				}
			}
			
			int stackVar = maxLocals;
			
			Analyzer analyzer = new Analyzer(new SimpleVerifier());
			analyzer.analyze(className, this);
			frames = new ArrayList<Frame>(Arrays.asList(analyzer.getFrames()));
			
			Set<String> usedTypes = new HashSet<String>();
			boolean createStack = false;
			
			AbstractInsnNode insn = instructions.getFirst();
			
			while (insn != null) {
				if (insn instanceof MethodInsnNode) {
					MethodInsnNode min = (MethodInsnNode)insn;
					
					// check for Stack.alloc(Class):
					if (min.owner.equals(STACK_NAME) && min.name.equals("alloc") && min.desc.equals(STACK_ALLOC_CLASS_DESC)) {
						AbstractInsnNode insnBefore = min.getPrevious();
						Type type = null;
						
						if (insnBefore instanceof LdcInsnNode && ((LdcInsnNode)insnBefore).cst instanceof Type) {
							type = (Type)((LdcInsnNode)insnBefore).cst;
							removeInsn(insnBefore);
						}
						else {
							logError("first parameter of Stack.alloc(Class) must be constant");
						}
						
						if (!staticAllocation) {
							usedTypes.add(type.getInternalName());
							instr.addStackType(type.getInternalName());
						}
						
						insn = replaceAllocClass(insn, type, stackVar).getNext();
						removeInsn(min);
						
						// remove redudant checkcast:
						if (insn instanceof TypeInsnNode) {
							TypeInsnNode tin = (TypeInsnNode)insn;
							if (tin.getOpcode() == Opcodes.CHECKCAST && tin.desc.equals(type.getInternalName())) {
								insn = insn.getNext();
								removeInsn(tin);
							}
						}
						
						continue;
					}
					
					// check for Stack.alloc(Object):
					if (min.owner.equals(STACK_NAME) && min.name.equals("alloc") && min.desc.equals(STACK_ALLOC_OBJECT_DESC)) {
						Frame frame = frames.get(instructions.indexOf(insn));
						BasicValue value = (BasicValue)frame.getStack(frame.getStackSize() - 1);
						Type type = value.getType();
						
						if (!staticAllocation) {
							usedTypes.add(type.getInternalName());
							instr.addStackType(type.getInternalName());
						}
						
						insn = replaceAllocObject(insn, type, stackVar).getNext();
						removeInsn(min);
						
						// remove redudant checkcast:
						if (insn instanceof TypeInsnNode) {
							TypeInsnNode tin = (TypeInsnNode)insn;
							if (tin.getOpcode() == Opcodes.CHECKCAST && tin.desc.equals(type.getInternalName())) {
								insn = insn.getNext();
								removeInsn(tin);
							}
						}
						
						continue;
					}
					
					// check for Stack.libraryCleanCurrentThread():
					if (min.owner.equals(STACK_NAME) && min.name.equals("libraryCleanCurrentThread") && min.desc.equals("()V")) {
						if (instr.isDisabled() || instr.isSingleThread()) {
							insn = insn.getNext();
							removeInsn(min);
							continue;
						}
						else {
							insn = insertInsn(insn, new FieldInsnNode(Opcodes.GETSTATIC, instr.getStackInternalName(), "threadLocal", "Ljava/lang/ThreadLocal;"));
							insn = insertInsn(insn, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "remove", "()V"));
							removeInsn(min);
							continue;
						}
					}
				}
				
				insn = insn.getNext();
			}

			if (!disableAllocation && (createStack || !usedTypes.isEmpty())) {
				// create code for getting stack object:
				insn = instructions.getFirst();
				insertStackVarBefore(insn, stackVar);

				LabelNode startLabel = new LabelNode();
				insertInsnBefore(insn, startLabel);

				// push/pop used types if any:
				if (usedTypes.size() > 0) {
					String[] typesArray = usedTypes.toArray(new String[usedTypes.size()]);
					insertPush(insn, typesArray, stackVar);
					
					while (insn != null) {
						if (insn instanceof InsnNode && isReturnOpcode(insn.getOpcode())) {
							insertPop(insn, true, typesArray, stackVar);
						}
						insn = insn.getNext();
					}

					// create finally block:
					LabelNode endLabel = new LabelNode();
					insn = instructions.getLast();
					insn = insertInsn(insn, endLabel);
					insn = insertPop(insn, false, typesArray, stackVar);
					insn = insertInsn(insn, new InsnNode(Opcodes.ATHROW));
					tryCatchBlocks.add(new TryCatchBlockNode(startLabel, endLabel, endLabel, null));
				}
			}
		
			if (emitMethod) {
				accept(cv);
			}
		}
		catch (AnalyzerException e) {
			throw new IllegalStateException(e);
		}
	}
	
	////////////////////////////////////////////////////////////////////////////
	
	private void removeInsn(AbstractInsnNode insn) {
		int idx = instructions.indexOf(insn);
		instructions.remove(insn);
		frames.remove(idx);
	}
	
	private AbstractInsnNode insertInsn(AbstractInsnNode insn, InsnList list) {
		if (list.size() == 0) {
			return insn;
		}
		
		int idx = instructions.indexOf(insn);
		for (int i=0; i<list.size(); i++) {
			frames.add(idx+1, null);
		}
		
		AbstractInsnNode last = list.getLast();
		instructions.insert(insn, list);
		return last;
	}
	
	private void insertInsnBefore(AbstractInsnNode insn, InsnList list) {
		if (list.size() == 0) {
			return;
		}
		
		int idx = instructions.indexOf(insn);
		for (int i=0; i<list.size(); i++) {
			frames.add(idx, null);
		}
		
		instructions.insertBefore(insn, list);
	}
	
	private AbstractInsnNode insertInsn(AbstractInsnNode pos, AbstractInsnNode insn) {
		int idx = instructions.indexOf(pos);
		frames.add(idx+1, null);
		
		instructions.insert(pos, insn);
		return insn;
	}
	
	private AbstractInsnNode insertInsnBefore(AbstractInsnNode pos, AbstractInsnNode insn) {
		int idx = instructions.indexOf(pos);
		frames.add(idx, null);
		
		instructions.insertBefore(pos, insn);
		return insn;
	}
	
	////////////////////////////////////////////////////////////////////////////
	
	private boolean isReturnOpcode(int opcode) {
		switch (opcode) {
			case Opcodes.IRETURN:
			case Opcodes.LRETURN:
			case Opcodes.FRETURN:
			case Opcodes.DRETURN:
			case Opcodes.ARETURN:
			case Opcodes.RETURN:
				return true;
		}
		return false;
	}

	private void insertStackVarBefore(AbstractInsnNode pos, int stackVar) {
		InsnList list = new InsnList();
		
		if (instr.isSingleThread()) {
			list.add(new FieldInsnNode(Opcodes.GETSTATIC, instr.getStackInternalName(), "INSTANCE", "L"+instr.getStackInternalName()+";"));
			list.add(new VarInsnNode(Opcodes.ASTORE, stackVar));
		}
		else {
			String getDesc = Type.getMethodDescriptor(Type.getObjectType(instr.getStackInternalName()), new Type[0]);
			list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, instr.getStackInternalName(), "get", getDesc));
			list.add(new VarInsnNode(Opcodes.ASTORE, stackVar));
		}
		
		insertInsnBefore(pos, list);
	}

	private void insertPush(AbstractInsnNode pos, String[] types, int stackVar) {
		InsnList list = new InsnList();
		
		list.add(new VarInsnNode(Opcodes.ALOAD, stackVar));
		for (int i=0; i<types.length; i++) {
			if (i < types.length-1) {
				list.add(new InsnNode(Opcodes.DUP));
			}
			
			list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, instr.getStackInternalName(),
					"push$"+Instrumenter.mangleInternalName(types[i]),
					"()V"));
		}
		
		insertInsnBefore(pos, list);
	}

	private AbstractInsnNode insertPop(AbstractInsnNode pos, boolean before, String[] types, int stackVar) {
		InsnList list = new InsnList();
		
		list.add(new VarInsnNode(Opcodes.ALOAD, stackVar));
		for (int i=0; i<types.length; i++) {
			if (i < types.length-1) {
				list.add(new InsnNode(Opcodes.DUP));
			}
			
			list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, instr.getStackInternalName(),
					"pop$"+Instrumenter.mangleInternalName(types[i]),
					"()V"));
		}
		
		if (before) {
			insertInsnBefore(pos, list);
			return null;
		}
		else {
			return insertInsn(pos, list);
		}
	}
	
	private AbstractInsnNode replaceAllocClass(AbstractInsnNode pos, Type type, int stackVar) {
		InsnList list = new InsnList();
		
		if (disableAllocation) {
			list.add(new TypeInsnNode(Opcodes.NEW, type.getInternalName()));
			list.add(new InsnNode(Opcodes.DUP));
			list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, type.getInternalName(), "<init>", "()V"));
		}
		else if (staticAllocation) {
			int num = inscls.registerStaticAlloc(type.getInternalName());
			list.add(new FieldInsnNode(Opcodes.GETSTATIC, className, "$stackTemp"+num, type.getDescriptor()));
		}
		else {
			list.add(new VarInsnNode(Opcodes.ALOAD, stackVar));
			list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, instr.getStackInternalName(),
					"get$"+Instrumenter.mangleInternalName(type.getInternalName()),
					"()"+type.getDescriptor()));
		}
		
		return insertInsn(pos, list);
	}

	private AbstractInsnNode replaceAllocObject(AbstractInsnNode pos, Type type, int stackVar) {
		InsnList list = new InsnList();
		
		if (disableAllocation) {
			list.add(new TypeInsnNode(Opcodes.NEW, type.getInternalName()));
			list.add(new InsnNode(Opcodes.DUP));
			list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, type.getInternalName(), "<init>", "()V"));
			
			list.add(new InsnNode(Opcodes.DUP_X1));
			list.add(new InsnNode(Opcodes.SWAP));
			
			Method m = StackGenerator.findGetMethodType(type.getInternalName());
			list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, type.getInternalName(),
					"set",
					"(L"+Type.getInternalName(m.getParameterTypes()[0])+";)V"));
		}
		else if (staticAllocation) {
			int num = inscls.registerStaticAlloc(type.getInternalName());
			list.add(new FieldInsnNode(Opcodes.GETSTATIC, className, "$stackTemp"+num, type.getDescriptor()));
			
			list.add(new InsnNode(Opcodes.DUP_X1));
			list.add(new InsnNode(Opcodes.SWAP));
			
			Method m = StackGenerator.findGetMethodType(type.getInternalName());
			list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, type.getInternalName(),
					"set",
					"(L"+Type.getInternalName(m.getParameterTypes()[0])+";)V"));
		}
		else {
			list.add(new VarInsnNode(Opcodes.ALOAD, stackVar));
			list.add(new InsnNode(Opcodes.SWAP));
			list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, instr.getStackInternalName(),
					"get$"+Instrumenter.mangleInternalName(type.getInternalName()),
					"("+type.getDescriptor()+")"+type.getDescriptor()));
		}
		
		return insertInsn(pos, list);
	}
	
	private void logError(String msg) {
		throw new IllegalStateException(msg+" (in class "+className.replace('/', '.')+", method "+name+")");
	}

}
