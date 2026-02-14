package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VideoAnalyzer -- metadata extraction, companion transcripts,
 * ffprobe parsing, and duration formatting.
 */
class VideoAnalyzerTest {

    @TempDir
    Path tempDir;

    private final VideoAnalyzer analyzer = new VideoAnalyzer();

    @Test
    void testCanAnalyzeVideoFiles() {
        FileMetadata videoMd = createMetadata("demo.mp4", FileUtils.FileType.VIDEO);
        FileMetadata audioMd = createMetadata("podcast.mp3", FileUtils.FileType.AUDIO);
        FileMetadata codeMd = createMetadata("App.java", FileUtils.FileType.CODE);

        assertTrue(analyzer.canAnalyze(videoMd));
        assertTrue(analyzer.canAnalyze(audioMd), "Should also handle audio files");
        assertFalse(analyzer.canAnalyze(codeMd));
    }

    @Test
    void testVideoAnalysisBasic() throws IOException {
        // Create a minimal video file (just a file with video extension)
        Path mp4File = tempDir.resolve("presentation.mp4");
        Files.write(mp4File, new byte[]{0x00, 0x00, 0x00, 0x1C, 0x66, 0x74, 0x79, 0x70}); // minimal MP4 header

        FileMetadata fm = FileMetadata.of(mp4File, tempDir, Files.size(mp4File),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("Video"));
        assertTrue(result.summary().contains("MP4"));
        assertTrue(result.keywords().contains("video"));
        assertTrue(result.keywords().contains("mp4"));
        assertNotNull(result.metrics().get("format"));
        assertEquals("MP4", result.metrics().get("format"));
    }

    @Test
    void testAudioAnalysis() throws IOException {
        Path mp3File = tempDir.resolve("interview.mp3");
        Files.write(mp3File, new byte[]{(byte) 0xFF, (byte) 0xFB, 0x00, 0x00}); // minimal MP3 header

        FileMetadata fm = FileMetadata.of(mp3File, tempDir, Files.size(mp3File),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("Audio"));
        assertTrue(result.summary().contains("MP3"));
        assertTrue(result.keywords().contains("audio"));
        assertTrue(result.keywords().contains("mp3"));
        assertEquals("audio", result.metrics().get("mediaType"));
    }

    @Test
    void testCompanionTranscriptDetection() throws IOException {
        // Create video file
        Path mp4File = tempDir.resolve("talk.mp4");
        Files.write(mp4File, new byte[]{0x00, 0x00, 0x00, 0x1C});

        // Create companion transcript
        Path txtFile = tempDir.resolve("talk.txt");
        Files.writeString(txtFile, "Welcome to the presentation.\nThis is about AI development.\nKey takeaway: use SDD methodology.");

        FileMetadata fm = FileMetadata.of(mp4File, tempDir, Files.size(mp4File),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("has transcript"));
        assertTrue(result.keywords().contains("has-transcript"));
        assertTrue(result.contentPreview().contains("AI development"));
        assertTrue(result.contentPreview().contains("SDD methodology"));
        assertNotNull(result.metrics().get("companionFile"));
        assertEquals("talk.txt", result.metrics().get("companionFile"));
    }

    @Test
    void testCompanionSrtTranscript() throws IOException {
        Path mp4File = tempDir.resolve("video.mp4");
        Files.write(mp4File, new byte[]{0x00, 0x00, 0x00, 0x1C});

        Path srtFile = tempDir.resolve("video.srt");
        Files.writeString(srtFile, """
                1
                00:00:01,000 --> 00:00:04,000
                Hello and welcome to this demo.

                2
                00:00:05,000 --> 00:00:08,000
                Today we will discuss code generation.
                """);

        FileMetadata fm = FileMetadata.of(mp4File, tempDir, Files.size(mp4File),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.keywords().contains("has-transcript"));
        assertTrue(result.contentPreview().contains("code generation"));
    }

    @Test
    void testCompanionVttTranscript() throws IOException {
        Path mp4File = tempDir.resolve("lecture.mp4");
        Files.write(mp4File, new byte[]{0x00, 0x00});

        Path vttFile = tempDir.resolve("lecture.vtt");
        Files.writeString(vttFile, """
                WEBVTT

                00:00:01.000 --> 00:00:04.000
                Welcome to the lecture on enterprise architecture.
                """);

        FileMetadata fm = FileMetadata.of(mp4File, tempDir, Files.size(mp4File),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.keywords().contains("has-transcript"));
        assertTrue(result.contentPreview().contains("enterprise architecture"));
    }

