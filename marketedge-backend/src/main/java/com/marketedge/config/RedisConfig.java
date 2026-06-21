package com.marketedge.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis Configuration for MarketEdge.
 *
 * <p>Registers two key Spring beans:
 * <ol>
 *   <li>{@link RedisTemplate} – general-purpose key/value access with JSON
 *       serialization (used by services for pub/sub and manual cache ops).</li>
 *   <li>{@link RedisCacheManager} – drives {@code @Cacheable / @CacheEvict}
 *       annotations with per-cache TTL policies.</li>
 * </ol>
 *
 * <p>Connection parameters (host, port, password, pool) are resolved from
 * {@code application.yml → spring.data.redis.*}; no connection factory bean
 * is needed here because Spring Boot auto-configures Lettuce.
 */
@Configuration
@EnableCaching   // activates the Spring Cache proxy layer
public class RedisConfig {

    // ─── Object Mapper (shared between both beans) ────────────────────────────

    /**
     * Custom {@link ObjectMapper} with Java 8 time support.
     *
     * <p>Activating {@code activateDefaultTyping} ensures the serialized JSON
     * embeds a {@code @class} field so the deserializer can reconstruct the
     * exact runtime type — important when values are {@code Object} or polymorphic.
     */
    private ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());                       // LocalDateTime etc.
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);   // ISO-8601 strings
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return mapper;
    }

    // ─── RedisTemplate Bean ───────────────────────────────────────────────────

    /**
     * General-purpose {@link RedisTemplate} wired with:
     * <ul>
     *   <li><b>Key serializer</b>: {@link StringRedisSerializer} — human-readable
     *       keys like {@code candle:cache:AAPL:1min}</li>
     *   <li><b>Value serializer</b>: {@link GenericJackson2JsonRedisSerializer} —
     *       JSON with embedded type info, handles {@link com.marketedge.model.Candle}
     *       lists and arbitrary DTOs.</li>
     *   <li><b>Hash key/value</b>: same pair — used for Redis Hashes in Phase 3.</li>
     * </ul>
     *
     * <p>Inject this bean anywhere with:
     * <pre>{@code
     *   @Autowired
     *   private RedisTemplate<String, Object> redisTemplate;
     * }</pre>
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory) {

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        // Key serializers
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);

        // Value serializers
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        // Default serializer for anything else
        template.setDefaultSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }

    // ─── Cache Manager Bean ───────────────────────────────────────────────────

    /**
     * {@link RedisCacheManager} with a global default configuration and
     * per-cache TTL overrides.
     *
     * <p>Cache name → TTL mapping:
     * <pre>
     *   candles      → 5 min  (live OHLCV data, refreshed by scheduler)
     *   symbols      → 60 min (static-ish; list of tracked tickers)
     *   strategies   → 10 min (calculated strategy metrics)
     *   health       → 30 sec (system health snapshot)
     * </pre>
     */
    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory) {

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        // ── Default config (applied to any cache not listed in overrides) ──
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .disableCachingNullValues()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(serializer));

        // ── Per-cache TTL overrides ────────────────────────────────────────
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        cacheConfigurations.put("candles",
                defaultConfig.entryTtl(Duration.ofMinutes(5)));

        cacheConfigurations.put("symbols",
                defaultConfig.entryTtl(Duration.ofHours(1)));

        cacheConfigurations.put("strategies",
                defaultConfig.entryTtl(Duration.ofMinutes(10)));

        cacheConfigurations.put("health",
                defaultConfig.entryTtl(Duration.ofSeconds(30)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()   // tie cache ops to active @Transactional
                .build();
    }
}