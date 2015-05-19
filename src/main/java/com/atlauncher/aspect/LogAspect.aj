package com.atlauncher.aspect;

import com.atlauncher.LogManager;
import com.atlauncher.annot.Log;
import com.atlauncher.evnt.LogEvent;

public aspect LogAspect{
    pointcut pubMeth(): execution(public * * (..));

    after(Log ann) : pubMeth() && @annotation(ann){
        LogManager.log(new LogEvent(ann.type(), ann.value()));
    }
}