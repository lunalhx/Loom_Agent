package cn.lunalhx.ai.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@ConditionalOnBean(StringRedisTemplate.class)
public class ModelConfigCacheRepository {

    private static final String KEY_PREFIX = "loom:agent:model-config:";

    private final StringRedisTemplate redisTemplate;

    public void save(String key, String value, Duration ttl) {
        redisTemplate.opsForValue().set(KEY_PREFIX + key, value, ttl);
    }

    public Optional<String> find(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(KEY_PREFIX + key));
    }

}
