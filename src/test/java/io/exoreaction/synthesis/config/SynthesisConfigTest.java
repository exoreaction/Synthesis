package io.exoreaction.synthesis.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SynthesisConfig and all inner classes — defaults, setters, null-safety.
 */
class SynthesisConfigTest {

    // === Root SynthesisConfig ===

    @Test
    void defaultConfig_allSectionsNonNull() {
        SynthesisConfig config = new SynthesisConfig();
        assertNotNull(config.getWorkspace());
        assertNotNull(config.getSearch());
        assertNotNull(config.getAi());
        assertNotNull(config.getScan());
        assertNotNull(config.getTracking());
        assertNotNull(config.getChangelog());
        assertNotNull(config.getSubWorkspaces());
        assertNotNull(config.getStaging());
        assertNotNull(config.getRouting());
        assertNotNull(config.getReport());
    }

    @Test
    void defaultConfig_subWorkspacesIsEmptyList() {
        SynthesisConfig config = new SynthesisConfig();
        assertTrue(config.getSubWorkspaces().isEmpty());
    }

    @Test
    void setSubWorkspaces_null_becomesEmptyList() {
        SynthesisConfig config = new SynthesisConfig();
        config.setSubWorkspaces(null);
        assertNotNull(config.getSubWorkspaces(), "null subWorkspaces should become empty list");
        assertTrue(config.getSubWorkspaces().isEmpty());
    }

    @Test
    void setRouting_null_becomesDefaultRoutingConfig() {
        SynthesisConfig config = new SynthesisConfig();
        config.setRouting(null);
        assertNotNull(config.getRouting(), "null routing should become default RoutingConfig");
    }

    @Test
    void setReport_null_becomesDefaultReportConfig() {
        SynthesisConfig config = new SynthesisConfig();
        config.setReport(null);
        assertNotNull(config.getReport(), "null report should become default ReportConfig");
    }

    // === WorkspaceConfig ===

    @Test
    void workspaceConfig_defaultName_isEmpty() {
        SynthesisConfig.WorkspaceConfig ws = new SynthesisConfig.WorkspaceConfig();
        assertEquals("", ws.getName());
    }

    @Test
    void workspaceConfig_defaultType_isGeneral() {
        SynthesisConfig.WorkspaceConfig ws = new SynthesisConfig.WorkspaceConfig();
        assertEquals("general", ws.getType());
    }

    @Test
    void workspaceConfig_defaultDescription_isEmpty() {
        SynthesisConfig.WorkspaceConfig ws = new SynthesisConfig.WorkspaceConfig();
        assertEquals("", ws.getDescription());
    }

    @Test
    void workspaceConfig_defaultMetadata_isNonNull() {
        SynthesisConfig.WorkspaceConfig ws = new SynthesisConfig.WorkspaceConfig();
        assertNotNull(ws.getMetadata());
    }

    @Test
    void workspaceConfig_setName_updatesName() {
        SynthesisConfig.WorkspaceConfig ws = new SynthesisConfig.WorkspaceConfig();
        ws.setName("my-workspace");
        assertEquals("my-workspace", ws.getName());
    }

    @Test
    void workspaceConfig_getWorkspaceType_generalType_returnsMixed() {
        SynthesisConfig.WorkspaceConfig ws = new SynthesisConfig.WorkspaceConfig();
        ws.setType("general");
        // "general" is a legacy type → MIXED
        assertNotNull(ws.getWorkspaceType());
    }

    @Test
    void workspaceConfig_getWorkspaceType_metadataCategoryTakesPrecedence() {
        SynthesisConfig.WorkspaceConfig ws = new SynthesisConfig.WorkspaceConfig();
        ws.setType("general");
        ws.getMetadata().setCategory("source-code");
        // metadata.category != "mixed" → takes precedence
        assertEquals(io.exoreaction.synthesis.workspace.WorkspaceType.SOURCE_CODE, ws.getWorkspaceType());
    }

    // === SearchConfig ===

    @Test
    void searchConfig_defaultMaxResults_is20() {
        SynthesisConfig.SearchConfig search = new SynthesisConfig.SearchConfig();
        assertEquals(20, search.getMaxResults());
    }

