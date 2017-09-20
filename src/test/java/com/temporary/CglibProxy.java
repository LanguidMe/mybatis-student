package com.temporary;

import java.lang.reflect.Method;

import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

public class CglibProxy implements MethodInterceptor{
	private Enhancer enhancer = new Enhancer();
	
	public Object getProxy(Class<?> clazz) {
		enhancer.setSuperclass(clazz);
		enhancer.setCallback(this);
		return enhancer.create();
	}
	
	@Override
	public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
		System.out.println("前置处理");
		Object result = proxy.invokeSuper(obj, args);
		System.out.println("后置处理");
		return result;
	}
	
	
	public static void main(String[] args) {
		CglibProxy cglibProxy = new CglibProxy();
		SayHello hello = (SayHello) cglibProxy.getProxy(SayHello.class);
		hello.say();
	}
}
