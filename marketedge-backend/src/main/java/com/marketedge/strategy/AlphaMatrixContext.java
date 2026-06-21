package com.marketedge.strategy;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Strategy context holding computed previous-day reference levels for AlphaMatrixStrategy.
 */
@Getter
@Builder
@ToString
public class AlphaMatrixContext {

    private final LocalDate tradingDate;
    
    /** Previous Day High (highest high of full reference session) */
    private final BigDecimal pdh;
    
    /** Previous Day Low (lowest low of full reference session) */
    private final BigDecimal pdl;
    
    /** Previous Day London High */
    private final BigDecimal pdlh;
    
    /** Previous Day London Low */
    private final BigDecimal pdll;

    /**
     * Validates that all critical market data points were successfully resolved
     * and that the context is safe to run strategies against.
     */
    public boolean isBuyContextValid() {
        return tradingDate != null 
                && pdh != null 
                && pdl != null 
                && pdlh != null 
                && pdll != null;
    }
}