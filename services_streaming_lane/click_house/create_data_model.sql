CREATE DATABASE IF NOT EXISTS quant;
USE quant;

-- ===============================
-- Reference / dimension table
-- ===============================
CREATE TABLE IF NOT EXISTS trading_symbol
(
    code                 String,
    commodity            String,
    contract_description String,
    contract_size        Float64,
    tick_size            Float64,
    trading_hours        String,
    exchange             String,
    point_value          Float64
)
ENGINE = MergeTree
PARTITION BY tuple()
ORDER BY (code);

-- ===============================
-- Ingestion tables (historical)
-- Partition FIRST by symbol, THEN by week(event_time)
-- ===============================

CREATE TABLE IF NOT EXISTS market_order_levels
(
    ingestion_time  DateTime64(3),                 -- meta only
    event_time      DateTime64(3)       NOT NULL,  -- DESIGNATED TS
    trading_symbol  String,
    source          LowCardinality(String),        -- 'IB', etc.
    side            Enum8('bid' = 1, 'ask' = 2),
    level           UInt16,                        -- 0 = top of book
    price           Float64,
    size            UInt64,
    event_id        UUID           NULL,
    INDEX idx_event_time_minmax event_time TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY (trading_symbol, toStartOfWeek(event_time))
ORDER BY (trading_symbol, event_time, side, level);

CREATE TABLE IF NOT EXISTS market_trades
(
    ingestion_time  DateTime64(3),                 -- meta only
    event_time      DateTime64(3)       NOT NULL,  -- DESIGNATED TS
    trading_symbol  String,
    source          LowCardinality(String),
    price           Float64,
    size            UInt64,
    event_id        UUID           NULL,
    INDEX idx_event_time_minmax event_time TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY (trading_symbol, toStartOfWeek(event_time))
ORDER BY (trading_symbol, event_time);