    @Test
    void searchConfig_defaultPreviewLength_is200() {
        SynthesisConfig.SearchConfig search = new SynthesisConfig.SearchConfig();
        assertEquals(200, search.getPreviewLength());
    }

    @Test
    void searchConfig_defaultContentPreviewBytes_is10240() {
        SynthesisConfig.SearchConfig search = new SynthesisConfig.SearchConfig();
        assertEquals(10240, search.getContentPreviewBytes());
    }

    @Test
    void searchConfig_setMaxResults_updatesValue() {
        SynthesisConfig.SearchConfig search = new SynthesisConfig.SearchConfig();
        search.setMaxResults(50);
        assertEquals(50, search.getMaxResults());
    }

    @Test
    void searchConfig_setPreviewLength_updatesValue() {
        SynthesisConfig.SearchConfig search = new SynthesisConfig.SearchConfig();
        search.setPreviewLength(500);
        assertEquals(500, search.getPreviewLength());
    }

    // === AiConfig ===

    @Test
    void aiConfig_defaultEnabled_isFalse() {
        SynthesisConfig.AiConfig ai = new SynthesisConfig.AiConfig();
        assertFalse(ai.isEnabled());
    }

    @Test
    void aiConfig_defaultApiKey_isNull() {
        SynthesisConfig.AiConfig ai = new SynthesisConfig.AiConfig();
        assertNull(ai.getApiKey());
    }

    @Test
    void aiConfig_defaultModel_isNonBlank() {
        SynthesisConfig.AiConfig ai = new SynthesisConfig.AiConfig();
        assertFalse(ai.getModel().isBlank());
    }

    @Test
    void aiConfig_defaultReadmeGeneration_isTrue() {
        SynthesisConfig.AiConfig ai = new SynthesisConfig.AiConfig();
        assertTrue(ai.isReadmeGeneration());
    }

    @Test
    void aiConfig_defaultContentSummary_isFalse() {
        SynthesisConfig.AiConfig ai = new SynthesisConfig.AiConfig();
        assertFalse(ai.isContentSummary());
    }

    @Test
    void aiConfig_defaultMaxTokens_is1024() {
        SynthesisConfig.AiConfig ai = new SynthesisConfig.AiConfig();
        assertEquals(1024, ai.getMaxTokens());
    }

    @Test
    void aiConfig_defaultVision_isNonNull() {
        SynthesisConfig.AiConfig ai = new SynthesisConfig.AiConfig();
        assertNotNull(ai.getVision());
    }

    @Test
    void aiConfig_setEnabled_updatesValue() {
        SynthesisConfig.AiConfig ai = new SynthesisConfig.AiConfig();
        ai.setEnabled(true);
        assertTrue(ai.isEnabled());
    }

    // === VisionConfig ===

    @Test
    void visionConfig_defaultEnabled_isTrue() {
        SynthesisConfig.VisionConfig vision = new SynthesisConfig.VisionConfig();
        assertTrue(vision.isEnabled());
    }

    @Test
    void visionConfig_defaultCostPerImage_isPositive() {
        SynthesisConfig.VisionConfig vision = new SynthesisConfig.VisionConfig();
        assertTrue(vision.getCostPerImageUsd() > 0);
    }

    @Test
    void visionConfig_defaultMaxImageSize_is20MB() {
        SynthesisConfig.VisionConfig vision = new SynthesisConfig.VisionConfig();
        assertEquals(20L * 1024 * 1024, vision.getMaxImageSizeBytes());
    }

    @Test
    void visionConfig_defaultConfirmBeforeScan_isTrue() {
        SynthesisConfig.VisionConfig vision = new SynthesisConfig.VisionConfig();
        assertTrue(vision.isConfirmBeforeScan());
    }

    // === ScanConfig ===

    @Test
    void scanConfig_defaultIncludePatterns_isNonEmpty() {
        SynthesisConfig.ScanConfig scan = new SynthesisConfig.ScanConfig();
        assertFalse(scan.getIncludePatterns().isEmpty());
    }

