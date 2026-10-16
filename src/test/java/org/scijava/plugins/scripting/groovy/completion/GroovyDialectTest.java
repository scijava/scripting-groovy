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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests {@link GroovyDialect}.
 *
 * @author Gabriel Selzer
 */
public class GroovyDialectTest {

	private Context context;
	private ScriptLanguage groovy;

	@Before
	public void setUp() {
		context = new Context(ScriptService.class, LanguageServerService.class);
		groovy = context.service(ScriptService.class).getLanguageByName("Groovy");
	}

	@After
	public void tearDown() {
		context.dispose();
	}

	@Test
	public void testScriptsAreServed() {
		// Groovy scripts become plain Groovy, which scijava-code-lsp-groovy
		// serves.
		final GroovyDialect dialect = new GroovyDialect();
		assertTrue(dialect.supports(groovy));
		assertEquals("groovy", dialect.languageId());
		assertTrue(context.service(LanguageServerService.class).supports(groovy));
	}

	@Test
	public void testTypes() {
		final GroovyDialect dialect = new GroovyDialect();
		assertEquals("java.util.List", dialect.runtimeType(java.util.List.class));
		assertEquals("Object", dialect.runtimeType(null));
		assertEquals("java.util.List items", dialect.declare("items",
			"java.util.List"));
		assertTrue(dialect.blanks("#@ String name"));
	}
}
