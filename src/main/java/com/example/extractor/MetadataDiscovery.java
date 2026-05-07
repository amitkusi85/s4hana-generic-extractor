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
 * Discovers entity type fields at runtime by parsing the OData $metadata document.
 * Resolves EntitySet name → EntityType → Property names.
 */
public class MetadataDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(MetadataDiscovery.class);

    private final ExtractorConfig config;
    private final HttpClient httpClient;

    public MetadataDiscovery(ExtractorConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    /**
     * Calls $metadata and returns the list of property names for the configured entity set.
     */
    public List<String> discoverFields() throws Exception {
        String metadataUrl = config.getBaseUrl() + config.getServicePath()
                + "/$metadata?sap-client=" + config.getClient();
        logger.info("Discovering fields from: {}", metadataUrl);

        String xml = httpClient.executeRequest(metadataUrl);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        // Step 1: Find EntitySet → resolve its EntityType
        String entityTypeName = resolveEntityType(doc, config.getEntitySet());
        if (entityTypeName == null) {
            throw new IllegalStateException(
                    "EntitySet '" + config.getEntitySet() + "' not found in $metadata");
        }
        logger.info("Resolved EntitySet '{}' -> EntityType '{}'", config.getEntitySet(), entityTypeName);

        // Step 2: Find EntityType → collect Property names
        List<String> fields = collectProperties(doc, entityTypeName);
        if (fields.isEmpty()) {
            throw new IllegalStateException(
                    "EntityType '" + entityTypeName + "' has no properties in $metadata");
        }
        logger.info("Discovered {} fields: {}", fields.size(), fields);
        return fields;
    }

    private String resolveEntityType(Document doc, String entitySetName) {
        NodeList entitySets = doc.getElementsByTagNameNS("*", "EntitySet");
        for (int i = 0; i < entitySets.getLength(); i++) {
            Element es = (Element) entitySets.item(i);
            if (entitySetName.equals(es.getAttribute("Name"))) {
                String fqType = es.getAttribute("EntityType");
                // EntityType is namespace-qualified, e.g. "ZNC_KX_SO_SRV.ZC_KX_SALESORDType"
                if (fqType.contains(".")) {
                    return fqType.substring(fqType.lastIndexOf('.') + 1);
                }
                return fqType;
            }
        }
        return null;
    }

    private List<String> collectProperties(Document doc, String entityTypeName) {
        List<String> fields = new ArrayList<>();
        NodeList entityTypes = doc.getElementsByTagNameNS("*", "EntityType");
        for (int i = 0; i < entityTypes.getLength(); i++) {
            Element et = (Element) entityTypes.item(i);
            if (entityTypeName.equals(et.getAttribute("Name"))) {
                NodeList properties = et.getElementsByTagNameNS("*", "Property");
                for (int j = 0; j < properties.getLength(); j++) {
                    Element prop = (Element) properties.item(j);
                    fields.add(prop.getAttribute("Name"));
                }
                break;
            }
        }
        return fields;
    }
}
