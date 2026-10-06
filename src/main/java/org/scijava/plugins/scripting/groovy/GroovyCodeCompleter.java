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

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.script.Bindings;
import javax.script.ScriptContext;

import groovy.lang.GroovySystem;
import groovy.lang.MetaMethod;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.scijava.Context;
import org.scijava.Priority;
import org.scijava.code.api.AbstractCodeCompleterPlugin;
import org.scijava.code.api.ClassIndex;
import org.scijava.code.api.CodeCompleterPlugin;
import org.scijava.code.api.CodeCompletionService;
import org.scijava.code.api.Completion;
import org.scijava.code.api.Completion.TextEdit;
import org.scijava.code.api.CompletionRequest;
import org.scijava.code.api.CompletionResult;
import org.scijava.code.api.Completions;
import org.scijava.code.api.ParameterChoices;
import org.scijava.code.api.ScriptDialect;
import org.scijava.code.api.ScriptDocument;
import org.scijava.code.api.SignatureHelp;
import org.scijava.code.api.Signatures;
import org.scijava.plugin.Plugin;
import org.scijava.util.Types;

/**
 * Code completion for Groovy, driven by Groovy's own compiler (see
 * {@link GroovyAnalysis}): members of the expression before a dot, using the
 * types the static type checker infers; variables in scope; keywords; class
 * names, with automatic imports; and import statements.
 *
 * @author Gabriel Selzer
 */
@Plugin(type = CodeCompleterPlugin.class, name = "Groovy",
	priority = Priority.HIGH)
public class GroovyCodeCompleter extends AbstractCodeCompleterPlugin {

	private static final List<String> KEYWORDS = Arrays.asList("as", "assert",
		"boolean", "break", "byte", "case", "catch", "char", "class", "continue",
		"def", "default", "do", "double", "else", "enum", "extends", "false",
		"final", "finally", "float", "for", "if", "implements", "import", "in",
		"instanceof", "int", "interface", "long", "new", "null", "private",
		"protected", "public", "return", "short", "static", "super", "switch",
		"this", "throw", "throws", "trait", "true", "try", "var", "void",
		"while");

	/** Packages Groovy imports by default. */
	private static final List<String> DEFAULT_IMPORTS = Arrays.asList(
		"java.lang", "java.util", "java.io", "java.net", "groovy.lang",
		"groovy.util");

	/** Upper bound on class-name suggestions, which can be very numerous. */
	private static final int MAX_CLASSES = 500;

	/** How scripts see their predefined variables: as declared Java objects. */
	private static final GroovyDialect DIALECT = new GroovyDialect();

	/** An import statement being typed: "import a.b.C". */
	private static final Pattern IMPORT = Pattern.compile(
		"^\\s*import\\s+(static\\s+)?([\\w$.]*)$");

	/** The identifier (possibly empty) being typed at the caret. */
	private static final Pattern IDENTIFIER = Pattern.compile("[\\w$]*$");

	/** A number literal followed by a dot, e.g. "1." (not a member access). */
	private static final Pattern NUMBER_DOT = Pattern.compile(
		"(?<![\\w$])\\d+\\.\\s*$");

	/** A package name followed by a dot, e.g. "java.util." */
	private static final Pattern QUALIFIER = Pattern.compile(
		"((?:[a-z_][\\w$]*\\.)+)\\s*$");

	public GroovyCodeCompleter() {
		// Warm the class index in the background, as completion needs it.
		new Thread(ClassIndex::ensureCache, "Groovy-ClassIndex-warmup").start();
	}