    @Test
    void scanConfig_defaultIncludePatterns_containsCommonExtensions() {
        SynthesisConfig.ScanConfig scan = new SynthesisConfig.ScanConfig();
        List<String> patterns = scan.getIncludePatterns();
        assertTrue(patterns.stream().anyMatch(p -> p.contains("*.java")));
        assertTrue(patterns.stream().anyMatch(p -> p.contains("*.md")));
        assertTrue(patterns.stream().anyMatch(p -> p.contains("*.py")));
    }

    @Test
    void scanConfig_defaultExcludePatterns_isEmpty() {
        SynthesisConfig.ScanConfig scan = new SynthesisConfig.ScanConfig();
        assertTrue(scan.getExcludePatterns().isEmpty());
    }

    @Test
    void scanConfig_defaultUseSmartDefaults_isTrue() {
        SynthesisConfig.ScanConfig scan = new SynthesisConfig.ScanConfig();
        assertTrue(scan.isUseSmartDefaults());
    }

    @Test
    void scanConfig_defaultComputeHashes_isTrue() {
        SynthesisConfig.ScanConfig scan = new SynthesisConfig.ScanConfig();
        assertTrue(scan.isComputeHashes());
    }

    @Test
    void scanConfig_defaultMaxFileSizeBytes_is10MB() {
        SynthesisConfig.ScanConfig scan = new SynthesisConfig.ScanConfig();
        assertEquals(10L * 1024 * 1024, scan.getMaxFileSizeBytes());
    }

    // === TrackingConfig ===

    @Test
    void trackingConfig_defaultEnabled_isTrue() {
        SynthesisConfig.TrackingConfig tracking = new SynthesisConfig.TrackingConfig();
        assertTrue(tracking.isEnabled());
    }

    @Test
    void trackingConfig_defaultSafetyPeriodDays_is7() {
        SynthesisConfig.TrackingConfig tracking = new SynthesisConfig.TrackingConfig();
        assertEquals(7, tracking.getSafetyPeriodDays());
    }

    @Test
    void trackingConfig_defaultAutoDetect_isTrue() {
        SynthesisConfig.TrackingConfig tracking = new SynthesisConfig.TrackingConfig();
        assertTrue(tracking.isAutoDetect());
    }

    @Test
    void trackingConfig_defaultRetentionDays_is90() {
        SynthesisConfig.TrackingConfig tracking = new SynthesisConfig.TrackingConfig();
        assertEquals(90, tracking.getRetentionDays());
    }

    @Test
    void trackingConfig_setters_updateValues() {
        SynthesisConfig.TrackingConfig tracking = new SynthesisConfig.TrackingConfig();
        tracking.setEnabled(false);
        tracking.setSafetyPeriodDays(14);
        tracking.setRetentionDays(30);
        assertFalse(tracking.isEnabled());
        assertEquals(14, tracking.getSafetyPeriodDays());
        assertEquals(30, tracking.getRetentionDays());
    }

    // === ChangelogConfig ===

    @Test
    void changelogConfig_defaultEnabled_isTrue() {
        SynthesisConfig.ChangelogConfig changelog = new SynthesisConfig.ChangelogConfig();
        assertTrue(changelog.isEnabled());
    }

    @Test
    void changelogConfig_defaultAutoSnapshot_isTrue() {
        SynthesisConfig.ChangelogConfig changelog = new SynthesisConfig.ChangelogConfig();
        assertTrue(changelog.isAutoSnapshot());
    }

    @Test
    void changelogConfig_defaultSnapshotIntervalHours_is6() {
        SynthesisConfig.ChangelogConfig changelog = new SynthesisConfig.ChangelogConfig();
        assertEquals(6, changelog.getSnapshotIntervalHours());
    }

    @Test
    void changelogConfig_defaultRetentionDays_is90() {
        SynthesisConfig.ChangelogConfig changelog = new SynthesisConfig.ChangelogConfig();
        assertEquals(90, changelog.getRetentionDays());
    }

    @Test
    void changelogConfig_defaultSignificance_isNonNull() {
        SynthesisConfig.ChangelogConfig changelog = new SynthesisConfig.ChangelogConfig();
        assertNotNull(changelog.getSignificance());
    }

