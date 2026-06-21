-- ══════════════════════════════════════════════════════════════════════════════
--  MarketEdge — Trade Record Schema
--  File: src/main/resources/db/schema_trade_record.sql
--
--  Run once against marketedge_db BEFORE starting the backend with
--  hbm2ddl.auto=validate, or the Hibernate schema check will fail
--  (TradeRecord entity maps to this table).
--
--  To apply:
--    psql -U postgres -d marketedge_db -f schema_trade_record.sql
-- ══════════════════════════════════════════════════════════════════════════════

-- ── 1. Sequence ───────────────────────────────────────────────────────────────
CREATE SEQUENCE IF NOT EXISTS trade_record_id_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

-- ── 2. Table ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS trade_record (
    -- Identity
    id                  BIGINT          NOT NULL DEFAULT nextval('trade_record_id_seq') PRIMARY KEY,
    signal_id           VARCHAR(36)     NOT NULL,          -- UUID from StrategySignal

    -- Instrument
    strategy_name       VARCHAR(50)     NOT NULL,
    symbol              VARCHAR(12)     NOT NULL,
    timeframe           VARCHAR(5)      NOT NULL,
    signal_type         VARCHAR(10)     NOT NULL,          -- BUY / SELL

    -- Prices
    entry_price         DECIMAL(18,4)   NOT NULL,
    stop_loss           DECIMAL(18,4)   NOT NULL,
    take_profit         DECIMAL(18,4)   NOT NULL,
    risk_reward_ratio   DECIMAL(8,4),

    -- Analytics
    confidence_score    INT             NOT NULL DEFAULT 0,
    breakout_strength   DECIMAL(6,4)    NOT NULL DEFAULT 0,
    retest_precision    DECIMAL(6,4)    NOT NULL DEFAULT 0,
    atr                 DECIMAL(18,4),                     -- ATR(14) at creation
    london_range_size   DECIMAL(18,4),                     -- LH - LL at creation

    -- Lifecycle
    signal_status       VARCHAR(20)     NOT NULL DEFAULT 'CREATED',
    trade_outcome       VARCHAR(20)     NOT NULL DEFAULT 'PENDING',

    -- Timestamps
    evaluated_at        TIMESTAMPTZ     NOT NULL,           -- when strategy fired
    triggered_at        TIMESTAMPTZ,                        -- when entry was touched
    closed_at           TIMESTAMPTZ,                        -- when TP or SL was hit
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    -- Context
    reason              TEXT
);

-- ── 3. Constraints ────────────────────────────────────────────────────────────
ALTER TABLE trade_record
    DROP CONSTRAINT IF EXISTS uq_trade_signal_id;
ALTER TABLE trade_record
    ADD  CONSTRAINT uq_trade_signal_id UNIQUE (signal_id);

-- ── 4. Indexes ────────────────────────────────────────────────────────────────
-- Primary lookup for dashboard (strategy × symbol, newest first)
DROP INDEX IF EXISTS idx_trade_strategy_symbol;
CREATE INDEX idx_trade_strategy_symbol ON trade_record (strategy_name, symbol);

-- Lifecycle tracker reads by status to find open signals quickly
DROP INDEX IF EXISTS idx_trade_signal_status;
CREATE INDEX idx_trade_signal_status ON trade_record (signal_status)
    WHERE signal_status IN ('CREATED', 'TRIGGERED');

-- Historical / backtesting range queries
DROP INDEX IF EXISTS idx_trade_evaluated_at;
CREATE INDEX idx_trade_evaluated_at ON trade_record (evaluated_at DESC);

-- Direct UUID lookup by SignalLifecycleTracker
DROP INDEX IF EXISTS idx_trade_signal_id;
CREATE INDEX idx_trade_signal_id ON trade_record (signal_id);

-- ── 5. Verify ─────────────────────────────────────────────────────────────────
-- \d trade_record