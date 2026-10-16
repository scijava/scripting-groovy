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

import org.scijava.code.lsp.ScriptDialect;
import org.scijava.plugin.Plugin;
import org.scijava.plugins.scripting.groovy.GroovyScriptLanguage;
import org.scijava.script.ScriptLanguage;

/**
 * Turns Groovy scripts into plain Groovy, for {@code groovy} language servers
 * (e.g. scijava-code-lsp-groovy's): their parameters declared as Java objects,
 * their {@code #@} lines blanked.
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
	public String parameterType(final Class<?> type) {
		// NB: Anonymous and local classes have no name to write.
		final String name = type.getCanonicalName();
		return name == null ? "Object" : name;
	}

	@Override
	public String declare(final String name, final String type) {
		return type + " " + name;
	}

	@Override
	public boolean blanks(final String line) {
		return line.trim().startsWith("#");
	}
}