    @Test
    void testCompanionMarkdownTranscript() throws IOException {
        Path mp4File = tempDir.resolve("demo.mp4");
        Files.write(mp4File, new byte[]{0x00, 0x00});

        Path mdFile = tempDir.resolve("demo.md");
        Files.writeString(mdFile, """
                # Demo Video Notes

                ## Key Points
                - Skill-Driven Development methodology
                - 25x faster development
                - Production-ready output
                """);

        FileMetadata fm = FileMetadata.of(mp4File, tempDir, Files.size(mp4File),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.keywords().contains("has-transcript"));
        assertTrue(result.contentPreview().contains("Skill-Driven Development"));
    }

    @Test
    void testNoCompanionTranscript() throws IOException {
        Path mp4File = tempDir.resolve("standalone.mp4");
        Files.write(mp4File, new byte[]{0x00, 0x00});

        FileMetadata fm = FileMetadata.of(mp4File, tempDir, Files.size(mp4File),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertFalse(result.keywords().contains("has-transcript"));
        assertFalse(result.summary().contains("has transcript"));
    }

    @Test
    void testFormatDuration() {
        assertEquals("5s", VideoAnalyzer.formatDuration(5));
        assertEquals("45s", VideoAnalyzer.formatDuration(45));
        assertEquals("1m 30s", VideoAnalyzer.formatDuration(90));
        assertEquals("5m 0s", VideoAnalyzer.formatDuration(300));
        assertEquals("1h 30m", VideoAnalyzer.formatDuration(5400));
        assertEquals("2h 15m", VideoAnalyzer.formatDuration(8100));
    }

    @Test
    void testCategorizeDuration() {
        assertEquals("clip", VideoAnalyzer.categorizeDuration(15));
        assertEquals("short-video", VideoAnalyzer.categorizeDuration(120));
        assertEquals("medium-video", VideoAnalyzer.categorizeDuration(600));
        assertEquals("long-video", VideoAnalyzer.categorizeDuration(3600));
    }

    @Test
    void testGetBaseName() {
        assertEquals("video", VideoAnalyzer.getBaseName("video.mp4"));
        assertEquals("my.video", VideoAnalyzer.getBaseName("my.video.mp4"));
        assertEquals("noext", VideoAnalyzer.getBaseName("noext"));
    }

    @Test
    void testExtractJsonDouble() {
        String json = """
                {
                    "format": {
                        "duration": "125.500",
                        "size": "12345678"
                    }
                }
                """;

        assertEquals(125.5, VideoAnalyzer.extractJsonDouble(json, "duration"), 0.01);
        assertEquals(12345678.0, VideoAnalyzer.extractJsonDouble(json, "size"), 0.01);
        assertEquals(0.0, VideoAnalyzer.extractJsonDouble(json, "nonexistent"), 0.01);
    }

    @Test
    void testExtractJsonInt() {
        String json = """
                {
                    "streams": [{
                        "width": 1920,
                        "height": 1080
                    }]
                }
                """;

        assertEquals(1920, VideoAnalyzer.extractJsonInt(json, "width"));
        assertEquals(1080, VideoAnalyzer.extractJsonInt(json, "height"));
    }

    @Test
    void testParseFfprobeOutput() {
        String json = """
                {
                    "streams": [{
                        "width": 1920,
                        "height": 1080,
                        "codec_type": "video"
                    }],
                    "format": {
                        "duration": "300.5",
                        "size": "50000000"
                    }
                }
                """;

        VideoAnalyzer.FfprobeResult result = analyzer.parseFfprobeOutput(json);
        assertNotNull(result);
        assertEquals(300.5, result.duration(), 0.01);
        assertEquals(1920, result.width());
        assertEquals(1080, result.height());
    }

    @Test
    void testParseFfprobeOutputEmpty() {
        VideoAnalyzer.FfprobeResult result = analyzer.parseFfprobeOutput("{}");
        assertNull(result, "Empty JSON should return null");
    }

    @Test
    void testMovieAnalysis() throws IOException {
        Path movFile = tempDir.resolve("recording.mov");
        Files.write(movFile, new byte[]{0x00, 0x00, 0x00, 0x14});

        FileMetadata fm = FileMetadata.of(movFile, tempDir, Files.size(movFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("Video"));
        assertTrue(result.summary().contains("MOV"));
        assertTrue(result.keywords().contains("mov"));
    }

    @Test
    void testWebmAnalysis() throws IOException {
        Path webmFile = tempDir.resolve("stream.webm");
        Files.write(webmFile, new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});

        FileMetadata fm = FileMetadata.of(webmFile, tempDir, Files.size(webmFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("Video"));
        assertTrue(result.keywords().contains("webm"));
    }

    private FileMetadata createMetadata(String name, FileUtils.FileType type) {
        return new FileMetadata(
                tempDir.resolve(name), name, name,
                name.contains(".") ? name.substring(name.lastIndexOf('.')) : "",
                type, null, 100, Instant.now(), null
        );
    }
}