	@Override
	public CompletionResult complete(final CompletionRequest request) {
		final String line = request.lineToCaret();
		final int caret = request.offset();
		// NB: Lines starting with '#' are SciJava script parameters (or shebangs).
		if (line.trim().startsWith("#")) return CompletionResult.EMPTY;

		final Matcher imp = IMPORT.matcher(line);
		if (imp.find()) {
			if (imp.group(1) != null) return CompletionResult.EMPTY; // static
			final String token = imp.group(2);
			return new CompletionResult(importCompletions(token), caret - token
				.length());
		}

		final Matcher id = IDENTIFIER.matcher(line);
		id.find();
		final String prefix = id.group();
		final int replaceStart = caret - prefix.length();
		final String before = line.substring(0, line.length() - prefix.length());
		final boolean member = before.trim().endsWith(".") && !before.trim()
			.endsWith("..") && !NUMBER_DOT.matcher(before).find();
		if (!member && prefix.isEmpty()) return CompletionResult.EMPTY;
		if (!member && Character.isDigit(prefix.charAt(0))) {
			return CompletionResult.EMPTY;
		}

		final Map<String, Class<?>> predefined = predefinedVariables(request);
		final ScriptDocument doc = ScriptDocument.of(request.text().substring(0,
			replaceStart), predefined, DIALECT, null);
		final GroovyAnalysis analysis = analyze(doc, predefined, line.substring(0,
			line.length() - prefix.length()));
		if (analysis == null) return CompletionResult.EMPTY; // string or comment

		final List<Completion> completions = new ArrayList<>();
		if (member) {
			completions.addAll(memberCompletions(analysis, before, prefix));
		}
		else {
			final Map<String, ClassNode> vars = analysis.variablesAtCaret();
			completions.addAll(variableCompletions(vars, doc.parameters(),
				prefix));
			completions.addAll(keywordCompletions(prefix));
			completions.addAll(classCompletions(request.text(), prefix));
		}
		return new CompletionResult(completions, replaceStart, choices(analysis,
			doc.parameters()));
	}

	@Override
	public SignatureHelp signatureHelp(final CompletionRequest request) {
		final String line = request.lineToCaret();
		if (line.trim().startsWith("#")) return SignatureHelp.NONE;
		// NB: The argument being typed, if a name, is replaced by the marker.
		final Matcher id = IDENTIFIER.matcher(line);
		id.find();
		final String prefix = id.group();
		final int start = request.offset() - prefix.length();
		final Map<String, Class<?>> predefined = predefinedVariables(request);
		final GroovyAnalysis analysis = analyze(ScriptDocument.of(request.text()
			.substring(0, start), predefined, DIALECT, null), predefined, line
				.substring(0, line.length() - prefix.length()));
		if (analysis == null) return SignatureHelp.NONE; // string or comment
		final GroovyAnalysis.Call call = analysis.callAtCaret();
		if (call == null) return SignatureHelp.NONE;
		final List<Completion> callables = callables(analysis, call);
		if (callables.isEmpty()) return SignatureHelp.NONE;
		// NB: Reflection lists methods in no particular order: sort them by
		// their parameter types, so that equally good fits come out the same.
		callables.sort(Comparator.comparing(c -> c.parameters().stream().map(
			p -> String.valueOf(p.type())).collect(Collectors.joining(","))));

		// The types of the arguments before the caret's (which is being typed).
		final List<String> argTypes = new ArrayList<>();
		for (int i = 0; i < call.arguments.size(); i++) {
			argTypes.add(i == call.active ? null : analysis.typeName(call.arguments
				.get(i)));
		}
		final ClassLoader loader = loader();
		return new SignatureHelp(Signatures.rateAll(callables, argTypes, (a,
			p) -> Signatures.fit(a, p, loader)), call.active, Signatures.callStart(
				request.text(), request.offset()));
	}

	// -- Helper methods --

	/**
	 * Analyzes the script up to the caret. If it does not parse (e.g. due to a
	 * syntax error above the caret), falls back to the caret's line alone.
	 *
	 * @return The analysis, or null if the caret is in a string or comment.
	 */
	private GroovyAnalysis analyze(final ScriptDocument doc,
		final Map<String, Class<?>> predefined, final String lineBefore)
	{
		final String code = GroovyAnalysis.prepare(doc);
		if (code == null) return null;
		final GroovyAnalysis analysis = GroovyAnalysis.of(code, loader());
		if (analysis.isParsed()) return analysis;
		final String lineCode = GroovyAnalysis.prepare(ScriptDocument.of(
			lineBefore, predefined, DIALECT, null));
		return lineCode == null ? analysis : GroovyAnalysis.of(lineCode, loader());
	}

