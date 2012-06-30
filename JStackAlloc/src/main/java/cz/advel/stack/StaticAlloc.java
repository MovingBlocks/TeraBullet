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

import java.lang.annotation.*;

/**
 * Marks method to use static fields instead of stack allocation when instrumented
 * in single-thread mode.<p>
 * 
 * You must be careful to use it only on methods that can't re-enter itself (even on
 * different instances), directly (eg. recursion) or indirectly (eg. by calling some
 * other method which calls this method again, or reentrancy introduced by user when
 * extending class).
 * 
 * @author jezek2
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface StaticAlloc {
}
