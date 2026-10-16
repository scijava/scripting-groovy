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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.code.lsp.ScriptSession;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests {@link GroovyDialect}: Groovy scripts, as plain Groovy for
 * scijava-code-lsp-groovy's server (which its own tests test).
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

	@Test
	public void testScriptParameters() throws Exception {
		final String header = "#@ String name\n#@ int count\n";
		assertTrue(complete(header + "name.toUpp").contains("toUpperCase"));
		// Primitive parameters complete as their wrapper type.
		assertTrue(complete(header + "count.intV").contains("intValue"));
		assertTrue(complete(header + "def x = na").contains("name"));
		// Nothing is offered on the parameter lines themselves.
		assertTrue(complete("#@ String na").isEmpty());
	}

	@Test
	public void testDiagnostics() {
		// Where they are in the script, after its parameter lines.
		assertEquals(Collections.singletonList("1:11 Unexpected input: '+'"),
			problems("#@ double sigma\ndef x = (1 +\n"));
		// Parameters are declared, so using them is fine.
		assertEquals(Collections.emptyList(), problems(
			"#@ String name\nprintln name.toUpperCase()\n"));
	}

	// -- Helper methods --

	private ScriptSession session() {
		return context.service(LanguageServerService.class).session(groovy);
	}

	private List<String> complete(final String code) throws Exception {
		try (ScriptSession session = session()) {
			return session.completion(code, code.length()).get(30, TimeUnit.SECONDS)
				.getItems().stream().map(CompletionItem::getLabel).collect(Collectors
					.toList());
		}
	}

	/** The problems found in a script, e.g. {@code 0:4 message}. */
	private List<String> problems(final String code) {
		final List<Diagnostic> found;
		try (ScriptSession session = session()) {
			found = session.update(code).diagnostics(20_000);
		}
		return found.stream().map(d -> d.getRange().getStart().getLine() + ":" + d
			.getRange().getStart().getCharacter() + " " + d.getMessage().getLeft())
			.collect(Collectors.toList());
	}
}
