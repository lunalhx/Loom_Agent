package cn.lunalhx.ai.test;

import cn.lunalhx.ai.infrastructure.mcp.McpInputSchemaSimplifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class McpInputSchemaSimplifierTest {

    private McpInputSchemaSimplifier simplifier;
    private McpJsonMapper jsonMapper;

    @Before
    public void setUp() {
        jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        simplifier = new McpInputSchemaSimplifier(jsonMapper, 160, 32, 12, 3000);
    }

    @Test
    public void nullSchemaReturnsValidObjectJson() throws Exception {
        String result = simplifier.simplify(null);
        assertEquals("{\"type\":\"object\"}", result);
        // must be parseable
        new ObjectMapper().readTree(result);
    }

    @Test
    public void outputIsAlwaysValidJson() throws Exception {
        McpSchema.JsonSchema schema = buildDeeplyNestedSchema();
        String result = simplifier.simplify(schema);
        JsonNode node = new ObjectMapper().readTree(result);
        assertTrue("Output must be a JSON object", node.isObject());
    }

    @Test
    public void deepNestedSchemasFlattened() throws Exception {
        McpSchema.JsonSchema schema = buildDeeplyNestedSchema();
        String result = simplifier.simplify(schema);

        // The root must have "properties", but no property value should contain nested "properties"
        JsonNode root = new ObjectMapper().readTree(result);
        JsonNode props = root.path("properties");
        assertTrue("Root must have properties", props.isObject());
        for (var entry : props.properties()) {
            JsonNode propValue = entry.getValue();
            assertFalse("Property " + entry.getKey() + " must not contain nested properties",
                    propValue.has("properties"));
            assertFalse("Property " + entry.getKey() + " must not contain anyOf",
                    propValue.has("anyOf"));
            assertFalse("Property " + entry.getKey() + " must not contain allOf",
                    propValue.has("allOf"));
            assertFalse("Property " + entry.getKey() + " must not contain oneOf",
                    propValue.has("oneOf"));
        }

        // Also check for $ref, $defs, definitions anywhere in output
        assertFalse("Must not contain $ref", result.contains("\"$ref\""));
        assertFalse("Must not contain $defs", result.contains("\"$defs\""));
        assertFalse("Must not contain definitions", result.contains("\"definitions\""));
        assertFalse("Must not contain additionalProperties", result.contains("additionalProperties"));
    }

    @Test
    public void longDescriptionTruncated() throws Exception {
        String longDesc = "a".repeat(500);
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "string");
        prop.put("description", longDesc);
        properties.put("longDescProp", prop);

        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);

        String result = simplifier.simplify(schema);
        JsonNode node = new ObjectMapper().readTree(result);
        JsonNode descNode = node.path("properties").path("longDescProp").path("description");
        assertTrue("Description must be present", descNode.isTextual());
        assertTrue("Description must be truncated to <= 160 chars",
                descNode.asText().length() <= 160);
        assertFalse("Description must not contain control chars",
                descNode.asText().matches(".*[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F].*"));
    }

    @Test
    public void enumSmallKeptLargeReplacedByCount() throws Exception {
        // Small enum (4 values) — kept
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> smallEnumProp = new LinkedHashMap<>();
        smallEnumProp.put("type", "string");
        smallEnumProp.put("enum", List.of("a", "b", "c", "d"));
        properties.put("smallEnum", smallEnumProp);

        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);
        String result = simplifier.simplify(schema);
        assertTrue("Small enum must be kept", result.contains("\"enum\":[\"a\",\"b\",\"c\",\"d\"]"));

        // Large enum (20 values) — replaced by count
        Map<String, Object> largeEnumProp = new LinkedHashMap<>();
        largeEnumProp.put("type", "string");
        List<String> largeValues = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            largeValues.add("val" + i);
        }
        largeEnumProp.put("enum", largeValues);
        Map<String, Object> props2 = new LinkedHashMap<>();
        props2.put("largeEnum", largeEnumProp);

        McpSchema.JsonSchema schema2 = new McpSchema.JsonSchema(
                "object", props2, null, null, null, null);
        String result2 = simplifier.simplify(schema2);
        assertFalse("Large enum must not have enum key", result2.contains("\"enum\":["));
        assertTrue("Large enum must show count in description",
                result2.contains("(enum: 20 values)"));
    }

    @Test
    public void arrayItemsShallowTypeOnly() throws Exception {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "object");
        // Deep nested inside items — should be dropped
        Map<String, Object> itemsProps = new LinkedHashMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("type", "string");
        nested.put("description", "nested description");
        itemsProps.put("nestedField", nested);
        items.put("properties", itemsProps);

        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "array");
        prop.put("items", items);
        prop.put("description", "An array of objects");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("arr", prop);

        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);
        String result = simplifier.simplify(schema);

        // Should only keep items.type, not nested properties
        assertTrue("Must have items", result.contains("\"items\":{"));
        assertTrue("Must have items.type", result.contains("\"items\":{\"type\":\"object\"}"));
        // items.properties should not exist (we only keep items.type)
    }

    @Test
    public void staysUnderCharCap() throws Exception {
        // Build a schema that would be large when fully serialized
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "string");
            prop.put("description", "Property number " + i + " with a rather long description to consume space");
            properties.put("prop" + i, prop);
        }

        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);

        // Use a tight cap to force progressive reduction
        McpInputSchemaSimplifier tightSimplifier =
                new McpInputSchemaSimplifier(jsonMapper, 160, 32, 12, 1200);
        String result = tightSimplifier.simplify(schema);
        assertTrue("Output must be within char cap: " + result.length() + " > 1200",
                result.length() <= 1200);
        // Must still be valid JSON
        new ObjectMapper().readTree(result);
    }

    @Test
    public void requiredPropsNeverDroppedByCap() throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> req1 = new LinkedHashMap<>();
        req1.put("type", "string");
        req1.put("description", "Required property one");
        properties.put("requiredA", req1);

        Map<String, Object> req2 = new LinkedHashMap<>();
        req2.put("type", "number");
        req2.put("description", "Required property two");
        properties.put("requiredB", req2);

        // Add many optional properties to blow past the cap
        for (int i = 0; i < 40; i++) {
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("type", "string");
            opt.put("description", "Optional property " + i + " with some padding text to make it bigger");
            properties.put("opt" + i, opt);
        }

        List<String> required = List.of("requiredA", "requiredB");
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", properties, required, null, null, null);

        // Very tight cap to force dropping optional properties
        McpInputSchemaSimplifier tightSimplifier =
                new McpInputSchemaSimplifier(jsonMapper, 160, 32, 12, 500);
        String result = tightSimplifier.simplify(schema);

        assertTrue("Required property requiredA must be preserved", result.contains("requiredA"));
        assertTrue("Required property requiredB must be preserved", result.contains("requiredB"));
        new ObjectMapper().readTree(result);
    }

    @Test
    public void cacheKeyDeterminism() throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> propA = new LinkedHashMap<>();
        propA.put("type", "string");
        propA.put("description", "First property");
        properties.put("alpha", propA);

        Map<String, Object> propB = new LinkedHashMap<>();
        propB.put("type", "number");
        propB.put("description", "Second property");
        properties.put("beta", propB);

        Map<String, Object> propC = new LinkedHashMap<>();
        propC.put("type", "boolean");
        propC.put("description", "Third property");
        properties.put("gamma", propC);

        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);

        String out1 = simplifier.simplify(schema);
        String out2 = simplifier.simplify(schema);
        assertEquals("Same schema must produce identical output", out1, out2);

        // Verify insertion order is preserved
        int posA = out1.indexOf("\"alpha\"");
        int posB = out1.indexOf("\"beta\"");
        int posC = out1.indexOf("\"gamma\"");
        assertTrue("alpha must appear before beta", posA < posB);
        assertTrue("beta must appear before gamma", posB < posC);
    }

    @Test
    public void emptyPropertiesEmitsEmptyObject() throws Exception {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", Map.of(), null, null, null, null);
        String result = simplifier.simplify(schema);
        assertTrue("Must contain properties key", result.contains("\"properties\":{}"));
        new ObjectMapper().readTree(result);
    }

    @Test
    public void nonObjectTypeSchemaMinimal() throws Exception {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "string", null, null, null, null, null);
        String result = simplifier.simplify(schema);
        assertEquals("{\"type\":\"string\"}", result);
        assertFalse("Must not contain properties", result.contains("properties"));
        new ObjectMapper().readTree(result);
    }

    @Test
    public void malformedNestedMapDoesNotThrow() {
        // Property value is a String instead of a Map
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("malformed", "not_a_map");

        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);
        String result = simplifier.simplify(schema);
        assertNotNull(result);
        try {
            new ObjectMapper().readTree(result);
        } catch (Exception e) {
            fail("Output must be valid JSON even with malformed input: " + e.getMessage());
        }
    }

    @Test
    public void typeAsListDoesNotThrow() throws Exception {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", List.of("string", "null"));
        prop.put("description", "Nullable string");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("nullableField", prop);

        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);
        String result = simplifier.simplify(schema);
        assertNotNull(result);
        JsonNode node = new ObjectMapper().readTree(result);
        assertEquals("string",
                node.path("properties").path("nullableField").path("type").asText());
    }

    @Test
    public void refPropertyReturnsMinimalObject() throws Exception {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("$ref", "#/$defs/Foo");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("refProp", prop);

        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);
        String result = simplifier.simplify(schema);
        JsonNode node = new ObjectMapper().readTree(result);
        JsonNode refPropNode = node.path("properties").path("refProp");
        assertEquals("object", refPropNode.path("type").asText());
        assertEquals("(ref)", refPropNode.path("description").asText());
    }

    private McpSchema.JsonSchema buildDeeplyNestedSchema() {
        // Build a schema with nested objects, anyOf, $ref, etc.
        Map<String, Object> leafProp = new LinkedHashMap<>();
        leafProp.put("type", "string");
        leafProp.put("description", "Leaf string");

        Map<String, Object> innerProperties = new LinkedHashMap<>();
        innerProperties.put("leaf", leafProp);

        Map<String, Object> innerObject = new LinkedHashMap<>();
        innerObject.put("type", "object");
        innerObject.put("properties", innerProperties);
        innerObject.put("required", List.of("leaf"));

        // anyOf for one property
        Map<String, Object> anyOf1 = new LinkedHashMap<>();
        anyOf1.put("type", "string");
        Map<String, Object> anyOf2 = new LinkedHashMap<>();
        anyOf2.put("type", "number");
        Map<String, Object> anyOfProp = new LinkedHashMap<>();
        anyOfProp.put("anyOf", List.of(anyOf1, anyOf2));
        anyOfProp.put("description", "Either string or number");

        Map<String, Object> topProperties = new LinkedHashMap<>();
        topProperties.put("nested", innerObject);
        topProperties.put("union", anyOfProp);

        return new McpSchema.JsonSchema(
                "object", topProperties, List.of("nested"), null, null,
                Map.of("ExternalRef", Map.of("type", "object")));
    }
}
