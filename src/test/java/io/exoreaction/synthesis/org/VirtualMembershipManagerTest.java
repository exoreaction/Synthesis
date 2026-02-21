package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VirtualMembershipManager} (P3-03).
 */
class VirtualMembershipManagerTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private VirtualMembershipManager manager;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        manager = new VirtualMembershipManager(db);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null && !db.isClosed()) db.close();
    }

    @Test
    void recordMembership_andRetrieve() throws SQLException {
        manager.recordMembership("/ws", "docs/proposal.pdf",
                "methodology/sdd", "topic overlap", 0.72);

        List<VirtualMembershipManager.VirtualMembership> members =
                manager.getVirtualMembers("/ws", "methodology/sdd");

        assertEquals(1, members.size());
        assertEquals("docs/proposal.pdf", members.get(0).filePath());
        assertEquals("methodology/sdd", members.get(0).directoryPath());
        assertEquals("topic overlap", members.get(0).relationship());
        assertEquals(0.72, members.get(0).bidStrength(), 0.001);
    }

    @Test
    void recordMembership_upsert_updatesExisting() throws SQLException {
        manager.recordMembership("/ws", "file.pdf", "dir/a", "topic overlap", 0.5);
        manager.recordMembership("/ws", "file.pdf", "dir/a", "entity overlap", 0.8);

        List<VirtualMembershipManager.VirtualMembership> members =
                manager.getVirtualMembers("/ws", "dir/a");

        assertEquals(1, members.size(), "Should upsert, not create duplicate");
        assertEquals("entity overlap", members.get(0).relationship());
        assertEquals(0.8, members.get(0).bidStrength(), 0.001);
    }

    @Test
    void getVirtualMembershipsForFile_returnsDirectories() throws SQLException {
        manager.recordMembership("/ws", "proposal.pdf", "methodology/sdd", "topic", 0.7);
        manager.recordMembership("/ws", "proposal.pdf", "products/workshop", "topic", 0.5);
        manager.recordMembership("/ws", "other.pdf", "methodology/sdd", "entity", 0.6);

        List<VirtualMembershipManager.VirtualMembership> memberships =
                manager.getVirtualMembershipsForFile("/ws", "proposal.pdf");

        assertEquals(2, memberships.size());
        // Sorted by bid_strength descending
        assertEquals("methodology/sdd", memberships.get(0).directoryPath());
        assertEquals("products/workshop", memberships.get(1).directoryPath());
    }

    @Test
    void countVirtualMembers_returnsCorrectCount() throws SQLException {
        manager.recordMembership("/ws", "file1.pdf", "dir/a", "topic", 0.5);
        manager.recordMembership("/ws", "file2.pdf", "dir/a", "entity", 0.6);
        manager.recordMembership("/ws", "file3.pdf", "dir/b", "topic", 0.7);

        assertEquals(2, manager.countVirtualMembers("/ws", "dir/a"));
        assertEquals(1, manager.countVirtualMembers("/ws", "dir/b"));
        assertEquals(0, manager.countVirtualMembers("/ws", "dir/c"));
    }

    @Test
    void removeAllForFile_deletesAllMemberships() throws SQLException {
        manager.recordMembership("/ws", "proposal.pdf", "dir/a", "topic", 0.5);
        manager.recordMembership("/ws", "proposal.pdf", "dir/b", "entity", 0.6);

        assertEquals(2, manager.getVirtualMembershipsForFile("/ws", "proposal.pdf").size());

        manager.removeAllForFile("/ws", "proposal.pdf");

        assertEquals(0, manager.getVirtualMembershipsForFile("/ws", "proposal.pdf").size());
        // Other files unaffected
    }

    @Test
    void recordBids_recordsAllVirtualBids() throws SQLException {
        List<Bid> virtualBids = List.of(
                new Bid(Path.of("/ws/methodology/sdd"), 0.72, Bid.MembershipType.VIRTUAL,
                        List.of("topic-match(0.60): [sdd]")),
                new Bid(Path.of("/ws/products/workshop"), 0.55, Bid.MembershipType.VIRTUAL,
                        List.of("entity-match(0.40): [greenfield]"))
        );

        manager.recordBids("/ws", "proposal.pdf", virtualBids);

        List<VirtualMembershipManager.VirtualMembership> memberships =
                manager.getVirtualMembershipsForFile("/ws", "proposal.pdf");

        assertEquals(2, memberships.size());
    }

    @Test
    void getVirtualMembers_emptyTable_returnsEmptyList() throws SQLException {
        List<VirtualMembershipManager.VirtualMembership> members =
                manager.getVirtualMembers("/ws", "nonexistent");
        assertTrue(members.isEmpty());
    }

    @Test
    void workspaceIsolation_differentWorkspaces() throws SQLException {
        manager.recordMembership("/ws1", "file.pdf", "dir/a", "topic", 0.5);
        manager.recordMembership("/ws2", "file.pdf", "dir/a", "entity", 0.6);

        assertEquals(1, manager.getVirtualMembers("/ws1", "dir/a").size());
        assertEquals(1, manager.getVirtualMembers("/ws2", "dir/a").size());
    }

    @Test
    void virtualMembership_hasCreatedAt() throws SQLException {
        long before = java.time.Instant.now().getEpochSecond();
        manager.recordMembership("/ws", "file.pdf", "dir/a", "topic", 0.5);
        long after = java.time.Instant.now().getEpochSecond();

        List<VirtualMembershipManager.VirtualMembership> members =
                manager.getVirtualMembers("/ws", "dir/a");

        assertEquals(1, members.size());
        assertNotNull(members.get(0).createdAt());
        long createdEpoch = members.get(0).createdAt().getEpochSecond();
        assertTrue(createdEpoch >= before && createdEpoch <= after,
                "createdAt should be within test execution window");
    }
}
