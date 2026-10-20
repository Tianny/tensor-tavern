package com.hify.common.config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局时间序列化。
 * 通过 Jackson2ObjectMapperBuilderCustomizer 定制 Spring Boot 自动装配的 ObjectMapper，
 * 而不是自建 ObjectMapper Bean——保留 Boot 的其余默认（模块自动发现等）。
 * Redis 用的 ObjectMapper 在 RedisConfig 独立配置，两边互不影响。
 */
@Configuration
public class JacksonConfig {

    /** LocalDateTime 格式：ISO 8601 无毫秒，如 2026-10-17T14:30:05 */
    public static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** LocalDate 格式：如 2026-10-17 */
    public static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer timeFormatCustomizer() {
        return builder -> builder
                .modulesToInstall(new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializers(new LocalDateTimeSerializer(LOCAL_DATE_TIME),
                        new LocalDateSerializer(LOCAL_DATE))
                .deserializers(new LocalDateTimeDeserializer(LOCAL_DATE_TIME),
                        new LocalDateDeserializer(LOCAL_DATE));
    }
}
