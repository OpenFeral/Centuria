package org.asf.centuria.modules.eventbus.conditions;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.asf.centuria.modules.eventbus.conditions.interfaces.IEventConditionConstructor;

@Retention(RUNTIME)
@Target({ TYPE, METHOD })
public @interface EventConditionConstructor {

	/**
	 * Defines the condition constructor type
	 * 
	 * @return IEventConditionConstructor class value
	 */
	public Class<? extends IEventConditionConstructor> value();

}
