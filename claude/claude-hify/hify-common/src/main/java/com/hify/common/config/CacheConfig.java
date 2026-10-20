package com.hify.common.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Spring Cache + Redis 配置。
 * 业务模块用注解做 Cache-Aside（@Cacheable / @CachePut / @CacheEvict）：
 * {@code @Cacheable(cacheNames = CacheConfig.PROVIDER_CACHE, key = "#id")}。
 * 序列化与 RedisTemplate（RedisConfig）共用同一套规则，均为带 @class 的 JSON。
 * 参数默认值在 @Value 里，可通过 application.yml 的 hify.cache.* 覆盖。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Provider 配置缓存：Cache-Aside，TTL 30min（CLAUDE.md 缓存策略） */
    public static final String PROVIDER_CACHE = "provider-cache";

    /** Agent 配置缓存：Cache-Aside，TTL 30min */
    public static final String AGENT_CACHE = "agent-cache";

    /** 对话上下文缓存：TTL 2h */
    public static final String SESSION_CACHE = "session-cache";

    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            @Value("${hify.cache.key-prefix:hify:}") String keyPrefix,
            @Value("${hify.cache.default-ttl:30m}") Duration defaultTtl,
            @Value("${hify.cache.provider-cache-ttl:30m}") Duration providerCacheTtl,
            @Value("${hify.cache.agent-cache-ttl:30m}") Duration agentCacheTtl,
            @Value("${hify.cache.session-cache-ttl:2h}") Duration sessionCacheTtl) {

        RedisCacheConfiguration defaults = cacheConfig(keyPrefix, defaultTtl);

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                PROVIDER_CACHE, cacheConfig(keyPrefix, providerCacheTtl),
                AGENT_CACHE, cacheConfig(keyPrefix, agentCacheTtl),
                SESSION_CACHE, cacheConfig(keyPrefix, sessionCacheTtl));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                .build();
    }

    private RedisCacheConfiguration cacheConfig(String keyPrefix, Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                // key 形如 hify:provider-cache:1
                .computePrefixWith(name -> keyPrefix + name + ":")
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer(RedisConfig.createRedisObjectMapper())));
    }
}
