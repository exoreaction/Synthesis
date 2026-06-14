package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.AiClient;
import io.exoreaction.synthesis.analyzer.PresentationExtractor;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Extracts slides from a presentation PDF as individual PNG images.
 *
 * <p>Usage:
 * <pre>
 *   synthesis extract-slides presentation.pdf
 *   synthesis extract-slides presentation.pdf --output slides/
 *   synthesis extract-slides presentation.pdf --dpi 300 --with-readme
 *   synthesis extract-slides presentation.pdf --no-vision
 * </pre>
 *
 * <p>When AI is enabled (default), each slide gets a vision-generated description.
 * Use --no-vision to extract images only (faster, no API cost).
 */
@Command(
        name = "extract-slides",
        description = "Extract slides from a presentation PDF as PNG images",
        mixinStandardHelpOptions = true
)
public class ExtractSlidesCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(
            index = "0",
            description = "Path to the presentation PDF file"
    )
    private Path pdfPath;

    @Option(
            names = {"-o", "--output"},
            description = "Output directory for slide images (default: <pdf-name>-slides/)"
    )
    private Path outputDir;

    @Option(
            names = {"--dpi"},
            description = "Rendering DPI: 150 (web) or 300 (print). Default: 150",
            defaultValue = "150"
    )
    private int dpi;

    @Option(
            names = {"--no-vision"},
            description = "Disable AI vision descriptions for slides (faster, no API cost)",
            defaultValue = "false"
    )
    private boolean noVision;

    @Option(
            names = {"--with-readme"},
            description = "Generate a README.md summarizing all slides",
            defaultValue = "false"
    )
    private boolean withReadme;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show detailed output",
            defaultValue = "false"
    )
    private boolean verbose;

    @Override
    public Integer call() {
        try {
            AnsiOutput.printHeader("Synthesis - Extract Slides");

            // Resolve and validate PDF path
            Path resolvedPdf = pdfPath.toAbsolutePath().normalize();
            if (!Files.exists(resolvedPdf)) {
                AnsiOutput.printError("PDF not found: " + resolvedPdf);
                return 1;
            }
            if (!resolvedPdf.getFileName().toString().toLowerCase().endsWith(".pdf")) {
                AnsiOutput.printError("Not a PDF file: " + resolvedPdf);
                return 1;
            }

            // Determine output directory
            if (outputDir == null) {
                String baseName = resolvedPdf.getFileName().toString();
                baseName = baseName.substring(0, baseName.lastIndexOf('.'));
                outputDir = resolvedPdf.getParent().resolve(baseName + "-slides");
            }
            outputDir = outputDir.toAbsolutePath().normalize();

            AnsiOutput.printInfo("Source: " + resolvedPdf);
            AnsiOutput.printInfo("Output: " + outputDir);
            AnsiOutput.printInfo("DPI: " + dpi);

            // Initialize AI client (optional)
            AiClient client = null;
            if (!noVision) {
                Path workspaceRoot = parent.getWorkspaceRoot();
                SynthesisConfig config = ConfigLoader.load(workspaceRoot);

                if (config.getAi().isEnabled() && config.getAi().getVision().isEnabled()) {
                    Optional<AiClient> clientOpt = AiClient.create(config.getAi());
                    if (clientOpt.isPresent()) {
                        client = clientOpt.get();

                        // Estimate cost
                        // Quick page count from file
                        org.apache.pdfbox.pdmodel.PDDocument doc =
                                org.apache.pdfbox.Loader.loadPDF(resolvedPdf.toFile());
                        int pageCount = doc.getNumberOfPages();
                        doc.close();

                        double estimatedCost = PresentationExtractor.estimateCost(pageCount);
                        System.out.println();
                        AnsiOutput.printInfo(String.format(
                                "Found %d slides. Estimated vision cost: ~$%.2f",
                                pageCount, estimatedCost));

                        if (config.getAi().getVision().isConfirmBeforeScan()) {
                            System.out.print("  Continue with vision analysis? [Y/n] ");
                            if (System.console() != null) {
                                String response = System.console().readLine();
                                if (response != null && (response.trim().equalsIgnoreCase("n")
                                        || response.trim().equalsIgnoreCase("no"))) {
                                    client = null;
                                    AnsiOutput.printInfo("Vision analysis disabled for this extraction.");
                                }
                            }
                        }
                    }
                }

                if (client == null && !noVision) {
                    AnsiOutput.printInfo("Vision not available. Extracting images only.");
                }
            } else {
                AnsiOutput.printInfo("Vision disabled (--no-vision). Extracting images only.");
            }

            // Extract slides
            System.out.println();
            AnsiOutput.printInfo("Extracting slides...");

            PresentationExtractor extractor = new PresentationExtractor();
            PresentationExtractor.ExtractionResult result =
                    extractor.extractSlides(resolvedPdf, outputDir, dpi, client);

            // Print results
            System.out.println();
            AnsiOutput.printSuccess(String.format("Extracted %d slides to %s",
                    result.slidesExtracted(), outputDir));
            if (result.slidesDescribed() > 0) {
                AnsiOutput.printInfo(String.format("AI descriptions generated for %d slides",
                        result.slidesDescribed()));
            }

            // Generate README
            if (withReadme) {
                String readmeContent = extractor.generateReadme(result, resolvedPdf);
                Path readmePath = outputDir.resolve("README.md");
                Files.writeString(readmePath, readmeContent);
                AnsiOutput.printSuccess("Generated README.md");
            }

            // Show slide details in verbose mode
            if (verbose) {
                System.out.println();
                for (PresentationExtractor.SlideInfo slide : result.slides()) {
                    System.out.printf("  Slide %d: %s%n", slide.slideNumber(),
                            slide.imagePath().getFileName());
                    if (!slide.description().isEmpty()) {
                        String desc = slide.description();
                        if (desc.length() > 100) desc = desc.substring(0, 100) + "...";
                        System.out.printf("    %s%n", AnsiOutput.dim(desc));
                    }
                }
            }

            System.out.println();
            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("Slide extraction failed: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        }
    }
}
