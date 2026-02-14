package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClientStatus} enum methods.
 */
class ClientStatusTest {

    @ParameterizedTest
    @CsvSource({
            "opportunity-SpareBank1, OPPORTUNITY",
            "opportunity-Mynder, OPPORTUNITY",
            "opportunity-ItemConsulting, OPPORTUNITY",
            "Entra-past, PAST",
            "CatalystOne-past, PAST",
            "Skytale-past, PAST",
            "Elprint, ACTIVE",
            "Opplysningen-1881, ACTIVE",
            "CatalystOne, ACTIVE",
            "realestate, ACTIVE"
    })
    void fromDirectoryName_detectsCorrectStatus(String dirName, ClientStatus expected) {
        assertEquals(expected, ClientStatus.fromDirectoryName(dirName));
    }

    @ParameterizedTest
    @CsvSource({
            "opportunity-SpareBank1, SpareBank1",
            "opportunity-Mynder, Mynder",
            "opportunity-ItemConsulting, ItemConsulting",
            "Entra-past, Entra",
            "CatalystOne-past, CatalystOne",
            "Elprint, Elprint",
            "Opplysningen-1881, Opplysningen-1881",
            "realestate, realestate"
    })
    void extractClientName_removesStatusPrefixSuffix(String dirName, String expectedName) {
        assertEquals(expectedName, ClientStatus.extractClientName(dirName));
    }

    @Test
    void fromDirectoryName_emptyString_returnsActive() {
        assertEquals(ClientStatus.ACTIVE, ClientStatus.fromDirectoryName(""));
    }

    @Test
    void extractClientName_emptyString_returnsEmpty() {
        assertEquals("", ClientStatus.extractClientName(""));
    }

    @Test
    void allValuesExist() {
        assertEquals(4, ClientStatus.values().length);
        assertNotNull(ClientStatus.valueOf("ACTIVE"));
        assertNotNull(ClientStatus.valueOf("PAST"));
        assertNotNull(ClientStatus.valueOf("OPPORTUNITY"));
        assertNotNull(ClientStatus.valueOf("SIGNED"));
    }
}
