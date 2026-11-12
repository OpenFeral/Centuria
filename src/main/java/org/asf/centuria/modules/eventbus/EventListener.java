package org.asf.centuria.modules.eventbus;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * 
 * An annotation used to mark a method as EventListener, the arguments will need
 * to contain a EventObject for this to work
 * 
 * @author Sky Swimmer
 *
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface EventListener {
}
