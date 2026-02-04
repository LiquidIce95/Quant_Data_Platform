CREATE DATABASE IF NOT EXISTS prod_realtime_store;
USE prod_realtime_store;

CREATE TABLE IF NOT EXISTS derivatives_tick_market_data
(
	source_system_name        LowCardinality(String),
	component_instance_name   LowCardinality(String),
	ingestion_time            DateTime64(3),
	exchange_code             LowCardinality(String),
	symbol_code               LowCardinality(String),
	symbol_type               LowCardinality(String),
	price                     Float64,
	price_unit                LowCardinality(String),
	size                      Float64,
	size_unit                 LowCardinality(String),
	tick_time                 DateTime64(3),
	event_ref                 String,
	open_interest             Nullable(Int64),
	open_interest_unit        Nullable(String),
	INDEX idx_pk_minmax (source_system_name, symbol_code, tick_time) TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY toDate(tick_time)
ORDER BY (source_system_name, symbol_code, tick_time);

CREATE TABLE IF NOT EXISTS derivatives_l2_market_data
(
	source_system_name        LowCardinality(String),
	component_instance_name   LowCardinality(String),
	ingestion_time            DateTime64(3),
	exchange_code             Nullable(String),
	symbol_code               LowCardinality(String),
	symbol_type               Nullable(String),
	price                     Float64,
	price_unit                LowCardinality(String),
	size                      Float64,
	size_unit                 LowCardinality(String),
	side                      LowCardinality(String),
	level                     Int32,
	max_level                 Int32,
	l2_time                   DateTime64(3),
	event_ref                 String,
	open_interest             Nullable(Int64),
	open_interest_unit        Nullable(String),
	INDEX idx_pk_minmax (source_system_name, symbol_code, l2_time) TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY toDate(l2_time)
ORDER BY (source_system_name, symbol_code, l2_time);

CREATE TABLE IF NOT EXISTS indicators
(
	source_system_name        LowCardinality(String),
	component_instance_name   LowCardinality(String),
	category_schema_ref       String,
	ingestion_time            DateTime64(3),
	market_event_time         DateTime64(3),
	symbol_code               LowCardinality(String),
	indicator_scope           LowCardinality(String),
	indicator_name            LowCardinality(String),
	indicator_value           Float64,
	indicator_value_unit      LowCardinality(String),
	INDEX idx_pk_minmax (source_system_name, symbol_code, market_event_time) TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY toDate(market_event_time)
ORDER BY (source_system_name, symbol_code, market_event_time);

CREATE TABLE IF NOT EXISTS numeric_economic_data
(
	source_system_name        LowCardinality(String),
	component_instance_name   LowCardinality(String),
	ingestion_time            DateTime64(3),
	publisher_code            LowCardinality(String),
	release_code              LowCardinality(String),
	release_time              DateTime64(3),
	release_field             LowCardinality(String),
	release_field_value       Float64,
	release_field_value_unit  LowCardinality(String),
	indicator_ref             Nullable(String),
	INDEX idx_pk_minmax (source_system_name, release_code, release_time) TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY toDate(release_time)
ORDER BY (source_system_name, release_code, release_time);
