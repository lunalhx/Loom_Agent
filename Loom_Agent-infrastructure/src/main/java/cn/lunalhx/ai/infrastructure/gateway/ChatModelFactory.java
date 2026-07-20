package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.web.client.RestClient;

public interface ChatModelFactory {
    String provider();

    ChatModel create(ModelRuntimeProperties.ProviderConfig config, RestClient.Builder restClientBuilder);

    ChatOptions createOptions(ModelRuntimeProperties.ProviderConfig config, ChatPrompt prompt,
                               String resolvedModel, boolean stream);
}