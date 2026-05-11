package com.example;

import com.example.config.ExtractorConfig;
import com.example.db.JobHistoryRepository;
import com.example.extractor.HttpClient;
import com.example.extractor.MetadataDiscovery;
import com.example.extractor.ODataExtractor;
import com.example.writer.CsvFileWriter;
import com.example.writer.JsonFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generic S/4HANA OData extractor.
 * Entity set and fields are fully dynamic — nothing is hardcoded.
 *
 * Usage:
 *   mvn compile exec:java                         (CLI extraction)
 *   mvn compile exec:java -Dexec.args="output json"
 *   mvn compile exec:java -Dexec.args="--web"     (Web UI on port 8080)
 *   mvn compile exec:java -Dexec.args="--web 9090" (Web UI on custom port)
 */
public class GenericExtractorApp {

    private static final Logger logger = LoggerFactory.getLogger(GenericExtractorApp.class);

    public static void main(String[] args) {
        // Web UI mode
        if (args.length > 0 && "--web".equalsIgnoreCase(args[0])) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
            try {
                ExtractorConfig config = ExtractorConfig.fromProperties();
                new WebUIServer(config, port).start();
                logger.info("Web UI is running. Open http://localhost:{} in your browser.", port);
                // Keep the main thread alive
                Thread.currentThread().join();
            } catch (Exception e) {
                logger.error("Failed to start Web UI: {}", e.getMessage(), e);
                System.exit(1);
            }
            return;
        }

        String outputDir = args.length > 0 ? args[0] : "output";
        String format = args.length > 1 ? args[1] : "csv";

        Path outputPath = Paths.get(outputDir);
        outputPath.toFile().mkdirs();

        JobHistoryRepository repo = new JobHistoryRepository();
        repo.initSchema();
        int jobId = (int) (System.currentTimeMillis() / 1000);

        try {
            ExtractorConfig config = ExtractorConfig.fromProperties();
            repo.insertJob(jobId, config.getEntitySet(), config.getExtractionMode());
            HttpClient httpClient = new HttpClient(config);

            logger.info("Starting S/4HANA generic extraction...");
            logger.info("Service path: {}", config.getServicePath());
            logger.info("Entity set: {}", config.getEntitySet());
            logger.info("Extraction mode: {}", config.getExtractionMode());
            logger.info("Output directory: {}", outputPath.toAbsolutePath());
            logger.info("Output format: {}", format);

            // Step 1: Discover fields from $metadata
            MetadataDiscovery metadata = new MetadataDiscovery(config, httpClient);
            List<String> fields = metadata.discoverFields();

            // Step 2: Extract data using discovered fields
            ODataExtractor extractor = new ODataExtractor(config, httpClient, fields);
            extractor.setJobContext(repo, jobId);

            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String mode = config.isDeltaLoad() ? "delta" : "full";
            String prefix = config.getEntitySet().toLowerCase() + "_" + mode + "_load_" + timestamp;
            Path outputFile = outputPath.resolve(prefix + "." + format);

            int count;
            if (config.isFullNoDelta() && "csv".equalsIgnoreCase(format)) {
                ODataExtractor.ParallelResult pr = extractor.extractParallelToCsvFiles(outputPath, prefix);
                count = pr.totalCount;
                if (!pr.partFiles.isEmpty()) {
                    com.example.writer.CsvFileMerger.merge(pr.partFiles, outputFile, true);
                } else {
                    java.nio.file.Files.createFile(outputFile);
                }
            } else if ("json".equalsIgnoreCase(format)) {
                List<Map<String, String>> allData = new ArrayList<>();
                count = extractor.extract(allData::addAll);
                new JsonFileWriter().write(allData, outputFile);
            } else {
                try (CsvFileWriter csvWriter = new CsvFileWriter()) {
                    csvWriter.open(outputFile);
                    count = extractor.extract(batch -> {
                        try {
                            csvWriter.writeBatch(batch);
                        } catch (java.io.IOException e) {
                            throw new RuntimeException("Failed to write CSV batch", e);
                        }
                    });
                }
            }

            logger.info("Extracted {} records from '{}'", count, config.getEntitySet());
            logger.info("Data written to {}", outputFile.toAbsolutePath());
            repo.updateJob(jobId, "completed", count, outputFile.toAbsolutePath().toString(), null);
        } catch (Exception e) {
            logger.error("Extraction failed: {}", e.getMessage(), e);
            repo.updateJob(jobId, "failed", 0, null, e.getMessage());
            System.exit(1);
        }
    }
}
