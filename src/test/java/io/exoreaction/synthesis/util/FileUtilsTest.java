package io.exoreaction.synthesis.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for FileUtils — classifyFile, detectLanguage, isBinaryFile,
 * formatSize, getExtension, FileType behaviour.
 */
class FileUtilsTest {

    // --- classifyFile ---

    @ParameterizedTest
    @CsvSource({
        "README.md,     MARKDOWN",
        "notes.markdown,MARKDOWN",
        "Main.java,     CODE",
        "app.py,        CODE",
        "app.ts,        CODE",
        "index.js,      CODE",
        "lib.rs,        CODE",
        "module.go,     CODE",
        "config.yaml,   YAML",
        "config.yml,    YAML",
        "data.json,     JSON",
        "data.jsonc,    JSON",
        "settings.toml, CONFIG",
        "app.properties,CONFIG",
        "app.xml,       CONFIG",
        "report.pdf,    PDF",
        "photo.png,     IMAGE",
        "photo.jpg,     IMAGE",
        "photo.jpeg,    IMAGE",
        "photo.svg,     IMAGE",
        "video.mp4,     VIDEO",
        "clip.mov,      VIDEO",
        "audio.mp3,     AUDIO",
        "audio.wav,     AUDIO",
        "doc.docx,      DOCUMENT",
        "sheet.xlsx,    DOCUMENT",
        "archive.zip,   BINARY",
        "library.jar,   BINARY",
        "bytecode.class,BINARY",
        "unknown.xyz,   OTHER"
    })
    void classifyFile_byExtension(String filename, String expectedType, @TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, "content");
        FileUtils.FileType type = FileUtils.classifyFile(file);
        assertEquals(FileUtils.FileType.valueOf(expectedType), type,
                "'" + filename + "' should classify as " + expectedType);
    }

    // --- detectLanguage ---

    @ParameterizedTest
    @CsvSource({
        "Main.java,   Java",
        "app.py,      Python",
        "app.js,      JavaScript",
        "app.ts,      TypeScript",
        "app.tsx,     TypeScript",
        "app.jsx,     JavaScript",
        "lib.go,      Go",
        "lib.rs,      Rust",
        "lib.rb,      Ruby",
        "App.kt,      Kotlin",
        "App.scala,   Scala",
        "prog.c,      C",
        "prog.cpp,    C++",
        "prog.h,      C",
        "prog.hpp,    C++",
        "App.cs,      C#",
        "App.swift,   Swift",
        "app.php,     PHP",
        "script.sh,   Shell",
        "script.bash, Shell",
        "query.sql,   SQL"
    })
    void detectLanguage_knownExtensions(String filename, String expectedLang,
                                         @TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, "content");
        assertEquals(expectedLang, FileUtils.detectLanguage(file),
                "'" + filename + "' should detect language " + expectedLang);
    }

    @ParameterizedTest
    @ValueSource(strings = {"README.md", "config.yaml", "data.json", "report.pdf", "photo.png"})
    void detectLanguage_nonCodeFiles_returnsNull(String filename, @TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, "content");
        assertNull(FileUtils.detectLanguage(file),
                "'" + filename + "' should have null language");
    }

    // --- getExtension ---

    @ParameterizedTest
    @CsvSource({
        "Main.java,     .java",
        "app.py,        .py",
        "README.md,     .md",
        "archive.tar.gz,.gz",
        "noextension,   ''"
    })
    void getExtension_variousPaths(String filename, String expected) {
        String result = FileUtils.getExtension(Path.of(filename));
        assertEquals(expected, result);
    }

    @Test
    void getExtension_noExtension_returnsEmpty() {
        assertEquals("", FileUtils.getExtension(Path.of("noextension")));
    }

    // --- isBinaryFile by extension ---

    @ParameterizedTest
    @ValueSource(strings = {
        "archive.zip", "library.jar", "bytecode.class",
        "photo.png", "photo.jpg", "photo.jpeg",
        "video.mp4", "clip.mov",
        "audio.mp3", "audio.wav",
        "doc.docx", "sheet.xlsx",
        "lib.so", "program.exe"
    })
    void isBinaryFile_binaryExtensions_returnsTrue(String filename, @TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve(filename);
        Files.write(file, new byte[]{0x00, 0x01, 0x02});
        assertTrue(FileUtils.isBinaryFile(file),
                "'" + filename + "' should be detected as binary");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Main.java", "app.py", "README.md",
        "config.yaml", "data.json", "settings.toml"
    })
    void isBinaryFile_textFiles_returnsFalse(String filename, @TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, "This is text content with no null bytes");
        assertFalse(FileUtils.isBinaryFile(file),
                "'" + filename + "' should not be binary");
    }

    @Test
    void isBinaryFile_svgFile_returnsFalse(@TempDir Path tempDir) throws IOException {
        Path svg = tempDir.resolve("icon.svg");
        Files.writeString(svg, "<svg xmlns='http://www.w3.org/2000/svg'/>");
        assertFalse(FileUtils.isBinaryFile(svg), "SVG is text-based XML, should not be binary");
    }

    @Test
    void isBinaryFile_fileWithNullByte_returnsTrue(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("weird.txt");
        Files.write(file, new byte[]{'a', 'b', 0x00, 'c'});
        assertTrue(FileUtils.isBinaryFile(file), "File with null byte should be binary");
    }

    // --- formatSize ---

    @ParameterizedTest
    @CsvSource({
        "0,          B",
        "512,        B",
        "1023,       B",
        "1024,       KB",
        "2048,       KB",
        "1048575,    KB",
        "1048576,    MB",
        "5242880,    MB",
        "1073741824, GB"
    })
    void formatSize_usesCorrectUnit(long bytes, String expectedUnit) {
        String result = FileUtils.formatSize(bytes);
        assertTrue(result.contains(expectedUnit),
                "formatSize(" + bytes + ") = '" + result + "' should contain " + expectedUnit);
    }

    @Test
    void formatSize_zero_returns0B() {
        assertEquals("0 B", FileUtils.formatSize(0));
    }

    @Test
    void formatSize_1024_contains1KB() {
        String result = FileUtils.formatSize(1024);
        assertTrue(result.contains("KB"), "1024 bytes should format as KB");
        assertTrue(result.contains("1.0"), "1024 bytes = 1.0 KB");
    }

    // --- isMediaFile ---

    @ParameterizedTest
    @ValueSource(strings = {"photo.png", "photo.jpg", "video.mp4", "audio.mp3"})
    void isMediaFile_mediaFiles_returnsTrue(String filename, @TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve(filename);
        Files.write(file, new byte[]{0x00});
        assertTrue(FileUtils.isMediaFile(file));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Main.java", "README.md", "config.yaml", "data.json"})
    void isMediaFile_nonMediaFiles_returnsFalse(String filename, @TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, "text");
        assertFalse(FileUtils.isMediaFile(file));
    }

    // --- FileType enum contracts ---

    @ParameterizedTest
    @EnumSource(value = FileUtils.FileType.class, names = {"IMAGE", "VIDEO", "AUDIO"})
    void fileType_mediaTypes_isMediaTrue(FileUtils.FileType type) {
        assertTrue(type.isMedia(), type + ".isMedia() should be true");
    }

    @ParameterizedTest
    @EnumSource(value = FileUtils.FileType.class,
            names = {"IMAGE", "VIDEO", "AUDIO"},
            mode = EnumSource.Mode.EXCLUDE)
    void fileType_nonMediaTypes_isMediaFalse(FileUtils.FileType type) {
        assertFalse(type.isMedia(), type + ".isMedia() should be false");
    }

    @ParameterizedTest
    @EnumSource(value = FileUtils.FileType.class, names = {"BINARY", "OTHER"})
    void fileType_binaryAndOther_notAnalyzable(FileUtils.FileType type) {
        assertFalse(type.isAnalyzable(), type + ".isAnalyzable() should be false");
    }

    @ParameterizedTest
    @EnumSource(value = FileUtils.FileType.class,
            names = {"BINARY", "OTHER"},
            mode = EnumSource.Mode.EXCLUDE)
    void fileType_allExceptBinaryOther_isAnalyzable(FileUtils.FileType type) {
        assertTrue(type.isAnalyzable(), type + ".isAnalyzable() should be true");
    }

    // --- md5Hash ---

    @Test
    void md5Hash_sameContent_producesConsistentHash(@TempDir Path tempDir) throws IOException {
        Path fileA = tempDir.resolve("a.txt");
        Path fileB = tempDir.resolve("b.txt");
        Files.writeString(fileA, "hello world");
        Files.writeString(fileB, "hello world");
        assertEquals(FileUtils.md5Hash(fileA), FileUtils.md5Hash(fileB),
                "Same content should produce same hash");
    }

    @Test
    void md5Hash_differentContent_producesDifferentHash(@TempDir Path tempDir) throws IOException {
        Path fileA = tempDir.resolve("a.txt");
        Path fileB = tempDir.resolve("b.txt");
        Files.writeString(fileA, "hello world");
        Files.writeString(fileB, "goodbye world");
        assertNotEquals(FileUtils.md5Hash(fileA), FileUtils.md5Hash(fileB),
                "Different content should produce different hashes");
    }

    @Test
    void md5Hash_returnsHexString(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "content");
        String hash = FileUtils.md5Hash(file);
        assertNotNull(hash);
        assertEquals(32, hash.length(), "MD5 should be 32 hex chars");
        assertTrue(hash.matches("[0-9a-f]+"), "Hash should be lowercase hex");
    }
}
