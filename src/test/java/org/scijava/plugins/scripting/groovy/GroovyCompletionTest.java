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

package org.scijava.plugins.scripting.groovy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.script.ScriptEngine;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.api.ClassIndex;
import org.scijava.code.api.CodeCompletionService;
import org.scijava.code.api.Completion;
import org.scijava.code.api.CompletionRequest;
import org.scijava.code.api.CompletionResult;
import org.scijava.code.api.SignatureHelp;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

/**
 * Tests {@link GroovyCodeCompleter}.
 *
 * @author Gabriel Selzer
 */
public class GroovyCompletionTest {

	private Context context;
	private ScriptLanguage groovy;
	private CodeCompletionService service;

	@Before
	public void setUp() {
		context = new Context(ScriptService.class, CodeCompletionService.class);
		groovy = context.service(ScriptService.class).getLanguageByName("Groovy");
		service = context.service(CodeCompletionService.class);
	}

	@After
	public void tearDown() {
		context.dispose();
	}

	@Test
	public void testCompleterIsDiscovered() {
		assertTrue(service.getCompleterPlugin(
			groovy) instanceof GroovyCodeCompleter);
	}

	@Test
	public void testMembersOfInferredTypes() {
		assertTrue(complete("def s = 'hello'\ns.toUpp").contains("toUpperCase"));
		// Types flow through method calls...
		assertTrue(complete("def x = new ArrayList()\n" +
			"def y = x.subList(0, 1)\ny.isEm").contains("isEmpty"));
		assertTrue(complete("'abc'.toUpperCase().trim().len").contains("length"));
		// ...and through closures.
		assertTrue(complete("[1, 2].collect { it * 2 }.isEm").contains(
			"isEmpty"));
		// Declared types count too.
		assertTrue(complete("List items = null\nitems.isEm").contains("isEmpty"));
		// Inherited members are included.
		assertTrue(complete("def x = new ArrayList()\nx.containsA").contains(
			"containsAll"));
	}

	@Test
	public void testScriptParameters() {
		final String header = "#@ String name\n#@ int count\n";
		assertTrue(complete(header + "name.toUpp").contains("toUpperCase"));
		// Primitive parameters complete as their wrapper type.
		assertTrue(complete(header + "count.intV").contains("intValue"));
		assertTrue(complete(header + "def x = na").contains("name"));
		// Nothing is offered on the parameter lines themselves.
		assertTrue(complete("#@ String na").isEmpty());
	}

	@Test
	public void testStaticMembers() {
		final List<String> texts = complete("Math.ma");
		assertTrue(texts.contains("max"));
		// Only static members of a class.
		assertFalse(complete("Integer.intV").contains("intValue"));
		assertTrue(complete("Integer.parseI").contains("parseInt"));
	}

	@Test
	public void testGroovyMethods() {
		// The GDK adds methods to Java classes.
		assertTrue(complete("[1, 2, 3].coll").contains("collect"));
		assertTrue(complete("def s = 'abc'\ns.rev").contains("reverse"));
		assertTrue(complete("new File('x').eachL").contains("eachLine"));
		final Completion each = completions("[1, 2, 3].eac").stream().filter(
			c -> c.insertionText().equals("each")).findFirst().orElse(null);
		assertTrue(each != null && each.summary().startsWith("GDK"));
	}

	@Test
	public void testScriptClasses() {
		final List<String> texts = complete("class Foo {\n" + //
			"  int bar\n" + //
			"  String baz() { 'x' }\n" + //
			"}\n" + //
			"def f = new Foo()\n" + //
			"f.ba");
		assertTrue(texts.toString(), texts.contains("bar"));
		assertTrue(texts.toString(), texts.contains("baz"));
	}

	@Test
	public void testVariableScopes() {
		assertTrue(complete("def greeting = 'hi'\ndef x = gree").contains(
			"greeting"));
		// Closure parameters are visible inside the closure...
		assertTrue(complete("[1].each { item ->\n  ite").contains("item"));
		// ...but not variables declared in a closure that has ended.
		assertFalse(complete("[1].each { def inner = it }\ninn").contains(
			"inner"));
		// Nor variables declared after the caret.
		assertFalse(complete("def a = lat").contains("later"));
		// For loop variables are visible in the loop.
		assertTrue(complete("for (element in [1, 2]) {\n  elem").contains(
			"element"));
	}

	@Test
	public void testIncompleteCode() {
		// Unclosed brackets before the caret.
		assertTrue(complete("def s = 'x'\nif (true) {\n  s.toUpp").contains(
			"toUpperCase"));
		assertTrue(complete("def s = 'x'\nprintln(s.toUpp").contains(
			"toUpperCase"));
		// Unresolvable classes do not prevent type inference.
		assertTrue(complete("def z = new NoSuchClass()\ndef s = 'x'\ns.toUpp")
			.contains("toUpperCase"));
		// A syntax error above the caret: the caret's line is still analyzed.
		assertTrue(complete("def a = (]\n'abc'.toUpp").contains("toUpperCase"));
	}

