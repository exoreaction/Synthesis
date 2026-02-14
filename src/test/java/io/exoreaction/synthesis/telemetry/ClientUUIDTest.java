package io.exoreaction.synthesis.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClientUUID} -- UUID generation, persistence, and validation.
 */
class ClientUUIDTest {

    @TempDir
    Path tempDir;

    @Test
    void getOrCreateGeneratesNewUuidWhenFileDoesNotExist() throws IOException {
        Path uuidPath = tempDir.resolve(".synthesis/client-uuid");

        String uuid = ClientUUID.getOrCreate(uuidPath);

        assertNotNull(uuid);
        assertFalse(uuid.isBlank());
        // Verify it's a valid UUID
        assertDoesNotThrow(() -> UUID.fromString(uuid));
        // Verify file was created
        assertTrue(Files.exists(uuidPath));
        assertEquals(uuid, Files.readString(uuidPath).trim());
    }

    @Test
    void getOrCreateReturnsExistingUuidWhenFileExists() throws IOException {
        Path uuidPath = tempDir.resolve(".synthesis/client-uuid");
        Files.createDirectories(uuidPath.getParent());

        String originalUuid = UUID.randomUUID().toString();
        Files.writeString(uuidPath, originalUuid + "\n");

        String uuid = ClientUUID.getOrCreate(uuidPath);

        assertEquals(originalUuid, uuid);
    }

    @Test
    void getOrCreateRegeneratesWhenFileContainsInvalidContent() throws IOException {
        Path uuidPath = tempDir.resolve(".synthesis/client-uuid");
        Files.createDirectories(uuidPath.getParent());
        Files.writeString(uuidPath, "not-a-valid-uuid\n");

        String uuid = ClientUUID.getOrCreate(uuidPath);

        assertNotNull(uuid);
        assertNotEquals("not-a-valid-uuid", uuid);
        assertDoesNotThrow(() -> UUID.fromString(uuid));
    }

    @Test
    void getOrCreateRegeneratesWhenFileIsEmpty() throws IOException {
        Path uuidPath = tempDir.resolve(".synthesis/client-uuid");
        Files.createDirectories(uuidPath.getParent());
        Files.writeString(uuidPath, "");

        String uuid = ClientUUID.getOrCreate(uuidPath);

        assertNotNull(uuid);
        assertFalse(uuid.isBlank());
        assertDoesNotThrow(() -> UUID.fromString(uuid));
    }

    @Test
    void readReturnsNullWhenFileDoesNotExist() {
        Path uuidPath = tempDir.resolve(".synthesis/client-uuid");

        assertNull(ClientUUID.read(uuidPath));
    }

    @Test
    void readReturnsUuidWhenFileExists() throws IOException {
        Path uuidPath = tempDir.resolve(".synthesis/client-uuid");
        Files.createDirectories(uuidPath.getParent());

        String expectedUuid = UUID.randomUUID().toString();
        Files.writeString(uuidPath, expectedUuid + "\n");

        String uuid = ClientUUID.read(uuidPath);

        assertEquals(expectedUuid, uuid);
    }

    @Test
    void readReturnsNullForInvalidContent() throws IOException {
        Path uuidPath = tempDir.resolve(".synthesis/client-uuid");
        Files.createDirectories(uuidPath.getParent());
        Files.writeString(uuidPath, "garbage");

        assertNull(ClientUUID.read(uuidPath));
    }

    @Test
    void existsReturnsFalseWhenNoFile() {
        Path uuidPath = tempDir.resolve(".synthesis/client-uuid");
        assertFalse(ClientUUID.exists(uuidPath));
    }

    @Test
    void existsReturnsTrueWhenValidFile() throws IOException {
        Path uuidPath = tempDir.resolve(".synthesis/client-uuid");
        Files.createDirectories(uuidPath.getParent());
        Files.writeString(uuidPath, UUID.randomUUID().toString());

        assertTrue(ClientUUID.exists(uuidPath));
    }

    @Test
    void isValidUuidAcceptsValidUuids() {
        assertTrue(ClientUUID.isValidUuid("550e8400-e29b-41d4-a716-446655440000"));
        assertTrue(ClientUUID.isValidUuid(UUID.randomUUID().toString()));
    }

    @Test
    void isValidUuidRejectsInvalidValues() {
        assertFalse(ClientUUID.isValidUuid(null));
        assertFalse(ClientUUID.isValidUuid(""));
        assertFalse(ClientUUID.isValidUuid("   "));
        assertFalse(ClientUUID.isValidUuid("not-a-uuid"));
        assertFalse(ClientUUID.isValidUuid("12345"));
    }

    @Test
    void getOrCreateProducesConsistentResults() throws IOException {
        Path uuidPath = tempDir.resolve(".synthesis/client-uuid");

        String first = ClientUUID.getOrCreate(uuidPath);
        String second = ClientUUID.getOrCreate(uuidPath);
        String third = ClientUUID.getOrCreate(uuidPath);

        assertEquals(first, second);
        assertEquals(second, third);
    }

    @Test
    void getUuidPathUsesCustomHome() {
        Path customHome = Path.of("/custom/home");
        Path uuidPath = ClientUUID.getUuidPath(customHome);

        assertEquals(customHome.resolve(".synthesis/client-uuid"), uuidPath);
    }
}
