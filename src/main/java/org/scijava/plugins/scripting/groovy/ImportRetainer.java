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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.scijava.util.ClassUtils;
import org.scijava.util.Types;

/**
 * Helper class for remembering previous import statements across eval calls.
 *
 * @author Curtis Rueden
 * @see org.codehaus.groovy.control.customizers.ImportCustomizer
 */
public class ImportRetainer extends CompilationCustomizer {

	private final List<ImportNode> priorImports = new ArrayList<>();
	private final List<ImportNode> priorStarImports = new ArrayList<>();
	private final List<ImportNode> priorStaticImports = new ArrayList<>();
	private final List<ImportNode> priorStaticStarImports = new ArrayList<>();

	public ImportRetainer() {
		super(CompilePhase.CONVERSION);
	}

	public void retainImport(ImportNode node) { priorImports.add(node); }
	public void retainStarImport(ImportNode node) { priorStarImports.add(node); }
	public void retainStaticImport(ImportNode node) { priorStaticImports.add(node); }
	public void retainStaticStarImport(ImportNode node) { priorStaticStarImports.add(node); }

	@SuppressWarnings("unchecked")
	private <T> T fieldValue(String name, Object instance) {
		return (T) ClassUtils.getValue(Types.field(ModuleNode.class, name), instance);
	}

	@Override
	public void call(final SourceUnit source, final GeneratorContext context, final ClassNode classNode) {
		ModuleNode ast = source.getAST();

		// HACK: Extract important private fields from ModuleNode class.
		// We need to add things to them, and this is the only way.
		final Map<String, ImportNode> imports = fieldValue("imports", ast);
		final List<ImportNode> starImports = fieldValue("starImports", ast);
		final Map<String, ImportNode> staticImports = fieldValue("staticImports", ast);
		final Map<String, ImportNode> staticStarImports = fieldValue("staticStarImports", ast);

		// NB: Copied from ImportCustomizer.call(...).
		// GROOVY-8399: apply import customizations only once per module
		if (!classNode.getName().equals(ast.getMainClassName())) return;
		ast.addImport(null, classNode);

		// NB: Adapted from ModuleNode.addImport(...).
		for (final ImportNode node : priorImports) {
			imports.put(node.getAlias(), node);
			storeLastAddedImportNode(ast, node);
		}
		// NB: Adapted from ModuleNode.addStarImport(...).
		for (final ImportNode node : priorStarImports) {
			starImports.add(node);
			storeLastAddedImportNode(ast, node);
		}
		// NB: Adapted from ModuleNode.addStaticImport(...).
		for (final ImportNode node : priorStaticImports) {
			final String alias = node.getAlias();
			ImportNode prev = staticImports.put(alias, node);
			if (prev != null) {
				staticImports.put(prev.toString(), prev);
				staticImports.put(alias, staticImports.remove(alias));
			}
			storeLastAddedImportNode(ast, node);
		}
		// NB: Adapted from ModuleNode.addStaticStarImport(...).
		for (final ImportNode node : priorStaticStarImports) {
			staticStarImports.put(node.getAlias(), node);
			storeLastAddedImportNode(ast, node);
		}
	}

	// NB: Copied from ModuleNode.storeLastAddedImportNode(...).
	// This method only exists as a workaround for GROOVY-6094
	// In order to keep binary compatibility
	private void storeLastAddedImportNode(final ModuleNode ast, final ImportNode node) {
		if (ast.getNodeMetaData(ImportNode.class) == ImportNode.class) {
			ast.putNodeMetaData(ImportNode.class, node);
		}
	}
}
