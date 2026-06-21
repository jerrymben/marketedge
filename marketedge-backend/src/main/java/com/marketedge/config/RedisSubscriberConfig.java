package com.marketedge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.marketedge.model.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

import java.nio.charset.StandardCharsets;

/**
 * Redis Pub/Sub subscriber configuration for the {@code market_data_stream} channel.
 *
 * <p>This wires up a {@link RedisMessageListenerContainer} that:
 * <ol>
 *   <li>Listens to the {@code market_data_stream} channel in a dedicated
 *       background thread (Lettuce async I/O → Spring listener thread).</li>
 *   <li>Routes each message to {@link CandleMessageHandler#onCandle(Candle, String)},
 *       where downstream processing happens (logging now; WebSocket fan-out in Phase 3).</li>
 * </ol>
 *
 * <p>The {@link MessageListenerAdapter} handles JSON → {@link Candle} deserialisation
 * automatically via the configured {@link ObjectMapper}.
 */
@Slf4j
@Configuration
public class RedisSubscriberConfig {

    // ─── Channel Names ────────────────────────────────────────────────────────
    public static final String CANDLE_CHANNEL  = "market_data_stream";
    public static final String SIGNAL_CHANNEL  = "strategy_signals";

    // ─── ObjectMapper (local instance; separate from Spring MVC's mapper) ────
    private ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    // ─── Listener Container Bean ──────────────────────────────────────────────

    /**
     * Container that holds all channel subscriptions.
     * Spring Boot manages its lifecycle (start on context refresh, stop on close).
     *
     * <p>Additional topics (e.g. strategy signals) can be registered here in
     * later phases by calling {@code container.addMessageListener(...)}.
     */
    @Bean
    public RedisMessageListenerContainer redisListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter   candleListenerAdapter) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Subscribe the candle handler to the market_data_stream channel
        container.addMessageListener(candleListenerAdapter, new ChannelTopic(CANDLE_CHANNEL));

        log.info("[Redis-Sub] Subscribed to channel '{}'", CANDLE_CHANNEL);
        return container;
    }

    /**
     * Adapter wrapping {@link CandleMessageHandler}.
     *
     * <p>The second argument {@code "onMessage"} tells the adapter which method
     * on the delegate to invoke with the raw {@link Message}.  We use the raw
     * {@link MessageListener} interface on the handler for explicit deserialisation
     * control (type-safe, no magic reflection).
     */
    @Bean
    public MessageListenerAdapter candleListenerAdapter(CandleMessageHandler handler) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(handler, "onMessage");
        return adapter;
    }

    // ─── Message Handler ──────────────────────────────────────────────────────

    /**
     * Handles incoming messages from the {@code market_data_stream} Redis channel.
     *
     * <p>Deserialises the JSON payload to a {@link Candle} and forwards it to
     * {@link com.marketedge.service.StrategyEngineService#onNewCandle(Candle)}
     * which fetches history from PostgreSQL and evaluates all registered strategies.
     */
    @Slf4j
    @org.springframework.stereotype.Component
    public static class CandleMessageHandler implements MessageListener {

        private final ObjectMapper objectMapper;
        private final com.marketedge.service.StrategyEngineService strategyEngineService;
        private final com.marketedge.service.WebSocketBroadcaster  webSocketBroadcaster;

        /**
         * Constructor injection ensures the strategy engine and WebSocket
         * broadcaster are available before the Redis listener container starts.
         */
        public CandleMessageHandler(
                com.marketedge.service.StrategyEngineService strategyEngineService,
                com.marketedge.service.WebSocketBroadcaster  webSocketBroadcaster) {
            this.strategyEngineService = strategyEngineService;
            this.webSocketBroadcaster  = webSocketBroadcaster;
            this.objectMapper = new ObjectMapper();
            this.objectMapper.registerModule(new JavaTimeModule());
            this.objectMapper.disable(
                com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            this.objectMapper.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }

        /**
         * Invoked by Spring Data Redis when a message arrives on any subscribed channel.
         *
         * @param message raw Redis message (body = JSON bytes, channel = channel name bytes)
         * @param pattern pattern that matched (null for exact-channel subscriptions)
         */
        @Override
        public void onMessage(Message message, byte[] pattern) {
            String rawJson = new String(message.getBody(), StandardCharsets.UTF_8);
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);

            log.debug("[Redis-Sub] Received on channel '{}': {}", channel, rawJson);

            try {
                Candle candle = deserialise(rawJson);
                if (candle != null) {
                    onCandle(candle, channel);
                }
            } catch (Exception e) {
                log.error("[Redis-Sub] Failed to deserialise candle from channel '{}': {} | payload={}",
                        channel, e.getMessage(), rawJson);
            }
        }

        /**
         * Business handler called with a fully deserialised {@link Candle}.
         *
         * <p>Logs the received tick and dispatches it to the
         * {@link com.marketedge.service.StrategyEngineService} for strategy evaluation.
         *
         * @param candle  the live candle received from the publisher
         * @param channel the Redis channel name
         */
        public void onCandle(Candle candle, String channel) {
            log.info("[Redis-Sub] ✔ New candle — {}/{} O={} H={} L={} C={} @ {}",
                    candle.getSymbol(),
                    candle.getTimeframe(),
                    candle.getOpenPrice(),
                    candle.getHighPrice(),
                    candle.getLowPrice(),
                    candle.getClosePrice(),
                    candle.getTimestamp());

            // ── Dispatch to Strategy Engine ───────────────────────────────
            strategyEngineService.onNewCandle(candle);

            // ── Phase 4: Broadcast candle to React clients via WebSocket ────
            webSocketBroadcaster.broadcastCandle(candle);
        }

        // ── Deserialisation helper ────────────────────────────────────────

        /**
         * Deserialises a JSON string to a {@link Candle}.
         *
         * <p>The payload from {@code GenericJackson2JsonRedisSerializer} looks like:
         * {@code {"@class":"com.marketedge.model.Candle","id":null,...}}.
         * Jackson maps this directly to {@link Candle} because all field names align.
         */
        private Candle deserialise(String json) throws Exception {
            return objectMapper.readValue(json, Candle.class);
        }
    }
}