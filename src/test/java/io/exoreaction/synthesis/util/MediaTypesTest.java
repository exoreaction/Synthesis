package io.exoreaction.synthesis.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MediaTypes} shared constants.
 *
 * @since v1.13.0 (P1-03)
 */
class MediaTypesTest {

    @Test
    void videoExtensions_containsStandardFormats() {
        assertTrue(MediaTypes.VIDEO_EXTENSIONS.contains("mp4"));
        assertTrue(MediaTypes.VIDEO_EXTENSIONS.contains("mov"));
        assertTrue(MediaTypes.VIDEO_EXTENSIONS.contains("avi"));
        assertTrue(MediaTypes.VIDEO_EXTENSIONS.contains("mkv"));
        assertTrue(MediaTypes.VIDEO_EXTENSIONS.contains("webm"));
    }

    @Test
    void audioExtensions_containsStandardFormats() {
        assertTrue(MediaTypes.AUDIO_EXTENSIONS.contains("mp3"));
        assertTrue(MediaTypes.AUDIO_EXTENSIONS.contains("wav"));
        assertTrue(MediaTypes.AUDIO_EXTENSIONS.contains("flac"));
        assertTrue(MediaTypes.AUDIO_EXTENSIONS.contains("ogg"));
        assertTrue(MediaTypes.AUDIO_EXTENSIONS.contains("aac"));
    }

    @Test
    void audioExtensions_containsModernFormats() {
        assertTrue(MediaTypes.AUDIO_EXTENSIONS.contains("m4a"));
        assertTrue(MediaTypes.AUDIO_EXTENSIONS.contains("wma"));
    }

    @Test
    void imageExtensions_containsStandardFormats() {
        assertTrue(MediaTypes.IMAGE_EXTENSIONS.contains("jpg"));
        assertTrue(MediaTypes.IMAGE_EXTENSIONS.contains("jpeg"));
        assertTrue(MediaTypes.IMAGE_EXTENSIONS.contains("png"));
        assertTrue(MediaTypes.IMAGE_EXTENSIONS.contains("gif"));
        assertTrue(MediaTypes.IMAGE_EXTENSIONS.contains("svg"));
        assertTrue(MediaTypes.IMAGE_EXTENSIONS.contains("bmp"));
    }

    @Test
    void imageExtensions_containsModernFormats() {
        assertTrue(MediaTypes.IMAGE_EXTENSIONS.contains("webp"));
        assertTrue(MediaTypes.IMAGE_EXTENSIONS.contains("heic"));
        assertTrue(MediaTypes.IMAGE_EXTENSIONS.contains("tiff"));
    }

    @Test
    void mediaExtensions_isUnionOfSubsets() {
        // Every video extension must be in MEDIA_EXTENSIONS
        for (String ext : MediaTypes.VIDEO_EXTENSIONS) {
            assertTrue(MediaTypes.MEDIA_EXTENSIONS.contains(ext),
                    "VIDEO_EXTENSIONS member '" + ext + "' must be in MEDIA_EXTENSIONS");
        }
        // Every audio extension must be in MEDIA_EXTENSIONS
        for (String ext : MediaTypes.AUDIO_EXTENSIONS) {
            assertTrue(MediaTypes.MEDIA_EXTENSIONS.contains(ext),
                    "AUDIO_EXTENSIONS member '" + ext + "' must be in MEDIA_EXTENSIONS");
        }
        // Every image extension must be in MEDIA_EXTENSIONS
        for (String ext : MediaTypes.IMAGE_EXTENSIONS) {
            assertTrue(MediaTypes.MEDIA_EXTENSIONS.contains(ext),
                    "IMAGE_EXTENSIONS member '" + ext + "' must be in MEDIA_EXTENSIONS");
        }
    }

    @Test
    void mediaExtensions_sizeEqualsUnionOfSubsets() {
        // MEDIA_EXTENSIONS should contain exactly the union of the three subsets
        Set<String> expectedUnion = new java.util.HashSet<>();
        expectedUnion.addAll(MediaTypes.VIDEO_EXTENSIONS);
        expectedUnion.addAll(MediaTypes.AUDIO_EXTENSIONS);
        expectedUnion.addAll(MediaTypes.IMAGE_EXTENSIONS);
        assertEquals(expectedUnion.size(), MediaTypes.MEDIA_EXTENSIONS.size(),
                "MEDIA_EXTENSIONS size should equal the union of all subsets");
    }

    @Test
    void mediaExtensions_doesNotContainNonMediaTypes() {
        assertFalse(MediaTypes.MEDIA_EXTENSIONS.contains("pdf"));
        assertFalse(MediaTypes.MEDIA_EXTENSIONS.contains("md"));
        assertFalse(MediaTypes.MEDIA_EXTENSIONS.contains("java"));
        assertFalse(MediaTypes.MEDIA_EXTENSIONS.contains("docx"));
    }

    // ---- P1-04: EXTENSION_REJECT_TYPE_MAP ----

    @Test
    void extensionRejectTypeMap_containsVideoExtensions() {
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.containsKey("mp4"));
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.get("mp4").contains("video"));
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.get("mp4").contains("media"));
    }

    @Test
    void extensionRejectTypeMap_containsAudioExtensions() {
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.containsKey("mp3"));
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.get("mp3").contains("audio"));
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.get("mp3").contains("media"));
    }

    @Test
    void extensionRejectTypeMap_containsImageExtensions() {
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.containsKey("png"));
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.get("png").contains("image"));
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.get("png").contains("media"));
    }

    @Test
    void extensionRejectTypeMap_containsDocumentExtensions() {
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.containsKey("pdf"));
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.get("pdf").contains("document"));
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.containsKey("md"));
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.get("md").contains("document"));
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.containsKey("txt"));
        assertTrue(MediaTypes.EXTENSION_REJECT_TYPE_MAP.get("txt").contains("document"));
    }

    @Test
    void extensionRejectTypeMap_unknownExtensionReturnsNull() {
        assertNull(MediaTypes.EXTENSION_REJECT_TYPE_MAP.get("java"));
        assertNull(MediaTypes.EXTENSION_REJECT_TYPE_MAP.get("rs"));
    }

    @Test
    void allSets_areUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> MediaTypes.VIDEO_EXTENSIONS.add("flv"));
        assertThrows(UnsupportedOperationException.class,
                () -> MediaTypes.AUDIO_EXTENSIONS.add("opus"));
        assertThrows(UnsupportedOperationException.class,
                () -> MediaTypes.IMAGE_EXTENSIONS.add("ico"));
        assertThrows(UnsupportedOperationException.class,
                () -> MediaTypes.MEDIA_EXTENSIONS.add("flv"));
    }
}
