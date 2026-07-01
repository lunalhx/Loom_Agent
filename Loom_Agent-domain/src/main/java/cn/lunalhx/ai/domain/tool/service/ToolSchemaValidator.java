package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.ToolInputValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.resource.SchemaLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ToolSchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(ToolSchemaValidator.class);
    private static final int MAX_ERRORS = 5;

    private final ObjectMapper objectMapper;
    private final SchemaRegistry schemaRegistry;
    private final Map<String, Schema> schemaCache = new ConcurrentHashMap<>();

    public ToolSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SchemaLoader loader = SchemaLoader.builder()
                .fetchRemoteResources(false)
                .block(iri -> true)
                .build();
        this.schemaRegistry = SchemaRegistry.builder()
                .schemaLoader(loader)
                .defaultDialectId(SpecificationVersion.DRAFT_2020_12.getDialectId())
                .build();
    }

    public Schema compile(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new IllegalArgumentException("Schema JSON must not be blank");
        }
        try {
            JsonNode schemaNode = objectMapper.readTree(schemaJson);
            return schemaRegistry.getSchema(schemaNode);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to compile schema: " + e.getMessage(), e);
        }
    }

    public ToolInputValidationResult validate(String toolName, String schemaJson, JsonNode input) {
        try {
            Schema schema = schemaCache.computeIfAbsent(toolName, k -> compile(schemaJson));
            return doValidate(schema, input);
        } catch (IllegalArgumentException e) {
            log.error("Schema compilation failed for tool '{}': {}", toolName, e.getMessage());
            return ToolInputValidationResult.failure(List.of(
                    new ToolInputValidationResult.FieldError("", "schema_compile_error", e.getMessage())
            ));
        }
    }

    public ToolInputValidationResult validateWithSchema(String toolName, Schema schema, JsonNode input) {
        if (schema == null) {
            return ToolInputValidationResult.failure(List.of(
                    new ToolInputValidationResult.FieldError("", "missing_schema", "No schema available for tool: " + toolName)
            ));
        }
        return doValidate(schema, input);
    }

    private ToolInputValidationResult doValidate(Schema schema, JsonNode input) {
        List<Error> errors = schema.validate(input);
        if (errors.isEmpty()) {
            return ToolInputValidationResult.success();
        }
        List<ToolInputValidationResult.FieldError> fieldErrors = errors.stream()
                .sorted(Comparator.comparing(m -> m.getInstanceLocation() != null ? m.getInstanceLocation().toString() : ""))
                .limit(MAX_ERRORS)
                .map(m -> new ToolInputValidationResult.FieldError(
                        m.getInstanceLocation() != null ? m.getInstanceLocation().toString() : "",
                        m.getKeyword() != null ? m.getKeyword() : "unknown",
                        m.getMessage() != null ? m.getMessage() : "Validation failed"
                ))
                .collect(Collectors.toList());
        return ToolInputValidationResult.failure(fieldErrors);
    }

    public void evict(String toolName) {
        schemaCache.remove(toolName);
    }

    public void clear() {
        schemaCache.clear();
    }
}