	private List<Completion> memberCompletions(final GroovyAnalysis analysis,
		final String before, final String prefix)
	{
		final Expression receiver = analysis.receiverAtCaret();
		final ClassNode type = GroovyAnalysis.typeOf(receiver);
		if (type == null) {
			// Perhaps a package name, e.g. "java.util."
			final Matcher q = QUALIFIER.matcher(before);
			if (q.find()) return packageMemberCompletions(q.group(1), prefix);
			return Collections.emptyList();
		}
		final boolean staticOnly = receiver instanceof ClassExpression;
		if (type.redirect().isPrimaryClassNode()) {
			return scriptClassMembers(analysis, type.redirect(), prefix, staticOnly);
		}
		final Class<?> c = Types.box(analysis.toClass(type));
		if (c == null) return Collections.emptyList();
		final List<Completion> out = sorted(Completions.membersOf(c, prefix,
			staticOnly));
		if (!staticOnly) out.addAll(gdkCompletions(c, prefix));
		return out;
	}

	/** Members of a class defined in the script itself. */
	private List<Completion> scriptClassMembers(final GroovyAnalysis analysis,
		final ClassNode type, final String prefix, final boolean staticOnly)
	{
		final List<Completion> out = new ArrayList<>();
		for (final PropertyNode p : type.getProperties()) {
			if (staticOnly && !p.isStatic() || !matches(p.getName(), prefix)) {
				continue;
			}
			out.add(Completion.builder(p.getName()).kind(Completion.Kind.FIELD)
				.returnType(p.getType().toString(false)).summary(p.getType().toString(
					false) + " " + p.getName()).build());
		}
		for (final FieldNode f : type.getFields()) {
			if (!f.isPublic() || f.isSynthetic() || staticOnly && !f.isStatic() ||
				!matches(f.getName(), prefix) || type.getProperty(f
					.getName()) != null) continue;
			out.add(Completion.builder(f.getName()).kind(Completion.Kind.FIELD)
				.returnType(f.getType().toString(false)).build());
		}
		for (final MethodNode m : type.getMethods()) {
			if (!m.isPublic() || m.isSynthetic() || staticOnly && !m.isStatic() ||
				!matches(m.getName(), prefix) || m.getName().contains("$")) continue;
			final List<Completion.Parameter> params = new ArrayList<>();
			for (final org.codehaus.groovy.ast.Parameter p : m.getParameters()) {
				params.add(new Completion.Parameter(p.getName(), p.getType()
					.toString(false)));
			}
			out.add(Completion.builder(m.getName()).kind(Completion.Kind.METHOD)
				.parameters(params).returnType(m.getReturnType().toString(false))
				.summary(m.getTypeDescriptor()).build());
		}
		final List<Completion> sorted = sorted(out);
		// Plus what it inherits from (compiled) superclasses.
		final Class<?> superclass = analysis.toClass(type.getSuperClass());
		if (superclass != null && superclass != Object.class) {
			sorted.addAll(sorted(Completions.membersOf(superclass, prefix,
				staticOnly)));
		}
		return sorted;
	}

	/** Classes and subpackages of a package, e.g. after "java.util." */
	private List<Completion> packageMemberCompletions(final String qualifier,
		final String prefix)
	{
		if (!ClassIndex.isCacheReady()) return Collections.emptyList();
		final String pkg = qualifier.substring(0, qualifier.length() - 1);
		return ClassIndex.findClassNamesForPackage(pkg) //
			.filter(s -> s.lastIndexOf('.') == pkg.length()) //
			.map(s -> s.substring(pkg.length() + 1)) //
			.filter(s -> matches(s, prefix) && !s.contains("$")) //
			.sorted().limit(MAX_CLASSES) //
			.map(s -> Completion.builder(s).kind(Completion.Kind.CLASS).summary(
				pkg + "." + s).build()) //
			.collect(Collectors.toList());
	}

