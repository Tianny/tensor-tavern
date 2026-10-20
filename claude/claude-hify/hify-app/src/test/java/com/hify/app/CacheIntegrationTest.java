package com.hify.app;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.hify.common.config.CacheConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Cache + Redis 集成测试（依赖本地 Redis）：
 * 验证 @Cacheable 命中、真实 key 形如 hify:{cacheName}:{key}、各缓存名 TTL 正确。
 */
@SpringBootTest
@Import(CacheIntegrationTest.CountingService.class)
class CacheIntegrationTest {

    @Autowired
    private CountingService countingService;

    @Autowired
    private RedisCacheManager cacheManager;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @AfterEach
    void cleanup() {
        redisTemplate.delete("hify:provider-cache:cache-int-test-1");
        redisTemplate.delete("hify:provider-cache:cache-int-test-2");
    }

    /** 测试用服务：调用次数可观测，用于判断缓存是否命中 */
    @Service
    static class CountingService {

        private final AtomicInteger counter = new AtomicInteger();

        @Cacheable(cacheNames = CacheConfig.PROVIDER_CACHE, key = "#id")
        public String load(String id) {
            return "v" + counter.incrementAndGet();
        }
    }

    @Test
    void 二次调用命中缓存_底层存储是Redis() {
        String first = countingService.load("cache-int-test-1");
        String second = countingService.load("cache-int-test-1");

        assertThat(first).isEqualTo(second);           // 第二次命中缓存，方法不再执行

        // 删掉 Redis 里的 key 再调用，应重新执行方法（证明存储是 Redis 而非本地内存）
        redisTemplate.delete("hify:provider-cache:cache-int-test-1");
        String third = countingService.load("cache-int-test-1");
        assertThat(third).isNotEqualTo(second);
    }

    @Test
    void 缓存key带全局前缀和缓存名() {
        countingService.load("cache-int-test-2");

        assertThat(redisTemplate.hasKey("hify:provider-cache:cache-int-test-2")).isTrue();
    }

    @Test
    void 各缓存名TTL配置正确() {
        Map<String, RedisCacheConfiguration> configs = cacheManager.getCacheConfigurations();

        assertThat(configs.get(CacheConfig.PROVIDER_CACHE).getTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(configs.get(CacheConfig.AGENT_CACHE).getTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(configs.get(CacheConfig.SESSION_CACHE).getTtl()).isEqualTo(Duration.ofHours(2));

        // key 前缀：hify:{缓存名}:
        assertThat(configs.get(CacheConfig.PROVIDER_CACHE).getKeyPrefixFor(CacheConfig.PROVIDER_CACHE))
                .isEqualTo("hify:provider-cache:");
    }
}
