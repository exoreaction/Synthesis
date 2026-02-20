package io.exoreaction.synthesis.org;

import java.time.Instant;
import java.util.*;

/**
 * Bootstraps directory identity from well-known directory name patterns.
 *
 * <p>Maps normalized directory names to identity templates. Name matching is
 * case-insensitive and handles kebab-case, underscores, and spaces by stripping
 * all separators before lookup.
 *
 * <p>Returns {@link Optional#empty()} for unrecognized names — no guessing.
 */
public class DirectoryNameVocabulary {

    private static final double DEFAULT_CONFIDENCE = 0.6;
    private static final String INFERRED_SOURCE = "inferred from directory name";

    /**
     * An identity template holding content types, accepted formats, and per-entry confidence.
     */
    private record IdentityTemplate(List<String> types, List<String> formats, double confidence) {
        /** Convenience constructor using the default confidence (0.6). */
        IdentityTemplate(List<String> types, List<String> formats) {
            this(types, formats, DEFAULT_CONFIDENCE);
        }
    }

    /** Map from normalized name (lowercase, separators stripped) to template. */
    private static final Map<String, IdentityTemplate> VOCABULARY;

    static {
        Map<String, IdentityTemplate> map = new HashMap<>();

        // Meeting notes / minutes
        IdentityTemplate meetings = new IdentityTemplate(
                List.of("meeting-notes", "minutes"), List.of("md", "pdf", "docx"));
        map.put("meetingnotes", meetings);
        map.put("meetings", meetings);
        map.put("minutes", meetings);

        // Presentations / slides
        IdentityTemplate presentations = new IdentityTemplate(
                List.of("presentation", "slides"), List.of("pdf", "pptx", "png"));
        map.put("presentations", presentations);
        map.put("slides", presentations);
        map.put("decks", presentations);

        // Invoices / billing (including Norwegian "faktura")
        IdentityTemplate invoices = new IdentityTemplate(
                List.of("invoice", "financial"), List.of("pdf"));
        map.put("invoices", invoices);
        map.put("billing", invoices);
        map.put("faktura", invoices);

        // Media / visuals
        IdentityTemplate media = new IdentityTemplate(
                List.of("media", "visual"), List.of("png", "jpg", "pdf", "mp4"));
        map.put("media", media);
        map.put("visuals", media);
        map.put("images", media);
        map.put("screenshots", media);

        // Business / strategy
        IdentityTemplate business = new IdentityTemplate(
                List.of("business", "strategy"), List.of("md", "pdf", "docx"));
        map.put("business", business);
        map.put("strategy", business);

        // Clients
        IdentityTemplate clients = new IdentityTemplate(
                List.of("client-material"), List.of("md", "pdf", "docx"));
        map.put("clients", clients);

        // Archive
        IdentityTemplate archive = new IdentityTemplate(
                List.of("archive"), List.of("*"));
        map.put("archive", archive);

        // Documentation
        IdentityTemplate docs = new IdentityTemplate(
                List.of("documentation"), List.of("md", "pdf", "txt"));
        map.put("docs", docs);
        map.put("documentation", docs);

        // Contracts / legal
        IdentityTemplate legal = new IdentityTemplate(
                List.of("contract", "legal"), List.of("pdf", "docx"));
        map.put("contracts", legal);
        map.put("legal", legal);

        // Marketing
        IdentityTemplate marketing = new IdentityTemplate(
                List.of("marketing"), List.of("md", "pdf", "png", "mp4"));
        map.put("marketing", marketing);

        // Products
        IdentityTemplate products = new IdentityTemplate(
                List.of("product"), List.of("md", "pdf"));
        map.put("products", products);

        // Sales
        IdentityTemplate sales = new IdentityTemplate(
                List.of("sales"), List.of("pdf", "pptx", "docx"));
        map.put("sales", sales);

        // Automation / scripts
        IdentityTemplate automation = new IdentityTemplate(
                List.of("automation", "scripts"), List.of("sh", "py", "md"), 0.8);
        map.put("automation", automation);
        map.put("scripts", automation);

        // Reports
        IdentityTemplate reports = new IdentityTemplate(
                List.of("report"), List.of("md", "pdf", "docx"));
        map.put("reports", reports);

        // Guides / tutorials (#176)
        IdentityTemplate guides = new IdentityTemplate(
                List.of("guide", "documentation"), List.of("md", "pdf"), 0.8);
        map.put("guides", guides);
        map.put("tutorials", guides);
        map.put("howtos", guides);

        // Executive reports (#176)
        IdentityTemplate executiveReports = new IdentityTemplate(
                List.of("executive", "report"), List.of("md", "pdf"), 0.85);
        map.put("executivereports", executiveReports);
        map.put("execreports", executiveReports);

        // Knowledge infrastructure (#176)
        IdentityTemplate knowledge = new IdentityTemplate(
                List.of("knowledge", "infrastructure"), List.of("md"), 0.7);
        map.put("knowledgeinfrastructure", knowledge);
        map.put("knowledge", knowledge);

        // Templates (#176)
        IdentityTemplate templates = new IdentityTemplate(
                List.of("template"), List.of("md", "yaml"), 0.75);
        map.put("templates", templates);

        // Runbooks / playbooks (#176)
        IdentityTemplate runbooks = new IdentityTemplate(
                List.of("runbook", "operations"), List.of("md"), 0.85);
        map.put("runbooks", runbooks);
        map.put("playbooks", runbooks);
        map.put("ops", runbooks);

        VOCABULARY = Collections.unmodifiableMap(map);
    }

    /**
     * Infers a {@link DirectoryIdentity} from a directory name and resolved scope.
     *
     * <p>The directory name is normalized by lowercasing and stripping hyphens,
     * underscores, and spaces before lookup. If the name is not in the vocabulary,
     * returns {@link Optional#empty()}.
     *
     * @param directoryName the name of the directory (not the full path)
     * @param scope         the resolved organizational scope for the directory
     * @return an optional identity with the template's confidence, or empty if unrecognized
     */
    public Optional<DirectoryIdentity> inferFromName(String directoryName, ScopeResolver.ResolvedScope scope) {
        if (directoryName == null || directoryName.isBlank()) {
            return Optional.empty();
        }

        String normalized = normalize(directoryName);
        IdentityTemplate template = VOCABULARY.get(normalized);

        if (template == null) {
            return Optional.empty();
        }

        DirectoryIdentity identity = new DirectoryIdentity(
                template.types(),
                template.formats(),
                List.of(),                          // no patterns from name vocabulary
                scope.level(),
                scope.organization(),
                scope.entity(),
                template.confidence(),
                Instant.now(),
                INFERRED_SOURCE,
                ""
        );

        return Optional.of(identity);
    }

    /**
     * Normalizes a directory name for vocabulary lookup.
     * Lowercases and strips hyphens, underscores, and spaces.
     */
    static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }
}
