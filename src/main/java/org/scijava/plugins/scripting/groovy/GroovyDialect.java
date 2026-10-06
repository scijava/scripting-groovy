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

import org.scijava.code.api.ScriptDialect;

/**
 * How Groovy scripts see their predefined variables (script parameters,
 * interpreter bindings): as the Java objects themselves, declared e.g.
 * {@code net.imglib2.img.Img image}. Lines starting with {@code #} (script
 * parameters, shebangs) are not Groovy.
 *
 * @author Gabriel Selzer
 */
class GroovyDialect implements ScriptDialect {

	@Override
	public String runtimeType(final Class<?> type) {
		return GroovyCodeCompleter.typeName(type);
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
