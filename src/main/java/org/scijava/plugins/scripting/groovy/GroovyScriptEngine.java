/*
 * #%L
 * JSR-223-compliant Groovy scripting language plugin.
 * %%
 * Copyright (C) 2014 Board of Regents of the University of
 * Wisconsin-Madison.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.plugins.scripting.groovy;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.DelegatingMetaClass;
import groovy.lang.GroovyClassLoader;
import groovy.lang.MetaClass;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import groovy.lang.Script;
import groovy.lang.Tuple;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.codehaus.groovy.runtime.MethodClosure;

/**
 * Groovy {@link ScriptEngine} implementation.
 *
 * @author Mike Grogan
 * @author A. Sundararajan
 */
public class GroovyScriptEngine extends AbstractScriptEngine implements
	Compilable, Invocable
{

	private static boolean DEBUG = false;

	// script-string-to-generated Class map
	private final Map<String, Class<?>> classMap;
	// global closures map - this is used to simulate a single
	// global functions namespace
	private final Map<String, Closure<?>> globalClosures;
	// class loader for Groovy generated classes
	private final GroovyClassLoader loader;
	// lazily initialized factory
	private volatile GroovyScriptLanguage factory;

	// counter used to generate unique global Script class names
	private static int counter;

	static {
		counter = 0;
	}

	public GroovyScriptEngine() {
		classMap = Collections.synchronizedMap(new HashMap<String, Class<?>>());
		globalClosures =
			Collections.synchronizedMap(new HashMap<String, Closure<?>>());
		loader =
			new GroovyClassLoader(getParentLoader(), new CompilerConfiguration());
	}

	@Override
	public Object eval(final Reader reader, final ScriptContext ctx)
		throws ScriptException
	{
		return eval(readFully(reader), ctx);
	}

	@Override
	public Object eval(final String script, final ScriptContext ctx)
		throws ScriptException
	{
		try {
			return eval(getScriptClass(script), ctx);
		}
		catch (final Exception e) {
			if (DEBUG) e.printStackTrace();
			throw new ScriptException(e);
		}
	}

	@Override
	public Bindings createBindings() {
		return new SimpleBindings();
	}

	@Override
	public ScriptEngineFactory getFactory() {
		if (factory == null) {
			synchronized (this) {
				if (factory == null) {
					factory = new GroovyScriptLanguage();
				}
			}
		}
		return factory;
	}

	// javax.script.Compilable methods
	@Override
	public CompiledScript compile(final String scriptSource)
		throws ScriptException
	{
		try {
			return new GroovyCompiledScript(this, getScriptClass(scriptSource));
		}
		catch (final CompilationFailedException ee) {
			throw new ScriptException(ee);
		}
	}

	@Override
	public CompiledScript compile(final Reader reader) throws ScriptException {
		return compile(readFully(reader));
	}

	// javax.script.Invocable methods.
	@Override
	public Object invokeFunction(final String name, final Object... args)
		throws ScriptException, NoSuchMethodException
	{
		return invokeImpl(null, name, args);
	}

	@Override
	public Object invokeMethod(final Object thiz, final String name,
		final Object... args) throws ScriptException, NoSuchMethodException
	{
		if (thiz == null) {
			throw new IllegalArgumentException("script object is null");
		}
		return invokeImpl(thiz, name, args);
	}

	@Override
	public <T> T getInterface(final Class<T> clasz) {
		return makeInterface(null, clasz);
	}

	@Override
	public <T> T getInterface(final Object thiz, final Class<T> clasz) {
		if (thiz == null) {
			throw new IllegalArgumentException("script object is null");
		}
		return makeInterface(thiz, clasz);
	}

	// package-privates
	Object eval(final Class<?> scriptClass, final ScriptContext ctx)
		throws ScriptException
	{
		// add context to bindings
		ctx.setAttribute("context", ctx, ScriptContext.ENGINE_SCOPE);

		// direct output to ctx.getWriter
		final Writer writer = ctx.getWriter();
		ctx.setAttribute("out", (writer instanceof PrintWriter) ? writer
			: new PrintWriter(writer), ScriptContext.ENGINE_SCOPE);
		/*
		 * We use the following Binding instance so that global variable lookup
		 * will be done in the current ScriptContext instance.
		 */
		final Binding binding =
			new Binding(ctx.getBindings(ScriptContext.ENGINE_SCOPE)) {

				@Override
				public Object getVariable(final String name) {
					synchronized (ctx) {
						final int scope = ctx.getAttributesScope(name);
						if (scope != -1) {
							return ctx.getAttribute(name, scope);
						}
					}
					throw new MissingPropertyException(name, getClass());
				}

				@Override
				public void setVariable(final String name, final Object value) {
					synchronized (ctx) {
						int scope = ctx.getAttributesScope(name);
						if (scope == -1) {
							scope = ScriptContext.ENGINE_SCOPE;
						}
						ctx.setAttribute(name, value, scope);
					}
				}
			};

		try {
			final Script scriptObject =
				InvokerHelper.createScript(scriptClass, binding);

			// create a Map of MethodClosures from this new script object
			final Method[] methods = scriptClass.getMethods();
			final Map<String, Closure<?>> closures =
				new HashMap<String, Closure<?>>();
			for (final Method m : methods) {
				final String name = m.getName();
				closures.put(name, new MethodClosure(scriptObject, name));
			}

			// save all current closures into global closures map
			globalClosures.putAll(closures);

			final MetaClass oldMetaClass = scriptObject.getMetaClass();

			/*
			 * We override the MetaClass of this script object so that we can
			 * forward calls to global closures (of previous or future "eval" calls)
			 * This gives the illusion of working on the same "global" scope.
			 */
			scriptObject.setMetaClass(new DelegatingMetaClass(oldMetaClass) {

				@Override
				public Object invokeMethod(final Object object, final String name,
					final Object args)
				{
					if (args == null) {
						return invokeMethod(object, name, MetaClassHelper.EMPTY_ARRAY);
					}
					if (args instanceof Tuple) {
						return invokeMethod(object, name, ((Tuple) args).toArray());
					}
					if (args instanceof Object[]) {
						return invokeMethod(object, name, (Object[]) args);
					}
					return invokeMethod(object, name, new Object[] { args });
				}

				@Override
				public Object invokeMethod(final Object object, final String name,
					final Object[] args)
				{
					try {
						return super.invokeMethod(object, name, args);
					}
					catch (final MissingMethodException mme) {
						return callGlobal(name, args, ctx);
					}
				}

				@Override
				public Object invokeStaticMethod(final Object object,
					final String name, final Object[] args)
				{
					try {
						return super.invokeStaticMethod(object, name, args);
					}
					catch (final MissingMethodException mme) {
						return callGlobal(name, args, ctx);
					}
				}
			});

			return scriptObject.run();
		}
		catch (final Exception e) {
			throw new ScriptException(e);
		}
	}

	Class<?> getScriptClass(final String script)
		throws CompilationFailedException
	{
		Class<?> clazz = classMap.get(script);
		if (clazz != null) {
			return clazz;
		}

		final InputStream stream = new ByteArrayInputStream(script.getBytes());
		clazz = loader.parseClass(stream, generateScriptName());
		classMap.put(script, clazz);
		return clazz;
	}

	// -- Internals only below this point

	// invokes the specified method/function on the given object.
	private Object invokeImpl(final Object thiz, final String name,
		final Object... args) throws ScriptException, NoSuchMethodException
	{
		if (name == null) {
			throw new NullPointerException("method name is null");
		}

		try {
			if (thiz != null) {
				return InvokerHelper.invokeMethod(thiz, name, args);
			}
			return callGlobal(name, args);
		}
		catch (final MissingMethodException mme) {
			throw new NoSuchMethodException(mme.getMessage());
		}
		catch (final Exception e) {
			throw new ScriptException(e);
		}
	}

	// call the script global function of the given name
	private Object callGlobal(final String name, final Object[] args) {
		return callGlobal(name, args, context);
	}

	private Object callGlobal(final String name, final Object[] args,
		final ScriptContext ctx)
	{
		final Closure<?> closure = globalClosures.get(name);
		if (closure != null) {
			return closure.call(args);
		}
		// Look for closure valued variable in the
		// given ScriptContext. If available, call it.
		final Object value = ctx.getAttribute(name);
		if (value instanceof Closure) {
			return ((Closure<?>) value).call(args);
		} // else fall thru..
		throw new MissingMethodException(name, getClass(), args);
	}

	// generate a unique name for top-level Script classes
	private synchronized String generateScriptName() {
		return "Script" + (++counter) + ".groovy";
	}

	private <T> T makeInterface(final Object obj, final Class<T> clazz) {
		final Object thiz = obj;
		if (clazz == null || !clazz.isInterface()) {
			throw new IllegalArgumentException("interface Class expected");
		}
		@SuppressWarnings("unchecked")
		final T result =
			(T) Proxy.newProxyInstance(clazz.getClassLoader(), new Class[] { clazz },
				new InvocationHandler() {

					@Override
					public Object invoke(final Object proxy, final Method m,
						final Object[] args) throws Throwable
					{
						return invokeImpl(thiz, m.getName(), args);
					}
				});
		return result;
	}

	// determine appropriate class loader to serve as parent loader
	// for GroovyClassLoader instance
	private ClassLoader getParentLoader() {
		// check whether thread context loader can "see" Groovy Script class
		final ClassLoader ctxtLoader =
			Thread.currentThread().getContextClassLoader();
		try {
			final Class<?> c = ctxtLoader.loadClass("org.codehaus.groovy.Script");
			if (c == Script.class) {
				return ctxtLoader;
			}
		}
		catch (final ClassNotFoundException cnfe) {}
		// exception was thrown or we get wrong class
		return Script.class.getClassLoader();
	}

	private String readFully(final Reader reader) throws ScriptException {
		final char[] arr = new char[8 * 1024]; // 8K at a time
		final StringBuilder buf = new StringBuilder();
		int numChars;
		try {
			while ((numChars = reader.read(arr, 0, arr.length)) > 0) {
				buf.append(arr, 0, numChars);
			}
		}
		catch (final IOException exp) {
			throw new ScriptException(exp);
		}
		return buf.toString();
	}
}
