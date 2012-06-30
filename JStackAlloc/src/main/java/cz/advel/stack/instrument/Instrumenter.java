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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;

/**
 *
 * @author jezek2
 */
class Instrumenter {
	
	static final String STACK_NAME = Type.getInternalName(Stack.class);
	
	private List<File> files;
	private File destDir;
	private String stackPackageName;
	private String stackInternalName;
	
	private boolean disabled = false;
	private boolean singleThread = false;
	private boolean isolated = false;
	
	private Set<String> stackTypes = new HashSet<String>();
	private File currentFile;
	private String currentMethod;
	private Map<File,Set<String>> classMethods = new LinkedHashMap<File,Set<String>>();

	public Instrumenter(List<File> files, File destDir, String stackPackageName) {
		this.files = files;
		this.destDir = destDir;
		this.stackPackageName = stackPackageName;
		
		stackInternalName = stackPackageName.replace('.', '/') + '/' + "$Stack";
	}

	public void setDisabled(boolean disabled) {
		this.disabled = disabled;
	}

	public boolean isDisabled() {
		return disabled;
	}

	public void setSingleThread(boolean singleThread) {
		this.singleThread = singleThread;
	}

	public boolean isSingleThread() {
		return singleThread;
	}

	public void setIsolated(boolean isolated) {
		this.isolated = isolated;
	}

	public boolean isIsolated() {
		return isolated;
	}

	public void process() throws IOException {
		CheckClass checkClass = new CheckClass(this);
		
		File stackFile = new File(destDir, getStackInternalName() + ".class");
		
		for (File file : files) {
			if (file.equals(stackFile)) continue;
			if (!file.getName().endsWith(".class")) continue;
			
			currentFile = file;
			FileInputStream in = new FileInputStream(file);
			try {
				ClassReader cr = new ClassReader(in);
				cr.accept(checkClass, 0);
			}
			finally {
				in.close();
			}
		}
		
		for (Entry<File,Set<String>> e : classMethods.entrySet()) {
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
			InstrumentClass instrClass = new InstrumentClass(cw, this, e.getValue());
			
			FileInputStream in = new FileInputStream(e.getKey());
			try {
				ClassReader cr = new ClassReader(in);
				cr.accept(instrClass, 0);
			}
			finally {
				in.close();
			}
			
			FileOutputStream out = new FileOutputStream(e.getKey());
			try {
				out.write(cw.toByteArray());
			}
			finally {
				out.close();
			}
		}
		
		if (!isDisabled()) {
			String[] types = stackTypes.toArray(new String[stackTypes.size()]);

			FileOutputStream out = new FileOutputStream(new File(destDir, stackPackageName.replace('.', '/')+"/$Stack.class"));
			try {
				out.write(StackGenerator.generateStackClass(this, types));
			}
			finally {
				out.close();
			}

			if (!isSingleThread()) {
				out = new FileOutputStream(new File(destDir, stackPackageName.replace('.', '/')+"/$Stack$1.class"));
				out.write(StackGenerator.generateStackClass1(this));
				out.close();
			}
		}
		
		System.out.println("Stack instrumented "+classMethods.size()+" classes");
	}

	String getStackInternalName() {
		return stackInternalName;
	}
	
	void addStackType(String internalName) {
		stackTypes.add(internalName);
	}

	void setCurrentMethod(String name) {
		currentMethod = name;
	}

	void addInstrumentMethod() {
		Set<String> methods = classMethods.get(currentFile);
		if (methods == null) {
			methods = new HashSet<String>();
			classMethods.put(currentFile, methods);
		}
		
		methods.add(currentMethod);
	}
	
	static String mangleInternalName(String name) {
		return name.replace('/', '$');
	}
	
}