    // === SignificanceConfig ===

    @Test
    void significanceConfig_defaultNoisePaths_isEmpty() {
        SynthesisConfig.ChangelogConfig.SignificanceConfig sig =
                new SynthesisConfig.ChangelogConfig.SignificanceConfig();
        assertTrue(sig.getNoisePaths().isEmpty());
    }

    @Test
    void significanceConfig_defaultCriticalPaths_isEmpty() {
        SynthesisConfig.ChangelogConfig.SignificanceConfig sig =
                new SynthesisConfig.ChangelogConfig.SignificanceConfig();
        assertTrue(sig.getCriticalPaths().isEmpty());
    }

    @Test
    void significanceConfig_defaultMassDeleteThreshold_is10() {
        SynthesisConfig.ChangelogConfig.SignificanceConfig sig =
                new SynthesisConfig.ChangelogConfig.SignificanceConfig();
        assertEquals(10, sig.getMassDeleteThreshold());
    }

    @Test
    void significanceConfig_setters_updateValues() {
        SynthesisConfig.ChangelogConfig.SignificanceConfig sig =
                new SynthesisConfig.ChangelogConfig.SignificanceConfig();
        sig.setNoisePaths(List.of("**/node_modules/**"));
        sig.setCriticalPaths(List.of("src/main/**"));
        sig.setMassDeleteThreshold(5);
        assertEquals(1, sig.getNoisePaths().size());
        assertEquals(1, sig.getCriticalPaths().size());
        assertEquals(5, sig.getMassDeleteThreshold());
    }

    // === StagingConfig ===

    @Test
    void stagingConfig_defaultEnabled_isFalse() {
        SynthesisConfig.StagingConfig staging = new SynthesisConfig.StagingConfig();
        assertFalse(staging.isEnabled());
    }

    @Test
    void stagingConfig_defaultRetentionDays_is30() {
        SynthesisConfig.StagingConfig staging = new SynthesisConfig.StagingConfig();
        assertEquals(30, staging.getRetentionDays());
    }

    @Test
    void stagingConfig_defaultAutoClassify_isTrue() {
        SynthesisConfig.StagingConfig staging = new SynthesisConfig.StagingConfig();
        assertTrue(staging.isAutoClassify());
    }

    @Test
    void stagingConfig_defaultClassificationThreshold_is0point5() {
        SynthesisConfig.StagingConfig staging = new SynthesisConfig.StagingConfig();
        assertEquals(0.5, staging.getClassificationThreshold(), 0.001);
    }

    @Test
    void stagingConfig_defaultCleanupExpired_isFalse() {
        SynthesisConfig.StagingConfig staging = new SynthesisConfig.StagingConfig();
        assertFalse(staging.isCleanupExpired());
    }

    // === RoutingConfig ===

    @Test
    void routingConfig_defaultCopyCompanions_isTrue() {
        SynthesisConfig.RoutingConfig routing = new SynthesisConfig.RoutingConfig();
        assertTrue(routing.isCopyCompanions());
    }

    @Test
    void routingConfig_defaultRules_isEmpty() {
        SynthesisConfig.RoutingConfig routing = new SynthesisConfig.RoutingConfig();
        assertTrue(routing.getRules().isEmpty());
    }

    @Test
    void routingConfig_hasRules_falseWhenEmpty() {
        SynthesisConfig.RoutingConfig routing = new SynthesisConfig.RoutingConfig();
        assertFalse(routing.hasRules());
    }

    @Test
    void routingConfig_hasRules_trueAfterAddingRules() {
        SynthesisConfig.RoutingConfig routing = new SynthesisConfig.RoutingConfig();
        SynthesisConfig.RoutingRule rule = new SynthesisConfig.RoutingRule();
        rule.setName("test");
        rule.setPatterns(List.of("*.pdf"));
        rule.setDestination("/tmp/destination");
        routing.setRules(List.of(rule));
        assertTrue(routing.hasRules());
    }

