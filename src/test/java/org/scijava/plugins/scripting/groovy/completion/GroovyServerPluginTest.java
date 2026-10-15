package org.scijava.plugins.scripting.groovy.completion;

import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.code.lsp.Environment;
import org.scijava.code.lsp.LanguageServerService;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptService;

import static org.junit.Assert.assertTrue;

/**
 * Tests {@link GroovyServerPlugin}
 *
 * @author Gabriel Selzer
 */
public class GroovyServerPluginTest {

    private Context context;
    private ScriptLanguage groovy;
    private LanguageServerService servers;

    @Before
    public void setUp() {
        context = new Context(ScriptService.class, LanguageServerService.class);
        groovy = context.service(ScriptService.class).getLanguageByName("Groovy");
        servers = context.service(LanguageServerService.class);
    }

    @After
    public void tearDown() {
        context.dispose();
    }

    @Test
    public void testServerIsDiscovered() {
        assertTrue(servers.supports(groovy));
    }

    @Test
    public void testServerLaunch() {
        LanguageServer server = servers.launch(
            groovy.getLanguageName().toLowerCase(),
            Environment.NONE
        );
        assertTrue(server instanceof GroovyLanguageServer);
    }
}

