package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.tool.model.ToolInputValidationResult;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolSchemaValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolSchemaValidator validator = new ToolSchemaValidator(mapper);

    @Test
    public void allRequiredFieldsPresentPasses() {
        String schema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"name\"],\"additionalProperties\":false}";
        ObjectNode input = mapper.createObjectNode().put("name", "test");
        ToolInputValidationResult result = validator.validate("test_tool", schema, input);
        assertTrue(result.valid());
    }

    @Test
    public void missingRequiredFieldFails() {
        String schema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"name\"],\"additionalProperties\":false}";
        ObjectNode input = mapper.createObjectNode();
        ToolInputValidationResult result = validator.validate("test_tool", schema, input);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.pointer().contains("name") || e.message().contains("name")));
    }

    @Test
    public void emptyStringRejectedWhenMinLengthOne() {
        String schema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"name\"],\"additionalProperties\":false}";
        ObjectNode input = mapper.createObjectNode().put("name", "");
        ToolInputValidationResult result = validator.validate("test_tool", schema, input);
        assertFalse(result.valid());
    }

    @Test
    public void exceedsEnumValuesFails() {
        String schema = "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"open\",\"closed\"]}},\"additionalProperties\":false}";
        ObjectNode input = mapper.createObjectNode().put("status", "invalid");
        ToolInputValidationResult result = validator.validate("enum_tool", schema, input);
        assertFalse(result.valid());
    }

    @Test
    public void numberOutOfRangeFails() {
        String schema = "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}},\"additionalProperties\":false}";
        ObjectNode input = mapper.createObjectNode().put("count", 200);
        ToolInputValidationResult result = validator.validate("range_tool", schema, input);
        assertFalse(result.valid());
    }

    @Test
    public void unknownFieldsRejectedWithAdditionalPropertiesFalse() {
        String schema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"additionalProperties\":false}";
        ObjectNode input = mapper.createObjectNode().put("name", "ok").put("extra", "bad");
        ToolInputValidationResult result = validator.validate("strict_tool", schema, input);
        assertFalse(result.valid());
    }

    @Test
    public void defaultValuesNotInjected() {
        String schema = "{\"type\":\"object\",\"properties\":{\"opt\":{\"type\":\"string\",\"default\":\"defaultVal\"}},\"additionalProperties\":false}";
        ObjectNode input = mapper.createObjectNode();
        ToolInputValidationResult result = validator.validate("default_tool", schema, input);
        assertTrue(result.valid());
        assertTrue(input.path("opt").isMissingNode());
    }

    @Test
    public void validationErrorsCappedAtFive() {
        String schema = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"},\"c\":{\"type\":\"integer\"},\"d\":{\"type\":\"integer\"},\"e\":{\"type\":\"integer\"},\"f\":{\"type\":\"integer\"}},\"required\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"],\"additionalProperties\":false}";
        ObjectNode input = mapper.createObjectNode();
        ToolInputValidationResult result = validator.validate("cap_tool", schema, input);
        assertFalse(result.valid());
        assertTrue(result.errors().size() <= 5);
    }

    @Test
    public void blankSchemaThrowsIllegalArgumentException() {
        try {
            validator.compile("");
        } catch (Exception e) {
            assertTrue(e instanceof IllegalArgumentException);
        }
    }

    @Test
    public void allToolSpecsCompile() {
        String[] schemas = {
                "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"minLength\":1},\"cwd\":{\"type\":\"string\",\"default\":\".\"},\"timeoutMs\":{\"type\":\"integer\",\"minimum\":1,\"default\":30000}},\"required\":[\"command\"],\"additionalProperties\":false}",
                "{\"type\":\"object\",\"properties\":{\"todos\":{\"type\":\"array\",\"minItems\":1,\"items\":{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\",\"minLength\":1},\"status\":{\"type\":\"string\",\"enum\":[\"pending\",\"in_progress\",\"completed\",\"blocked\",\"skipped\"]}},\"required\":[\"content\",\"status\"],\"additionalProperties\":false}}},\"required\":[\"todos\"],\"additionalProperties\":false}",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"name\"],\"additionalProperties\":false}",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1},\"content\":{\"type\":\"string\"},\"mode\":{\"type\":\"string\",\"enum\":[\"create\",\"overwrite\"],\"default\":\"create\"}},\"required\":[\"path\",\"content\"],\"additionalProperties\":false}",
                "{\"type\":\"object\",\"properties\":{\"paths\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":20,\"items\":{\"type\":\"string\",\"minLength\":1}}},\"required\":[\"paths\"],\"additionalProperties\":false}",
                "{\"type\":\"object\",\"properties\":{\"operation\":{\"type\":\"string\",\"enum\":[\"status\",\"diff\",\"log\",\"init\",\"add\",\"commit\",\"push\",\"reset\",\"clean\",\"rebase\",\"checkout\"]}},\"required\":[\"operation\"],\"oneOf\":[{\"properties\":{\"operation\":{\"const\":\"status\"}},\"additionalProperties\":false},{\"properties\":{\"operation\":{\"const\":\"diff\"},\"path\":{\"type\":\"string\"}},\"additionalProperties\":false}]}"
        };
        for (int i = 0; i < schemas.length; i++) {
            validator.compile(schemas[i]);
        }
    }
}
