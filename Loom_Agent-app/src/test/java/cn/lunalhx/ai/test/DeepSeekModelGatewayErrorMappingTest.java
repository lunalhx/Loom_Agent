package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.infrastructure.gateway.DeepSeekModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.net.http.HttpHeaders;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class DeepSeekModelGatewayErrorMappingTest {

    @Test
    public void shouldMapOnlyExplicitContextErrorsToContextOverflow() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 2, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            DeepSeekModelGateway gateway = new DeepSeekModelGateway(
                    new MockEnvironment(), new ObjectMapper(), new ModelRuntimeProperties(), executor);
            Method method = DeepSeekModelGateway.class.getDeclaredMethod(
                    "toHttpException", int.class, String.class, HttpHeaders.class, String.class);
            method.setAccessible(true);

            ModelGatewayException ordinary400 = (ModelGatewayException) method.invoke(gateway, 400,
                    "{\"error\":{\"message\":\"temperature must be between 0 and 2\"}}",
                    HttpHeaders.of(Map.of(), (a, b) -> true), "deepseek-v4-flash");
            ModelGatewayException context422 = (ModelGatewayException) method.invoke(gateway, 422,
                    "{\"error\":{\"message\":\"context_length_exceeded: tokens exceed limit\"}}",
                    HttpHeaders.of(Map.of(), (a, b) -> true), "deepseek-v4-flash");

            assertEquals(ModelErrorCode.BAD_REQUEST, ordinary400.getErrorCode());
            assertEquals(ModelErrorCode.CONTEXT_OVERFLOW, context422.getErrorCode());
        } finally {
            executor.shutdownNow();
        }
    }

}
