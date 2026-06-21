package com.marketedge.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * HTTP client configuration for outbound REST calls to the Twelve Data API.
 *
 * <p>We use {@link RestTemplate} (synchronous, thread-per-request) rather than
 * WebClient because the Twelve Data free tier is polled by a single
 * {@code @Scheduled} thread — there is no benefit to reactive non-blocking I/O
 * in a rate-limited, low-concurrency context.
 *
 * <p>Timeouts are kept tight so a slow API response does not block the scheduler
 * thread beyond one polling cycle.
 */
@Configuration
public class RestTemplateConfig {

    /**
     * Primary {@link RestTemplate} bean for all Twelve Data API calls.
     *
     * <ul>
     *   <li><b>Connect timeout</b> – 5 s: abort if TCP handshake takes too long.</li>
     *   <li><b>Read timeout</b>    – 10 s: abort if response body stalls.</li>
     *   <li>Registers a {@link MappingJackson2HttpMessageConverter} that also
     *       accepts {@code text/plain} responses — the Twelve Data API sometimes
     *       returns JSON with a {@code Content-Type: text/plain} header.</li>
     * </ul>
     */
    @Bean
    public RestTemplate twelveDataRestTemplate(RestTemplateBuilder builder) {

        // Custom converter that accepts both application/json AND text/plain
        // (Twelve Data occasionally serves JSON with text/plain content-type)
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter();
        converter.setSupportedMediaTypes(
                List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN));

        return builder
                .setConnectTimeout(Duration.ofSeconds(5)) // Correct setter for your version
                .setReadTimeout(Duration.ofSeconds(5))    // Update this too if you use it
                .build();
    }
}