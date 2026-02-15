package io.exoreaction.synthesis.update;

/**
 * Options for controlling the update process.
 *
 * @author Thor Henning Hetland / eXOReaction
 */
public class UpdateOptions {

    private boolean dryRun = false;
    private boolean force = false;
    private boolean skipDocs = false;
    private boolean skipVisuals = false;
    private boolean skipGitPull = false;
    private boolean skipBuild = false;
    private String targetVersion = null;

    /** Default options -- update everything. */
    public UpdateOptions() {}

    // --- Builder-style setters ---

    public UpdateOptions dryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    public UpdateOptions force(boolean force) {
        this.force = force;
        return this;
    }

    public UpdateOptions skipDocs(boolean skipDocs) {
        this.skipDocs = skipDocs;
        return this;
    }

    public UpdateOptions skipVisuals(boolean skipVisuals) {
        this.skipVisuals = skipVisuals;
        return this;
    }

    public UpdateOptions skipGitPull(boolean skipGitPull) {
        this.skipGitPull = skipGitPull;
        return this;
    }

    public UpdateOptions skipBuild(boolean skipBuild) {
        this.skipBuild = skipBuild;
        return this;
    }

    public UpdateOptions targetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
        return this;
    }

    // --- Getters ---

    /** If true, only report what would be done without making changes. */
    public boolean isDryRun() { return dryRun; }

    /** If true, force update even if versions match. */
    public boolean isForce() { return force; }

    /** If true, skip documentation installation (saves bandwidth). */
    public boolean isSkipDocs() { return skipDocs; }

    /** If true, skip visual assets installation (saves significant bandwidth). */
    public boolean isSkipVisuals() { return skipVisuals; }

    /** If true, skip git pull when updating from source. */
    public boolean isSkipGitPull() { return skipGitPull; }

    /** If true, skip Maven build (use existing target/ JARs). */
    public boolean isSkipBuild() { return skipBuild; }

    /** Target version to update to (null = latest). */
    public String getTargetVersion() { return targetVersion; }

    @Override
    public String toString() {
        return "UpdateOptions{dryRun=" + dryRun + ", force=" + force
                + ", skipDocs=" + skipDocs + ", skipVisuals=" + skipVisuals
                + ", targetVersion=" + targetVersion + "}";
    }
}
