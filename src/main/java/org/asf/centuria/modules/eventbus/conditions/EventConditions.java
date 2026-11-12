package org.asf.centuria.modules.eventbus.conditions;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention(RUNTIME)
@Target({ TYPE, METHOD })
@RepeatableTarget(EventCondition.class)
public @interface EventConditions {

	public EventCondition[] value();

}