	/**
	 * The GDK methods Groovy adds to {@code c}, e.g. {@code each} and
	 * {@code collect} for collections.
	 */
	private static List<Completion> gdkCompletions(final Class<?> c,
		final String prefix)
	{
		final Map<String, Completion> out = new LinkedHashMap<>();
		for (final MetaMethod m : GroovySystem.getMetaClassRegistry()
			.getMetaClass(c).getMetaMethods())
		{
			if (m.isStatic() || !matches(m.getName(), prefix)) continue;
			final List<Completion.Parameter> params = new ArrayList<>();
			final Class<?>[] types = m.getNativeParameterTypes();
			for (int i = 0; i < types.length; i++) {
				params.add(new Completion.Parameter("arg" + i, typeName(types[i])));
			}
			final String signature = typeName(m.getReturnType()) + " " + m
				.getName() + params.stream().map(Completion.Parameter::type).collect(
					Collectors.joining(", ", "(", ")"));
			// NB: Groovy lists some methods more than once.
			out.putIfAbsent(signature, Completion.builder(m.getName()).kind(
				Completion.Kind.METHOD).parameters(params).returnType(typeName(m
					.getReturnType())).declaringClass(m.getDeclaringClass().getName())
				.summary("GDK: " + signature).build());
		}
		return sorted(new ArrayList<>(out.values()));
	}

	private static List<Completion> variableCompletions(
		final Map<String, ClassNode> vars, final Map<String, String> predefined,
		final String prefix)
	{
		final Set<String> names = new HashSet<>();
		final List<Completion> out = new ArrayList<>();
		// Innermost (most recently declared) variables first.
		final List<String> order = new ArrayList<>(vars.keySet());
		Collections.reverse(order);
		order.addAll(predefined.keySet());
		for (final String name : order) {
			if (!matches(name, prefix) || name.equals(prefix) || !names.add(name)) {
				continue;
			}
			final ClassNode type = vars.get(name);
			final String typeName = type != null ? type.toString(false) : predefined
				.get(name);
			out.add(Completion.builder(name).kind(Completion.Kind.VARIABLE).summary(
				typeName == null ? name : typeName + " " + name).build());
		}
		return out;
	}

	private static List<Completion> keywordCompletions(final String prefix) {
		return KEYWORDS.stream().filter(k -> k.startsWith(prefix) && !k.equals(
			prefix)).map(k -> Completion.builder(k).kind(Completion.Kind.KEYWORD)
				.build()).collect(Collectors.toList());
	}

	/** Class names starting with the prefix, imported on acceptance. */
	private static List<Completion> classCompletions(final String text,
		final String prefix)
	{
		if (!Character.isUpperCase(prefix.charAt(0)) || !ClassIndex
			.isCacheReady()) return Collections.emptyList();
		final List<String> classNames = ClassIndex.findSimpleClassNamesStartingWith(
			prefix);
		// Closest matches first: shortest simple name, then alphabetical.
		classNames.sort(Comparator.comparingInt((String cn) -> cn.length() - cn
			.lastIndexOf('.')).thenComparing(Comparator.naturalOrder()));
		final List<Completion> out = new ArrayList<>();
		for (final String className : classNames) {
			if (className.contains("$")) continue;
			final String simpleName = className.substring(className.lastIndexOf(
				'.') + 1);
			final Completion.Builder b = Completion.builder(simpleName).kind(
				Completion.Kind.CLASS).summary(className);
			final TextEdit edit = importEdit(text, className);
			if (edit != null) b.additionalEdits(Collections.singletonList(edit));
			out.add(b.build());
			if (out.size() >= MAX_CLASSES) break;
		}
		return out;
	}

	/**
	 * An edit importing the given class, after the script's last import (or at
	 * the top), or null if no import is needed.
	 */
	static TextEdit importEdit(final String text, final String className) {
		final String pkg = className.substring(0, Math.max(0, className
			.lastIndexOf('.')));
		if (DEFAULT_IMPORTS.contains(pkg) || className.equals(
			"java.math.BigDecimal") || className.equals("java.math.BigInteger"))
		{
			return null;
		}
		final Pattern imports = Pattern.compile("^\\s*import\\s+(?:static\\s+)?" +
			"([\\w$.]+?)(\\.\\*)?\\s*(?:as\\s+\\w+\\s*)?;?\\s*$", Pattern.MULTILINE);
		final Matcher m = imports.matcher(text);
		int insertAt = 0;
		while (m.find()) {
			final String imported = m.group(1);
			if (imported.equals(className) || m.group(2) != null && imported.equals(
				pkg)) return null;
			insertAt = m.end() + 1;
		}
		if (insertAt == 0 && text.startsWith("#!")) {
			insertAt = text.indexOf('\n') + 1; // NB: After the shebang.
		}
		insertAt = Math.min(insertAt, text.length());
		return TextEdit.insert(insertAt, "import " + className + "\n");
	}

