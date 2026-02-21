package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DirectoryProfile} wrapper record.
 */
class DirectoryProfileTest {

    @Test
    void fromIdentity_wrapsWithEmptyCentroidAndWants() {
        DirectoryIdentity identity = DirectoryIdentity.empty();
        DirectoryProfile profile = DirectoryProfile.fromIdentity(identity);

        assertSame(identity, profile.identity());
        assertEquals(DirectoryCentroid.empty(), profile.centroid());
        assertEquals(DirectoryWants.empty(), profile.wants());
    }

    @Test
    void constructWithAllComponents() {
        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("marketing"), List.of("md", "pdf"), List.of(),
                ScopeLevel.ORGANIZATION, "eXOReaction", null,
                0.8, null, "test", ""
        );
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("renewable energy"), List.of("GreenField"),
                "Q1", List.of("proposal"), 0.87, 8, 0, Instant.now()
        );
        DirectoryWants wants = new DirectoryWants(
                List.of("energy"), List.of("GreenField"), List.of("invoice"),
                "inferred", 0.5
        );

        DirectoryProfile profile = new DirectoryProfile(identity, centroid, wants);

        assertEquals(identity, profile.identity());
        assertEquals(centroid, profile.centroid());
        assertEquals(wants, profile.wants());
    }

    @Test
    void constructWithNullCentroid_defaultsToEmpty() {
        DirectoryIdentity identity = DirectoryIdentity.empty();
        DirectoryProfile profile = new DirectoryProfile(identity, null, null);

        assertEquals(DirectoryCentroid.empty(), profile.centroid());
        assertEquals(DirectoryWants.empty(), profile.wants());
    }

    @Test
    void constructWithNullIdentity_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new DirectoryProfile(null, DirectoryCentroid.empty(), DirectoryWants.empty()));
    }
}
