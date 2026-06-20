package cn.lunalhx.ai.domain.model.service;

import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

public class OutputFormatValidator {

    private final ObjectMapper objectMapper;

    public OutputFormatValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(OutputFormat outputFormat, String output) {
        if (StringUtils.isBlank(output)) {
            throw new ModelGatewayException(ModelErrorCode.OUTPUT_EMPTY, ModelErrorCode.OUTPUT_EMPTY.message(), false, null, null);
        }
        if (OutputFormat.JSON_OBJECT != outputFormat) {
            return;
        }
        try {
            objectMapper.readTree(output);
        } catch (Exception e) {
            throw new ModelGatewayException(ModelErrorCode.VALIDATION_ERROR, ModelErrorCode.VALIDATION_ERROR.message(), false, null, e);
        }
    }

}