	private static List<Completion> importCompletions(final String token) {
		if (token.isEmpty() || !ClassIndex.isCacheReady()) {
			return Collections.emptyList();
		}
		return ClassIndex.findClassNamesStartingWith(token) //
			.filter(s -> !s.contains("$")) //
			.sorted().limit(MAX_CLASSES) //
			.map(s -> Completion.builder(s).kind(Completion.Kind.IMPORT).build()) //
			.collect(Collectors.toList());
	}

	/** Suggests in-scope variables whose type suits a parameter. */
	private ParameterChoices choices(final GroovyAnalysis analysis,
		final Map<String, String> predefined)
	{
		return parameter -> {
			final Class<?> target = Types.box(GroovyAnalysis.load(parameter.type(),
				loader()));
			if (target == null || target == Object.class) {
				return Collections.emptyList();
			}
			final Map<String, ClassNode> vars = analysis.variablesAtCaret();
			final List<Completion> out = new ArrayList<>();
			for (final Map.Entry<String, ClassNode> var : vars.entrySet()) {
				final Class<?> c = Types.box(analysis.toClass(var.getValue()));
				if (c != null && target.isAssignableFrom(c)) {
					out.add(Completion.of(var.getKey()));
				}
			}
			for (final Map.Entry<String, String> var : predefined.entrySet()) {
				if (vars.containsKey(var.getKey())) continue;
				final Class<?> c = Types.box(GroovyAnalysis.load(var.getValue(), loader()));
				if (c != null && target.isAssignableFrom(c)) {
					out.add(Completion.of(var.getKey()));
				}
			}
			return out;
		};
	}

	/** The signatures the call may be calling: methods or constructors. */
	private List<Completion> callables(final GroovyAnalysis analysis,
		final GroovyAnalysis.Call call)
	{
		final List<Completion> out = new ArrayList<>();
		if (call.expression instanceof ConstructorCallExpression) {
			final ClassNode type = call.expression.getType().redirect();
			final Class<?> c = analysis.toClass(type);
			if (c != null) {
				for (final Constructor<?> ctor : c.getConstructors()) {
					out.add(constructor(ctor));
				}
			}
			else if (type.isPrimaryClassNode()) {
				for (final ConstructorNode ctor : type.getDeclaredConstructors()) {
					out.add(methodNode(type.getNameWithoutPackage(), ctor));
				}
			}
			return out;
		}
		if (call.expression instanceof StaticMethodCallExpression) {
			final StaticMethodCallExpression e =
				(StaticMethodCallExpression) call.expression;
			return named(Completions.membersOf(analysis.toClass(e.getOwnerType()),
				e.getMethod(), true), e.getMethod());
		}
		final MethodCallExpression e = (MethodCallExpression) call.expression;
		final String name = e.getMethodAsString();
		if (name == null) return out;
		if (e.isImplicitThis()) {
			// A function defined in the script, or a GDK method such as println.
			for (final MethodNode m : analysis.scriptMethods(name)) {
				out.add(methodNode(name, m));
			}
			out.addAll(named(gdkCompletions(Object.class, name), name));
			return out;
		}
		final Expression receiver = e.getObjectExpression();
		final ClassNode type = GroovyAnalysis.typeOf(receiver);
		if (type == null) return out;
		final boolean staticOnly = receiver instanceof ClassExpression;
		if (type.redirect().isPrimaryClassNode()) {
			return named(scriptClassMembers(analysis, type.redirect(), name,
				staticOnly), name);
		}
		final Class<?> c = Types.box(analysis.toClass(type));
		if (c == null) return out;
		out.addAll(named(Completions.membersOf(c, name, staticOnly), name));
		if (!staticOnly) out.addAll(named(gdkCompletions(c, name), name));
		return out;
	}

	/** The callables among {@code completions} with exactly the given name. */
	private static List<Completion> named(final List<Completion> completions,
		final String name)
	{
		final List<Completion> out = new ArrayList<>();
		for (final Completion c : completions) {
			if (c.kind() == Completion.Kind.METHOD && c.insertionText().equals(
				name)) out.add(c);
		}
		return out;
	}

