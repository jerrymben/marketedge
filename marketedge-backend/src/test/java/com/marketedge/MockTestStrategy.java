package com.marketedge.strategy;

import com.marketedge.model.Candle;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class MockTestStrategy implements TradingStrategy {
    @Override
    public StrategySignal evaluate(List<Candle> historicalData, Candle latestCandle) {
        // Automatically trigger a mock BUY signal to verify end-to-end data transmission
        return StrategySignal.builder()
                .signalType(SignalType.BUY)
                .strategyName(getName())
                .symbol(latestCandle.getSymbol())
                .timeframe(latestCandle.getTimeframe())
                .entryPrice(latestCandle.getClosePrice())
                .reason("Mock validation test trigger successful")
                .build();
    }

    @Override public String getName() { return "Mock Test Strategy"; }
    @Override public int getMinimumBarsRequired() { return 1; }
    @Override public boolean supports(String symbol) { return "XAU/USD".equals(symbol); }
}