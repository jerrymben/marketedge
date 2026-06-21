package com.marketedge.strategy;

import com.marketedge.model.Candle;
import com.marketedge.model.TradeRecord;
import com.marketedge.repository.TradeRecordRepository;
import com.marketedge.strategy.SignalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignalLifecycleTracker {

    private static final int EXPIRY_HOURS = 24;

    private final TradeRecordRepository tradeRecordRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public void evaluate(Candle latestCandle) {
        if (latestCandle == null) return;

        final String symbol = latestCandle.getSymbol();
        final String timeframe = latestCandle.getTimeframe();

        List<TradeRecord> open = tradeRecordRepository.findOpenSignals(symbol, timeframe);
        if (open == null || open.isEmpty()) return;

        log.debug("[Lifecycle] {} open signal(s) for {}/{} @ {}",
                open.size(), symbol, timeframe, latestCandle.getTimestamp());

        for (TradeRecord record : open) {
            try {
                process(record, latestCandle);
            } catch (Exception e) {
                log.error("[Lifecycle] Error on signal {}: {}",
                        shortId(record), e.getMessage(), e);
            }
        }
    }

    private void process(TradeRecord record, Candle candle) {
        if (record == null || candle == null) return;

        final LocalDateTime now = LocalDateTime.now();
        final LocalDateTime expiryTime = record.getEvaluatedAt() != null
                ? record.getEvaluatedAt().plusHours(EXPIRY_HOURS)
                : now;

        // Expiry check
        if (record.getSignalStatus() == SignalStatus.CREATED && now.isAfter(expiryTime)) {
            record.markExpired(now);
            tradeRecordRepository.save(record);

            log.info("[Lifecycle] ⏱ EXPIRED {} {}/{} (>{}h old)",
                    shortId(record), record.getSymbol(), record.getTimeframe(), EXPIRY_HOURS);

            broadcast(record);
            return;
        }

        switch (record.getSignalStatus()) {

            case CREATED:
                if (touches(candle, record.getEntryPrice())) {
                    record.markTriggered(now);
                    tradeRecordRepository.save(record);

                    log.info("[Lifecycle] ⚡ TRIGGERED {} {}/{} Entry={} Conf={}",
                            shortId(record),
                            record.getSymbol(),
                            record.getTimeframe(),
                            record.getEntryPrice(),
                            record.getConfidenceScore());

                    broadcast(record);
                }
                break;

            case TRIGGERED:
                if (touches(candle, record.getStopLoss())) {
                    record.markSlHit(now);
                    tradeRecordRepository.save(record);

                    log.info("[Lifecycle] ❌ SL_HIT {} {}/{} SL={} Conf={}",
                            shortId(record),
                            record.getSymbol(),
                            record.getTimeframe(),
                            record.getStopLoss(),
                            record.getConfidenceScore());

                    broadcast(record);

                } else if (touches(candle, record.getTakeProfit())) {
                    record.markTpHit(now);
                    tradeRecordRepository.save(record);

                    log.info("[Lifecycle] ✅ TP_HIT {} {}/{} TP={} Conf={}",
                            shortId(record),
                            record.getSymbol(),
                            record.getTimeframe(),
                            record.getTakeProfit(),
                            record.getConfidenceScore());

                    broadcast(record);
                }
                break;

            default:
                log.trace("[Lifecycle] Signal {} already terminal ({})",
                        shortId(record), record.getSignalStatus());
        }
    }

    private boolean touches(Candle candle, java.math.BigDecimal price) {
        if (candle == null || price == null) return false;

        return candle.getLowPrice() != null
                && candle.getHighPrice() != null
                && candle.getLowPrice().compareTo(price) <= 0
                && candle.getHighPrice().compareTo(price) >= 0;
    }

    private void broadcast(TradeRecord record) {
        try {
            Map<String, Object> payload = new HashMap<>();

            payload.put("type", "LIFECYCLE_UPDATE");
            payload.put("signalId", record.getSignalId());
            payload.put("symbol", record.getSymbol());
            payload.put("timeframe", record.getTimeframe());
            payload.put("strategyName", record.getStrategyName());
            payload.put("status", safeEnum(record.getSignalStatus()));
            payload.put("outcome", safeEnum(record.getTradeOutcome()));
            payload.put("confidence", record.getConfidenceScore());

            payload.put("triggeredAt",
                    record.getTriggeredAt() != null ? record.getTriggeredAt().toString() : null);

            payload.put("closedAt",
                    record.getClosedAt() != null ? record.getClosedAt().toString() : null);

            messagingTemplate.convertAndSend("/topic/lifecycle", payload);

        } catch (Exception e) {
            log.warn("[Lifecycle] WS broadcast failed for {}: {}",
                    shortId(record), e.getMessage());
        }
    }

    private static String shortId(TradeRecord r) {
        if (r == null || r.getSignalId() == null) return "UNKNOWN";
        return r.getSignalId().length() >= 8
                ? r.getSignalId().substring(0, 8)
                : r.getSignalId();
    }

    private static String safeEnum(Enum<?> e) {
        return e != null ? e.name() : null;
    }
}