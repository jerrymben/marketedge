package com.marketedge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket configuration for MarketEdge live data streaming.
 *
 * <h3>Topics the React frontend subscribes to:</h3>
 * <pre>
 *   /topic/candles/{symbol}/{timeframe}  – live OHLCV candle updates
 *   /topic/signals                       – strategy signal alerts
 *   /topic/health                        – system health heartbeat
 * </pre>
 *
 * <h3>React connection:</h3>
 * <pre>
 *   const client = new Client({ brokerURL: 'ws://localhost:8080/api/ws' });
 *   client.subscribe('/topic/candles/AAPL/1min', handler);
 * </pre>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker for /topic/** destinations
        registry.enableSimpleBroker("/topic");
        // Prefix for messages FROM the client (not used in Phase 4, reserved for Phase 5 orders)
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // Allow React dev server (port 3000) and production origins
                .setAllowedOriginPatterns("http://localhost:3000", "http://localhost:*", "https://*")
                // SockJS fallback for browsers that don't support native WebSocket
                .withSockJS();
    }
}
