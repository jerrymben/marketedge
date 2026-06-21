package com.marketedge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MarketEdge – Application Entry Point
 *
 * <p>Bootstraps the Spring Boot context with:
 * <ul>
 *   <li>{@code @SpringBootApplication} — enables component scan, auto-config,
 *       and {@code @Configuration} processing for the {@code com.marketedge} package.</li>
 *   <li>{@code @EnableScheduling} — activates {@code @Scheduled} tasks used by
 *       the Twelve Data polling service (Phase 2).</li>
 *   <li>{@code @EnableCaching} — declared here as a safety net; it is also
 *       declared in {@link com.marketedge.config.RedisConfig} where the
 *       {@link org.springframework.cache.CacheManager} bean lives.</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>
 *   mvn spring-boot:run
 *   # or
 *   java -jar target/marketedge-backend-1.0.0-SNAPSHOT.jar
 * </pre>
 *
 * <p>Required environment variables (or override in application.yml):
 * <pre>
 *   DB_USERNAME          – PostgreSQL username  (default: postgres)
 *   DB_PASSWORD          – PostgreSQL password  (default: postgres)
 *   REDIS_HOST           – Redis hostname       (default: localhost)
 *   REDIS_PORT           – Redis port           (default: 6379)
 *   REDIS_PASSWORD       – Redis password       (default: empty)
 *   TWELVE_DATA_API_KEY  – Twelve Data API key  (required in Phase 2)
 * </pre>
 */
@SpringBootApplication
@EnableScheduling
public class MarketEdgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketEdgeApplication.class, args);
    }
}