	private static Completion constructor(final Constructor<?> ctor) {
		final List<Completion.Parameter> params = new ArrayList<>();
		for (final java.lang.reflect.Parameter p : ctor.getParameters()) {
			params.add(new Completion.Parameter(p.getName(), typeName(p
				.getType())));
		}
		return Completion.builder(ctor.getDeclaringClass().getSimpleName()).kind(
			Completion.Kind.METHOD).parameters(params).returnType(typeName(ctor
				.getDeclaringClass())).build();
	}

	/** A callable defined in the script itself. */
	private static Completion methodNode(final String name, final MethodNode m) {
		final List<Completion.Parameter> params = new ArrayList<>();
		for (final org.codehaus.groovy.ast.Parameter p : m.getParameters()) {
			params.add(new Completion.Parameter(p.getName(), p.isDynamicTyped()
				? null : p.getType().getName()));
		}
		return Completion.builder(name).kind(Completion.Kind.METHOD).parameters(
			params).returnType(m.getReturnType().toString(false)).build();
	}

	/**
	 * Gets the variables defined before the script runs: its parameters (e.g.
	 * {@code #@ String name}) and, in a live interpreter, the engine's bindings.
	 *
	 * @return Map of variable name to type.
	 */
	private Map<String, Class<?>> predefinedVariables(
		final CompletionRequest request)
	{
		final Map<String, Class<?>> vars = new LinkedHashMap<>();
		final Context context = getContext();
		final CodeCompletionService service = context == null ? null : context
			.getService(CodeCompletionService.class);
		if (service != null) {
			service.scriptParameters(request.text()).forEach((name, type) -> {
				if (isIdentifier(name)) vars.put(name, type);
			});
		}
		final ScriptContext scriptContext = request.context();
		final Bindings bindings = scriptContext == null ? null : scriptContext
			.getBindings(ScriptContext.ENGINE_SCOPE);
		if (bindings != null) {
			for (final Map.Entry<String, Object> entry : bindings.entrySet()) {
				final String name = entry.getKey();
				if (!isIdentifier(name) || name.startsWith("__")) continue;
				final Object value = entry.getValue();
				vars.put(name, value == null ? Object.class : publicType(value
					.getClass()));
			}
		}
		return vars;
	}

	private static ClassLoader loader() {
		final ClassLoader loader = Thread.currentThread().getContextClassLoader();
		return loader != null ? loader : GroovyCodeCompleter.class
			.getClassLoader();
	}

	private static boolean isIdentifier(final String name) {
		if (name == null || name.isEmpty() || !Character.isJavaIdentifierStart(
			name.charAt(0))) return false;
		for (int i = 1; i < name.length(); i++) {
			if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
		}
		return !KEYWORDS.contains(name);
	}

	private static boolean matches(final String name, final String prefix) {
		return name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(
			Locale.ROOT));
	}

	/** Sorts completions by name, case-insensitively (stably). */
	private static List<Completion> sorted(final List<Completion> completions) {
		final List<Completion> out = new ArrayList<>(completions);
		out.sort(Comparator.comparing(c -> c.insertionText().toLowerCase(
			Locale.ROOT)));
		return out;
	}

	/**
	 * Gets the name by which Groovy code can refer to a class, e.g.
	 * {@code java.util.List} or {@code int[]}; or {@code Object} if it has none
	 * (e.g. an anonymous class).
	 */
	/** Gets the name by which Groovy code refers to {@code c}. */
	static String typeName(final Class<?> c) {
		final String name = c == null ? null : c.getCanonicalName();
		return name == null ? "Object" : name;
	}

	@Override
	public ScriptDialect dialect() {
		return DIALECT;
	}

	/**
	 * Gets the most specific public class or interface of {@code c}, by which
	 * code can refer to its instances.
	 */
	private static Class<?> publicType(final Class<?> c) {
		for (Class<?> t = c; t != null; t = t.getSuperclass()) {
			if (isAccessible(t)) return t;
		}
		for (final Class<?> i : c.getInterfaces()) {
			if (isAccessible(i)) return i;
		}
		return Object.class;
	}

	private static boolean isAccessible(final Class<?> c) {
		for (Class<?> t = c; t != null; t = t.getEnclosingClass()) {
			if (!Modifier.isPublic(t.getModifiers())) return false;
		}
		return !c.isAnonymousClass() && !c.isLocalClass();
	}
}
