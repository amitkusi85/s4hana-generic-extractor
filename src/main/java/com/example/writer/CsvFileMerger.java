package com.example.writer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Merges multiple CSV files (with identical headers) into a single CSV file.
 * Keeps the header from the first file and skips the header line of every subsequent file.
 */
public class CsvFileMerger {

    private static final Logger logger = LoggerFactory.getLogger(CsvFileMerger.class);

    /**
     * @param parts ordered list of part files to merge (each must include a header row)
     * @param target output file (overwritten if exists)
     * @param deleteParts when true, deletes each part file after it is merged
     */
    public static void merge(List<Path> parts, Path target, boolean deleteParts) throws java.io.IOException {
        try (BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            boolean firstFile = true;
            long totalLines = 0;
            for (Path p : parts) {
                if (!Files.exists(p)) continue;
                try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    String line;
                    boolean isHeader = true;
                    while ((line = r.readLine()) != null) {
                        if (isHeader) {
                            isHeader = false;
                            if (firstFile) {
                                out.write(line);
                                out.newLine();
                                totalLines++;
                            }
                            continue;
                        }
                        out.write(line);
                        out.newLine();
                        totalLines++;
                    }
                }
                firstFile = false;
                if (deleteParts) {
                    try { Files.deleteIfExists(p); } catch (Exception ignore) {}
                }
            }
            logger.info("Merged {} part files into {} ({} lines)", parts.size(), target, totalLines);
        }
    }
}
