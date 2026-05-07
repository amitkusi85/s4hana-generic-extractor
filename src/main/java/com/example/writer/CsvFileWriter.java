package com.example.writer;

import com.opencsv.CSVWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes extracted data to a CSV file.
 * Supports streaming: open once, write batches as they arrive, then close.
 */
public class CsvFileWriter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(CsvFileWriter.class);

    private CSVWriter writer;
    private String[] headers;
    private int rowCount;

    public void open(Path outputFile) throws IOException {
        writer = new CSVWriter(new FileWriter(outputFile.toFile()));
    }

    public void writeBatch(List<Map<String, String>> batch) throws IOException {
        if (batch.isEmpty()) return;
        if (writer == null) throw new IllegalStateException("CsvFileWriter not opened");

        if (headers == null) {
            headers = batch.get(0).keySet().toArray(new String[0]);
            writer.writeNext(headers);
        }

        for (Map<String, String> row : batch) {
            String[] values = new String[headers.length];
            for (int i = 0; i < headers.length; i++) {
                values[i] = row.getOrDefault(headers[i], "");
            }
            writer.writeNext(values);
            rowCount++;
        }
        writer.flush();
    }

    public int getRowCount() {
        return rowCount;
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
            logger.info("Wrote {} rows to CSV", rowCount);
        }
    }
}
