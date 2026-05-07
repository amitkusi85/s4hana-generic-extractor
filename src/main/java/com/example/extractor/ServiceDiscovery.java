package com.example.extractor;

import com.example.config.ExtractorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers all available EntitySets from the OData $metadata document.
 */
public class ServiceDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(ServiceDiscovery.class);

    private final ExtractorConfig config;
    private final HttpClient httpClient;

    public ServiceDiscovery(ExtractorConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    /**
     * Returns all EntitySet names available in the configured OData service.
     */
    public List<String> discoverEntitySets() throws Exception {
        String metadataUrl = config.getBaseUrl() + config.getServicePath()
                + "/$metadata?sap-client=" + config.getClient();
        logger.info("Discovering entity sets from: {}", metadataUrl);

        String xml = httpClient.executeRequest(metadataUrl);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        List<String> entitySets = new ArrayList<>();
        NodeList entitySetNodes = doc.getElementsByTagNameNS("*", "EntitySet");
        for (int i = 0; i < entitySetNodes.getLength(); i++) {
            Element es = (Element) entitySetNodes.item(i);
            String name = es.getAttribute("Name");
            if (name != null && !name.isBlank()) {
                entitySets.add(name);
            }
        }

        logger.info("Discovered {} entity sets: {}", entitySets.size(), entitySets);
        return entitySets;
    }
}