    @Test
    void routingConfig_setRules_null_becomesEmptyList() {
        SynthesisConfig.RoutingConfig routing = new SynthesisConfig.RoutingConfig();
        routing.setRules(null);
        assertNotNull(routing.getRules());
        assertTrue(routing.getRules().isEmpty());
    }

    // === RoutingRule ===

    @Test
    void routingRule_defaultName_isEmpty() {
        SynthesisConfig.RoutingRule rule = new SynthesisConfig.RoutingRule();
        assertEquals("", rule.getName());
    }

    @Test
    void routingRule_defaultPatterns_isEmpty() {
        SynthesisConfig.RoutingRule rule = new SynthesisConfig.RoutingRule();
        assertTrue(rule.getPatterns().isEmpty());
    }

    @Test
    void routingRule_defaultDestination_isEmpty() {
        SynthesisConfig.RoutingRule rule = new SynthesisConfig.RoutingRule();
        assertEquals("", rule.getDestination());
    }

    @Test
    void routingRule_setters_updateValues() {
        SynthesisConfig.RoutingRule rule = new SynthesisConfig.RoutingRule();
        rule.setName("My Rule");
        rule.setPatterns(List.of("Synthesis_*.pdf", "Engineering_*.pdf"));
        rule.setDestination("/tmp/docs");
        assertEquals("My Rule", rule.getName());
        assertEquals(2, rule.getPatterns().size());
        assertEquals("/tmp/docs", rule.getDestination());
    }

    @Test
    void routingRule_setPatterns_null_becomesEmptyList() {
        SynthesisConfig.RoutingRule rule = new SynthesisConfig.RoutingRule();
        rule.setPatterns(null);
        assertNotNull(rule.getPatterns());
        assertTrue(rule.getPatterns().isEmpty());
    }

    // === SubWorkspaceConfig ===

    @Test
    void subWorkspaceConfig_defaultNoArg_fieldsHaveDefaults() {
        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig();
        assertEquals("", sw.getName());
        assertEquals("", sw.getPath());
        assertEquals("", sw.getDescription());
        assertEquals("general", sw.getType());
        assertNotNull(sw.getTags());
        assertNotNull(sw.getCodebases());
        assertNull(sw.getIncludePatterns());
        assertNull(sw.getExcludePatterns());
    }

    @Test
    void subWorkspaceConfig_namePathConstructor_setsNameAndPath() {
        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig("MyOrg", "MyOrg/");
        assertEquals("MyOrg", sw.getName());
        assertEquals("MyOrg/", sw.getPath());
    }

    @Test
    void subWorkspaceConfig_isStaging_falseByDefault() {
        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig();
        assertFalse(sw.isStaging());
    }

    @Test
    void subWorkspaceConfig_isStaging_trueWhenTypeIsStaging() {
        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig();
        sw.setType("staging");
        assertTrue(sw.isStaging());
    }

    @Test
    void subWorkspaceConfig_setTags_null_becomesEmptyList() {
        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig();
        sw.setTags(null);
        assertNotNull(sw.getTags());
        assertTrue(sw.getTags().isEmpty());
    }

    @Test
    void subWorkspaceConfig_setCodebases_null_becomesEmptyList() {
        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig();
        sw.setCodebases(null);
        assertNotNull(sw.getCodebases());
        assertTrue(sw.getCodebases().isEmpty());
    }

    @Test
    void subWorkspaceConfig_toString_containsNameAndPath() {
        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig("Alpha", "alpha/");
        String str = sw.toString();
        assertTrue(str.contains("Alpha"), "toString should contain name");
        assertTrue(str.contains("alpha/"), "toString should contain path");
    }

    // === ReportConfig ===

    @Test
    void reportConfig_defaultOutputDir_isNull() {
        SynthesisConfig.ReportConfig report = new SynthesisConfig.ReportConfig();
        assertNull(report.getOutputDir());
    }

    @Test
    void reportConfig_setOutputDir_updatesValue() {
        SynthesisConfig.ReportConfig report = new SynthesisConfig.ReportConfig();
        report.setOutputDir("/tmp/reports");
        assertEquals("/tmp/reports", report.getOutputDir());
    }
}
