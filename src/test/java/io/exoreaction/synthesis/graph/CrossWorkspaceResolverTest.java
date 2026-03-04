package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.ClientRepoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CrossWorkspaceResolver}.
 */
class CrossWorkspaceResolverTest {

    private CrossWorkspaceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CrossWorkspaceResolver();
    }

    // ---- Helpers ----

    private SynthesisConfig buildConfig(List<SubWorkspaceConfig> subs, String crossCompanyLinks) {
        SynthesisConfig config = new SynthesisConfig();
        config.setSubWorkspaces(subs);
        config.getWorkspace().setCrossCompanyLinks(crossCompanyLinks);
        return config;
    }

    private SubWorkspaceConfig sub(String name, String path, String srcPath) {
        SubWorkspaceConfig s = new SubWorkspaceConfig(name, path);
        s.setSrcPath(srcPath);
        return s;
    }

    private SubWorkspaceConfig subWithClientRepo(String name, String path, String srcPath,
                                                  String docsSub, String srcSub) {
        SubWorkspaceConfig s = sub(name, path, srcPath);
        ClientRepoConfig cr = new ClientRepoConfig();
        cr.setDocs(docsSub);
        cr.setSrc(srcSub);
        s.setClientRepos(List.of(cr));
        return s;
    }

    // ---- resolve() tests ----

    @Test
    void same_company_top_level_resolves() {
        SynthesisConfig config = buildConfig(
                List.of(sub("eXOReaction", "eXOReaction", "/src/exoreaction")),
                "explicit_only");

        List<CrossWorkspaceResolver.CrossWorkspaceLink> links =
                resolver.resolve(config, "eXOReaction");

        assertEquals(1, links.size());
        assertEquals("eXOReaction", links.get(0).docsRelPath());
        assertEquals("/src/exoreaction", links.get(0).srcAbsPath());
        assertEquals("eXOReaction", links.get(0).companyId());
        assertEquals(0.8, links.get(0).confidence(), 0.001);
    }

    @Test
    void nested_docs_path_resolves_to_company_src() {
        SynthesisConfig config = buildConfig(
                List.of(sub("eXOReaction", "eXOReaction", "/src/exoreaction")),
                "explicit_only");

        List<CrossWorkspaceResolver.CrossWorkspaceLink> links =
                resolver.resolve(config, "eXOReaction/marketing");

        assertEquals(1, links.size());
        assertEquals("/src/exoreaction", links.get(0).srcAbsPath());
        assertEquals(0.8, links.get(0).confidence(), 0.001);
    }

    @Test
    void client_repo_resolves_to_client_src() {
        SubWorkspaceConfig sub = subWithClientRepo(
                "eXOReaction", "eXOReaction", "/src/exoreaction",
                "eXOReaction/clients/Elprint", "/src/elprint");
        SynthesisConfig config = buildConfig(List.of(sub), "explicit_only");

        List<CrossWorkspaceResolver.CrossWorkspaceLink> links =
                resolver.resolve(config, "eXOReaction/clients/Elprint");

        assertEquals(1, links.size());
        assertEquals("/src/elprint", links.get(0).srcAbsPath());
        assertEquals(0.9, links.get(0).confidence(), 0.001);
    }

    @Test
    void no_src_path_declared_returns_empty() {
        SubWorkspaceConfig sub = new SubWorkspaceConfig("eXOReaction", "eXOReaction");
        // srcPath left as default empty string
        SynthesisConfig config = buildConfig(List.of(sub), "explicit_only");

        List<CrossWorkspaceResolver.CrossWorkspaceLink> links =
                resolver.resolve(config, "eXOReaction");

        assertTrue(links.isEmpty());
    }

    // ---- canLink() tests ----

    @Test
    void cross_company_blocked_by_explicit_only() {
        SynthesisConfig config = buildConfig(
                List.of(
                        sub("eXOReaction", "eXOReaction", "/src/exoreaction"),
                        sub("Quadim",      "Quadim",      "/src/quadim")),
                "explicit_only");

        assertFalse(resolver.canLink(config, "eXOReaction", "Quadim"));
    }

    @Test
    void cross_company_allowed_with_allow_all() {
        SynthesisConfig config = buildConfig(
                List.of(
                        sub("eXOReaction", "eXOReaction", "/src/exoreaction"),
                        sub("Quadim",      "Quadim",      "/src/quadim")),
                "allow_all");

        assertTrue(resolver.canLink(config, "eXOReaction", "Quadim"));
    }

    @Test
    void same_company_can_link_always_true() {
        SynthesisConfig config = buildConfig(
                List.of(sub("eXOReaction", "eXOReaction", "/src/exoreaction")),
                "explicit_only");

        assertTrue(resolver.canLink(config, "eXOReaction", "eXOReaction/marketing"));
    }
}
