package com.atlauncher.annot;

import com.atlauncher.evnt.LogEvent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Log {
    String value();
    LogEvent.LogType type() default LogEvent.LogType.INFO;
}