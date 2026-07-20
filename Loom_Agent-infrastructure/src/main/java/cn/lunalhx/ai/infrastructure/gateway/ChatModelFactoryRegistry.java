package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ChatModelFactoryRegistry {

    private final Map<String, ChatModelFactory> factories;

    public ChatModelFactoryRegistry(List<ChatModelFactory> factories) {
        Map<String, ChatModelFactory> collected = new LinkedHashMap<>();
        for (ChatModelFactory factory : factories) {
            String provider = normalize(factory.provider());
            if (provider.isBlank()) {
                throw new IllegalStateException("ChatModelFactory provider 不能为空");
            }
            if (collected.putIfAbsent(provider, factory) != null) {
                throw new IllegalStateException("重复的 ChatModelFactory provider: " + provider);
            }
        }
        this.factories = Map.copyOf(collected);
    }

    public ChatModelFactory require(String provider) {
        ChatModelFactory factory = factories.get(normalize(provider));
        if (factory == null) {
            throw new ModelGatewayException(ModelErrorCode.CONFIG_ERROR,
                    "不支持的 AI provider: " + provider, false, null, null);
        }
        return factory;
    }

    private String normalize(String provider) {
        return provider == null ? "" : provider.strip().toLowerCase(Locale.ROOT);
    }
}