	@Test
	public void testKeywords() {
		assertTrue(complete("ret").contains("return"));
		assertFalse(complete("def x = 1.").contains("return"));
	}

	@Test
	public void testClassNamesWithAutoImport() {
		ClassIndex.ensureCache();
		final Completion atomic = completions("def a = new AtomicInt").stream()
			.filter(c -> c.insertionText().equals("AtomicInteger")).findFirst()
			.orElse(null);
		assertTrue(atomic != null);
		assertEquals(1, atomic.additionalEdits().size());
		assertEquals("import java.util.concurrent.atomic.AtomicInteger\n", atomic
			.additionalEdits().get(0).newText());
		// Already imported (or imported by default): no edit.
		final Completion list = completions("def a = new ArrayLis").stream()
			.filter(c -> c.insertionText().equals("ArrayList")).findFirst().orElse(
				null);
		assertTrue(list != null && list.additionalEdits().isEmpty());
		assertNull(GroovyCodeCompleter.importEdit(
			"import java.util.concurrent.atomic.*\n", "java.util.concurrent.atomic.AtomicInteger"));
		// Inserted after the last import.
		assertEquals(20, GroovyCodeCompleter.importEdit("import java.io.File\n" +
			"x = 1\n", "java.util.concurrent.atomic.AtomicInteger").start());
	}

	@Test
	public void testImports() {
		ClassIndex.ensureCache();
		assertTrue(complete("import java.util.concurrent.atomic.AtomicInt")
			.contains("java.util.concurrent.atomic.AtomicInteger"));
		assertTrue(complete("java.util.concurrent.atomic.AtomicInt").contains(
			"AtomicInteger"));
	}

	@Test
	public void testStringsAndComments() {
		assertTrue(complete("def s = 'a.b.").isEmpty());
		assertTrue(complete("// s.toUpp").isEmpty());
		assertTrue(complete("/* x.").isEmpty());
	}

	@Test
	public void testInterpreterBindings() throws Exception {
		final ScriptEngine engine = groovy.getScriptEngine();
		engine.put("injected", new ArrayList<String>());
		final String code = "injected.si";
		final List<String> texts = service.complete(new CompletionRequest(code,
			code.length(), groovy, engine, engine.getContext())).completions()
			.stream().map(Completion::insertionText).collect(Collectors.toList());
		assertTrue(texts.toString(), texts.contains("size"));
	}

	@Test
	public void testSignatureHelp() {
		// A decimal (a BigDecimal) fits the floating-point overloads, which come
		// first; the integral ones do not. The caret is in the second argument.
		final SignatureHelp max = help("Math.max(1.5, ");
		assertEquals(1, max.activeParameter());
		assertEquals("[double:MATCH, float:MATCH, int:MISMATCH, long:MISMATCH]",
			describe(max));
		// An integer fits the integral overloads, and converts to the others.
		assertEquals("[int:MATCH, long:MATCH, double:CONVERSION, " +
			"float:CONVERSION]", describe(help("Math.max(1, 2")));
		// Nothing typed yet: unrated.
		assertTrue(describe(help("Math.max(")).contains("UNKNOWN"));
		// Arguments' types come from the analysis, e.g. of variables.
		final SignatureHelp indexOf = help("def s = 'abc'\ndef c = 'b'\n" +
			"s.indexOf(c, ");
		assertEquals("java.lang.String:MATCH", describe(indexOf).replaceAll(
			"^\\[([^,]*),.*", "$1"));
		// Constructors, and the script's own functions.
		assertTrue(describe(help("new StringBuilder(")).contains(
			"java.lang.String"));
		final SignatureHelp area = help("def area(int w, int h) { w * h }\n" +
			"area(3, ");
		assertEquals("[int:MATCH]", describe(area));
		// The innermost call, and none outside calls.
		assertEquals(0, help("Math.max(Math.abs(").activeParameter());
		assertTrue(help("def x = 1").isEmpty());
	}

	private SignatureHelp help(final String code) {
		return service.getCompleter(groovy).signatureHelp(new CompletionRequest(
			code, groovy, null));
	}

	/** Each signature's first parameter type and fit, in order. */
	private static String describe(final SignatureHelp help) {
		return help.signatures().stream().map(s -> (s.callable().parameters()
			.isEmpty() ? "()" : s.callable().parameters().get(0).type()) + ":" + s
				.fit()).collect(Collectors.toList()).toString();
	}

	@Test
	public void testParameterChoices() {
		final CompletionResult result = service.complete(new CompletionRequest(
			"def a = new ArrayList()\ndef s = 'x'\nCollections.so", groovy, null));
		final List<String> choices = result.parameterChoices().choicesFor(
			new Completion.Parameter("list", "java.util.List")).stream().map(
				Completion::insertionText).collect(Collectors.toList());
		assertEquals("[a]", choices.toString());
	}

	// -- Helper methods --

	private List<Completion> completions(final String code) {
		return service.complete(new CompletionRequest(code, groovy, null))
			.completions();
	}

	private List<String> complete(final String code) {
		return completions(code).stream().map(Completion::insertionText).collect(
			Collectors.toList());
	}
}
