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

package cz.advel.stack;

import cz.advel.stack.instrument.InstrumentationTask;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Support for stack allocation of "value" objects. The only requirements for "value"
 * objects is that they must have public zero argument constructor and <code>set</code>
 * method with one argument of the same type (or superclass) which copies data from
 * given instance.<p>
 * 
 * <b>Example usage:</b>
 * <pre>
 * public static Vector3f average(Vector3f v1, Vector3f v2, Vector3f out) {
 *     out.add(v1, v2);
 *     out.scale(0.5f);
 *     return out;
 * }
 * 
 * public static void test() {
 *     Vector3f v1 = Stack.alloc(Vector3f.class);
 *     v1.set(0f, 1f, 2f);
 * 
 *     Vector3f v2 = Stack.alloc(v1);
 *     v2.x = 10f;
 * 
 *     Vector3f avg = average(v1, v2, Stack.alloc(Vector3f.class));
 * }
 * </pre>
 * which is transformed into something like the following code. The actual
 * generated code has mangled names for unique type identification and can have
 * other minor differences.
 * <pre>
 * public static void test() {
 *     $Stack stack = $Stack.get();
 *     stack.pushVector3f();
 *     try {
 *         Vector3f v1 = stack.getVector3f();
 *         v1.set(0f, 1f, 2f);
 * 
 *         Vector3f v2 = stack.getVector3f(v1);
 *         v2.x = 10f;
 * 
 *         Vector3f avg = average(v1, v2, stack.getVector3f());
 *     }
 *     finally {
 *         stack.popVector3f();
 *     }
 * }
 * </pre>
 * 
 * <b>Rules:</b>
 * <ul>
 * <li>classes needs to be instrumented by provided InstrumentationTask for ANT, otherwise
 *     error is throwed in runtime</li>
 * <li>stack is pushed only once per method, do not use stack allocation in loops</li>
 * <li>returning of stack allocated objects is not supported, use output parameter instead (like in the
 *     example)</li>
 * <li>working with stack is thread-safe, the data are separate for each thread</li>
 * <li>combining different libraries works fine, each must have their stack stored
 *     in different package, so you'll just end up with multiple stacks in final application,
 *     which is fine, because the values are used between them without problem
 * <li>when creating and destroying threads you must be aware that the implementation
 *     uses ThreadLocal to persist stack instances between method calls, it's advisable
 *     to call <code>cleanCurrentThread</code> method on thread just before destroying
 * </li>
 * </ul>
 * 
 * @author jezek2
 */
public class Stack {
	
	private static List<WeakReference<ThreadLocal>> threadLocalList = new ArrayList<WeakReference<ThreadLocal>>();
	
	private Stack() {
	}

	/**
	 * Returns stack allocated object.<p>
	 * 
	 * Requires instrumentation of your classes in order to work.
	 * 
	 * @param cls class type, must be compile-time constant
	 * @return stack allocated instance of given class
	 */
	public static <T> T alloc(Class<T> cls) {
		throw new Error("not instrumented");
	}

	/**
	 * Returns stack allocated object with copied value from given parameter.<p>
	 * 
	 * Requires instrumentation of your classes in order to work.
	 * 
	 * @param obj object to copy on stack, the type must be statically known
	 * @return stack allocated instance with copied data
	 */
	public static <T> T alloc(T obj) {
		throw new Error("not instrumented");
	}
	
	/**
	 * Used internally.
	 */
	public static synchronized void internalRegisterThreadLocal(ThreadLocal local) {
		threadLocalList.add(new WeakReference<ThreadLocal>(local));
	}
	
	/**
	 * Removes all cached stack instances for current thread.
	 */
	public static synchronized void cleanCurrentThread() {
		for (Iterator<WeakReference<ThreadLocal>> it = threadLocalList.iterator(); it.hasNext(); ) {
			WeakReference<ThreadLocal> ref = it.next();
			ThreadLocal local = ref.get();
			if (local != null) {
				local.remove();
			}
			else {
				it.remove();
			}
		}
	}

	/**
	 * Removes all cached stack instances for current thread in current library.<p>
	 * 
	 * Requires instrumentation of your classes in order to work.
	 * 
	 * @see InstrumentationTask#setIsolated(boolean)
	 */
	public static void libraryCleanCurrentThread() {
		throw new Error("not instrumented");
	}
	
}
