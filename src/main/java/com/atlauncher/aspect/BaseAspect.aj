package com.atlauncher.aspect;

public abstract aspect BaseAspect{
    pointcut pubMeth() : execution(public * * (..));
}