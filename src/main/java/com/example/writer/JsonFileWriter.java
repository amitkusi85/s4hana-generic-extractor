package com.example.writer;

import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes extracted data to a JSON file.
 */
public class JsonFileWriter {

    private static final Logger logger = LoggerFactory.getLogger(JsonFileWriter.class);

    public void write(List<Map<String, String>> data, Path outputFile) throws IOException {
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(data);
        Files.writeString(outputFile, json);
        logger.info("Wrote {} records to {}", data.size(), outputFile);
    }
}
