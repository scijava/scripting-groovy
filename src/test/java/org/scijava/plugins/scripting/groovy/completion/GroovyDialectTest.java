/*
 * #%L
 * JSR-223-compliant Groovy scripting language plugin.
 * %%
 * Copyright (C) 2014 - 2026 SciJava developers.
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

package org.scijava.plugins.scripting.groovy.completion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.Diagnostic;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.code.lsp.ScriptDialect.Header;
import org.scijava.code.lsp.ScriptSession;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests {@link GroovyDialect}
 *
 * @author Gabriel Selzer
 */
public class GroovyDialectTest {

	private GroovyDialect dialect;
	private Context context;
	private ScriptLanguage groovy;

	@Before
	public void setUp() {
		dialect = new GroovyDialect();
		context = new Context(ScriptService.class, LanguageServerService.class);
		groovy = context.service(ScriptService.class).getLanguageByName("Groovy");
	}

	@After
	public void tearDown() {
		context.dispose();
	}

	@Test
	public void testScriptsLanguageAdherence() {
		// Ensure the dialect supports the Groovy Script Language
		assertTrue(dialect.supports(groovy));
	}

	@Test
	public void testHeader() {
		// SciJava Script parameters should be declared in Groovy code with
		// their Java types, e.g. "java.util.List items"
		final Map<String, Class<?>> parameters = new LinkedHashMap<>();
		parameters.put("items", java.util.List.class);
		parameters.put("count", int.class);
		// Anonymous classes have no name to write.
		parameters.put("thing", new Object() {}.getClass());
		final Header header = dialect.header(parameters);
		assertEquals("java.util.List items; int count; Object thing", header
			.code());
		// Nothing is imported.
		assertTrue(header.hiddenNames().isEmpty());
	}

	@Test
	public void testGroovyServerIntegration() {
		// With scijava-code-lsp-groovy's server: the parameters' declarations,
		// and the blanked parameter lines, parse.
		try (ScriptSession session = new ScriptSession(context.service(
			LanguageServerService.class), groovy))
		{
			final List<Diagnostic> problems = session.update(
				"#@ String name\n#@ int count\nprintln name * count\n").diagnostics(
					20_000);
			assertTrue(problems.toString(), problems.isEmpty());
		}
	}
}
