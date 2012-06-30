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
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.FileSet;

/**
 * Example usage:
 * 
 * <pre>
 * &lt;target name="instrument-classes"&gt;
 *     &lt;taskdef name="instrument-stack"
 *         classname="cz.advel.stack.instrument.InstrumentationTask"
 *         classpath="${run.classpath}"&gt;
 *     &lt;/taskdef&gt;
 * 
 *     &lt;instrument-stack dest="${build.classes.dir}" packageName="your.package.name"&gt;
 *         &lt;fileset dir="${build.classes.dir}" includes="&#42;&#42;/&#42;.class"/&gt;
 *     &lt;/instrument-stack&gt;
 * &lt;/target&gt;
 * 
 * &lt;target name="-post-compile" depends="instrument-classes"&gt;
 * &lt;/target&gt;
 * </pre>
 * 
 * @author jezek2
 */
public class InstrumentationTask extends Task {
	
	private List<FileSet> fileSets = new ArrayList<FileSet>();
	private File destDir;
	private String stackPackageName;
	private boolean disabled = false;
	private boolean singleThread = false;
	private boolean isolated = false;
	
	public void addFileSet(FileSet fs) {
		fileSets.add(fs);
	}

	public void setDest(File destDir) {
		this.destDir = destDir;
	}

	public void setPackageName(String packageName) {
		this.stackPackageName = packageName;
	}
	
	/**
	 * If true, stack allocation is disabled and every occurence of Stack.alloc()
	 * methods are replaced by direct object allocation.
	 */
	public void setDisabled(boolean b) {
		disabled = b;
	}
	
	/**
	 * Sets single thread mode. If enabled, stack is accessed using static field
	 * instead of ThreadLocal. Gives some performance boost if you don't run in
	 * more then one thread.
	 */
	public void setSingleThread(boolean b) {
		singleThread = b;
	}

	/**
	 * Sets isolated mode.<p>
	 * 
	 * If enabled, instrumented bytecode won't have dependency on JStackAlloc
	 * library, this disables effect of {@link Stack#cleanCurrentThread} method
	 * on stack instances of any library that is compiled with this option.<p>
	 * 
	 * Library author(s) should provide their own method for cleaning resources for
	 * current thread (possibly also cleaning other resources). See
	 * {@link Stack#libraryCleanCurrentThread} method.
	 */
	public void setIsolated(boolean isolated) {
		this.isolated = isolated;
	}

	@Override
	public void execute() throws BuildException {
		try {
			List<File> files = new ArrayList<File>();
			for (FileSet fs : fileSets) {
				String[] fileNames = fs.getDirectoryScanner().getIncludedFiles();
				for (String fname : fileNames) {
					File file = new File(fs.getDir(), fname);
					if (file.getName().endsWith(".class")) {
						files.add(file);
					}
				}
			}
			
			Instrumenter instr = new Instrumenter(files, destDir, stackPackageName);
			instr.setDisabled(disabled);
			instr.setSingleThread(singleThread);
			instr.setIsolated(isolated);
			instr.process();
		}
		catch (IOException e) {
			throw new BuildException(e);
		}
	}

}
