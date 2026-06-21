-- ═══════════════════════════════════════════════════════════════════════════
--  MarketEdge – Corrected Database Schema
--  File: src/main/resources/db/schema.sql
--
--  ROOT-CAUSE FIXES (Issue #3, #5):
--
--  Fix 1: symbol VARCHAR(10) → VARCHAR(12)
--    "GBP/CAD", "XAU/USD", "USD/JPY" = 7 chars each.
--    VARCHAR(10) was technically fine for these, but the Candle.java entity
--    now declares length=12 so this DDL must match for Hibernate validate.
--
--  Fix 2: timestamp TIMESTAMP → TIMESTAMPTZ
--    Hibernate maps java.time.Instant to TIMESTAMPTZ in PostgreSQL.
--    The original schema used plain TIMESTAMP (no timezone), causing a
--    Hibernate SchemaManagementException on startup with ddl-auto=validate.
--    TIMESTAMPTZ stores the timezone offset and correctly round-trips Instant.
--
--  Fix 3: volume column added, nullable
--    Forex symbols return null/zero volume from Twelve Data.
--    The column must not have NOT NULL so these rows can be inserted.
--
--  Fix 4: candle_id_seq created explicitly
--    Using BIGSERIAL auto-creates the sequence. If the table was already
--    created with BIGSERIAL, this sequence already exists — the IF NOT EXISTS
--    guards are safe to re-run.
--
--  HOW TO APPLY:
--    If the table does NOT yet exist:
--      psql -U postgres -d marketedge_db -f schema.sql
--
--    If the table DOES exist with old column types (the common case):
--      Run the migration block at the bottom of this file instead.
-- ═══════════════════════════════════════════════════════════════════════════


-- ── 1. Sequence ─────────────────────────────────────────────────────────────
-- BIGSERIAL creates candle_id_seq automatically.
-- If you previously used BIGSERIAL this already exists; the block is safe.
CREATE SEQUENCE IF NOT EXISTS candle_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


-- ── 2. Table (fresh install) ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS candle (
    id          BIGINT          NOT NULL DEFAULT nextval('candle_id_seq') PRIMARY KEY,

    -- FIX 1: VARCHAR(12) for FX pairs like "GBP/CAD", "XAU/USD"
    symbol      VARCHAR(12)     NOT NULL,

    timeframe   VARCHAR(5)      NOT NULL,

    open_price  DECIMAL(18, 4)  NOT NULL,
    high_price  DECIMAL(18, 4)  NOT NULL,
    low_price   DECIMAL(18, 4)  NOT NULL,
    close_price DECIMAL(18, 4)  NOT NULL,

    -- FIX 2: TIMESTAMPTZ (not plain TIMESTAMP) so Hibernate validate passes
    -- when java.time.Instant is used in the entity.
    timestamp   TIMESTAMPTZ     NOT NULL,

    -- FIX 3: volume is nullable for Forex instruments (no exchange volume data)
    volume      DECIMAL(18, 4)
);


-- ── 3. Unique constraint ─────────────────────────────────────────────────────
ALTER TABLE candle
    DROP CONSTRAINT IF EXISTS uq_candle_symbol_timeframe_timestamp;

ALTER TABLE candle
    ADD CONSTRAINT uq_candle_symbol_timeframe_timestamp
    UNIQUE (symbol, timeframe, timestamp);


-- ── 4. Index ─────────────────────────────────────────────────────────────────
DROP INDEX IF EXISTS idx_candle_lookup_sort;
CREATE INDEX idx_candle_lookup_sort ON candle (symbol, timeframe, timestamp);


-- ═══════════════════════════════════════════════════════════════════════════
--  MIGRATION — Run this block ONLY if the table already exists.
--  Safely alters column types in-place.  Data is preserved.
-- ═══════════════════════════════════════════════════════════════════════════

-- Step 1: Widen symbol column from VARCHAR(10) to VARCHAR(12)
ALTER TABLE candle ALTER COLUMN symbol TYPE VARCHAR(12);

-- Step 2: Convert timestamp column from TIMESTAMP to TIMESTAMPTZ.
--   USING clause re-interprets the stored values as UTC (which they already are).
ALTER TABLE candle
    ALTER COLUMN timestamp TYPE TIMESTAMPTZ
    USING timestamp AT TIME ZONE 'UTC';

-- Step 3: Add volume column if it doesn't exist yet
ALTER TABLE candle ADD COLUMN IF NOT EXISTS volume DECIMAL(18, 4);

-- Step 4: Verify final state
-- \d candle