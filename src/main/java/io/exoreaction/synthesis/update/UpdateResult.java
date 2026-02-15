package io.exoreaction.synthesis.update;

import java.util.Collections;
import java.util.List;

/**
 * Result of performing an update operation.
 *
 * <p>Contains details about what was updated, the new version,
 * and any errors that occurred during the update process.
 *
 * @author Thor Henning Hetland / eXOReaction
 */
public class UpdateResult {

    private final String previousVersion;
    private final String newVersion;
    private final List<String> updatedComponents;
    private final List<String> errors;
    private final boolean dryRun;

    public UpdateResult(String previousVersion, String newVersion,
                        List<String> updatedComponents, List<String> errors,
                        boolean dryRun) {
        this.previousVersion = previousVersion;
        this.newVersion = newVersion;
        this.updatedComponents = updatedComponents != null ? updatedComponents : Collections.emptyList();
        this.errors = errors != null ? errors : Collections.emptyList();
        this.dryRun = dryRun;
    }

    /** Version before the update. */
    public String getPreviousVersion() { return previousVersion; }

    /** Version after the update (null if update failed). */
    public String getNewVersion() { return newVersion; }

    /** List of components that were updated. */
    public List<String> getUpdatedComponents() { return updatedComponents; }

    /** List of errors that occurred during update. */
    public List<String> getErrors() { return errors; }

    /** Whether this was a dry run (no changes made). */
    public boolean isDryRun() { return dryRun; }

    /** Whether the update was successful (has new version and no errors). */
    public boolean isSuccessful() {
        return newVersion != null && errors.isEmpty();
    }

    /** Whether any components were actually updated. */
    public boolean hasUpdates() {
        return !updatedComponents.isEmpty();
    }

    @Override
    public String toString() {
        return "UpdateResult{" + previousVersion + " -> " + newVersion
                + ", components=" + updatedComponents.size()
                + ", errors=" + errors.size()
                + (dryRun ? ", DRY RUN" : "") + "}";
    }
}
