/*
 * #%L
 * JSR-223-compliant Groovy scripting language plugin.
 * %%
 * Copyright (C) 2014 - 2021 SciJava developers.
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

import java.security.CodeSource;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.codehaus.groovy.ast.CompileUnit;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.jsr223.GroovyScriptEngineFactory;
import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;
import org.scijava.Priority;
import org.scijava.plugin.Plugin;
import org.scijava.script.AdaptedScriptLanguage;
import org.scijava.script.ScriptLanguage;

import groovy.lang.GroovyClassLoader;

/**
 * An adapter of the Groovy interpreter to the SciJava scripting interface.
 *
 * @author Curtis Rueden
 * @see ScriptEngine
 */
@Plugin(type = ScriptLanguage.class, name = "Groovy", priority = Priority.HIGH)
public class GroovyScriptLanguage extends AdaptedScriptLanguage {

	private static final ThreadLocal<CompileUnit> asts = new ThreadLocal<>();

	public GroovyScriptLanguage() {
		super(new GroovyScriptEngineFactory());
	}

	@Override
	public ScriptEngine getScriptEngine() {
		// Attach a "compiler customizer" (a Groovy feature) to the script engine,
		// which will feed extra imports into each script prior to evaluation.
		final CompilerConfiguration compilerConfig = new CompilerConfiguration();
		final ImportRetainer importRetainer = new ImportRetainer();
		compilerConfig.addCompilationCustomizers(importRetainer);

		final GroovyScriptEngineImpl engine = new GroovyScriptEngineImpl() {

			@Override
			public Object eval(String script, ScriptContext scriptContext) throws ScriptException {
				final Object result = super.eval(script, scriptContext);

				// Retrieve the AST that was built during compilation+evaluation.
				final CompileUnit ast = asts.get();
				asts.remove();

				// Extract the imports present in the AST, and save them for next time.
				for (final ModuleNode m : ast.getModules()) {
					m.getImports().forEach(ipt -> importRetainer.retainImport(ipt));
					m.getStarImports().forEach(ipt -> importRetainer.retainStarImport(ipt));
					m.getStaticImports().values().forEach(ipt -> importRetainer.retainStaticImport(ipt));
					m.getStaticStarImports().values().forEach(ipt -> importRetainer.retainStaticStarImport(ipt));
				}
				return result;
			}
		};

		// Wrap the script engine's ClassLoader in another layer, which saves AST
		// objects as soon as they are created. In this way, we can retrieve the AST
		// of each particular evaluation after it completes. This is important so
		// that we can extract the imports of that evaluation, to reapply them when
		// evaluating subsequent scripts.
		final GroovyClassLoader classLoader = new GroovyClassLoader(engine.getClassLoader(), compilerConfig, false) {

			@Override
			protected CompilationUnit createCompilationUnit(CompilerConfiguration config, CodeSource source) {
				// Create the compilation unit as usual.
				final CompilationUnit unit = super.createCompilationUnit(config, source);

				// Obtain the AST that now exists as part of the unit creation.
				final CompileUnit ast = unit.getAST();

				// Save the AST into a ThreadLocal, for post-eval retrieval.
				asts.set(ast);

				return unit;
			}
		};
		engine.setClassLoader(classLoader);

		return engine;
	}
}
