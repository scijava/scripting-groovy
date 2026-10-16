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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.scijava.code.lsp.ScriptDialect;
import org.scijava.plugin.Plugin;
import org.scijava.plugins.scripting.groovy.GroovyScriptLanguage;
import org.scijava.script.ScriptLanguage;

/**
 * Describes how to turn Groovy scripts into plain Groovy code, for
 * {@code groovy} language servers (e.g. scijava-code-lsp-groovy's).
 *
 * @author Gabriel Selzer
 */
@Plugin(type = ScriptDialect.class, name = "Groovy")
public class GroovyDialect implements ScriptDialect {

	@Override
	public boolean supports(final ScriptLanguage language) {
		return language instanceof GroovyScriptLanguage;
	}

	@Override
	public String languageId() {
		return "groovy";
	}

	@Override
	public Header header(final Map<String, Class<?>> parameters) {
		// Declare the types of each parameter for type reasoning; Groovy gets
		// the very Java objects, named in full, so nothing needs importing
		final List<String> declarations = new ArrayList<>();
		parameters.forEach((name, type) -> declarations.add(parameterType(
			type) + " " + name));
		// Combine into a Header
		return new Header(String.join("; ", declarations), Collections
			.emptySet());
	}

	/**
	 * @param type the Java type (of a SciJava Script parameter) declared in the
	 *          script
	 * @return the Groovy type of the object given to the script: the Java type
	 *         itself, by its full name (or {@code Object} for anonymous and
	 *         local classes, which have no name to write)
	 */
	static String parameterType(final Class<?> type) {
		final String name = type.getCanonicalName();
		return name == null ? "Object" : name;
	}
}
