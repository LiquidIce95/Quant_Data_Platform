# ADR-007: Ingestion Ordering Strategy for Market Data (QuestDB vs ClickHouse)

**Status:** Proposed
**Date:** 2025-10-14
**Owners:** Data Platform / Trading Infra

---

## Context

Our market data pipeline ingests Level 2 (L2) and tick-by-tick (allLast) for the **front 8 futures months** of crude oil and natural gas. Queries by analysts and models are **almost always scoped to few symbols** (e.g., a specific futures contract) with time filters. We currently use Kafka → Spark → DB → Grafana, targeting **< 600 ms end-to-end** user-perceived latency.

A key design pressure is how the storage engine expects rows to be ordered at ingest time:

* **QuestDB** requires tables to be **globally ordered by the designated timestamp**. Any back-in-time arrival triggers **O3 (out-of-order) merges**, incurring CPU/IO.
* **Our workload** only needs **per-symbol ordering**, not global ordering across symbols. Kafka can naturally preserve per-key order; Spark executors can sort batches locally.

We need an architecture that reliably meets latency goals while keeping the system simple and scalable.

---

## Problem Statement

Without an ordering strategy, multiple parallel writers interleave timestamps across symbols, causing QuestDB to perform frequent O3 merges. This increases commit latency, spikes CPU/IO, and harms tail latency. A tempting workaround is to split into **many tables per symbol**, but that introduces operational complexity (schema/metadata sprawl, UNIONs for cross-symbol views, WAL/merge overhead) and is difficult to manage as symbol counts grow.

We must choose between:

1. Enforcing **global ordering** before ingest to QuestDB and keeping **one table per feed**.
2. Using a database that natively benefits from **per-symbol partitioning and per-symbol time ordering** (e.g., ClickHouse), fitting Kafka’s and Spark's parallelism model.

---

## Requirements

* **R1 — Latency:** End-to-end p95 < **600 ms** from IB receive to Grafana visible.
* **R2 — Query pattern:** Optimized for `WHERE symbol = ? AND ts BETWEEN ? AND ?`.
* **R3 — Scale:** Handle growth from tens/hundreds → thousands of symbols without architectural rework.
* **R4 — Simplicity:** Avoid exploding table counts (no per-symbol tables). Keep operational complexity reasonable.
* **R5 — Reliability:** Predictable performance under bursty markets; straightforward backfill strategy.

---

## Considered Options

### Option A — QuestDB + pre-ingest global sort (single ordered writer)

**Pattern:** IB → (optional Kafka) → *Ordering Gateway* (≤100–150 ms buffer; global `ORDER BY ts`) → QuestDB (ILP, WAL) → Grafana.

* **How it works**: A thin gateway receives all rows, buffers a sub-second window, sorts globally by designated timestamp, then writes in ascending order. QuestDB appends without O3 in steady state.
* **Pros**

  * Fastest write path when order is preserved; lowest CPU/IO.
  * Excellent for ultra-low latency at modest symbol counts.
* **Cons**

  * Single ordered writer is a **scaling choke** if symbol count or throughput rises.
  * Any drift from global order reintroduces O3 costs.
  * Backfills to older partitions remain expensive.
  * Adds architectural complexity and additional network hops
  * We need to send messages to kafka by key thus we need to implement a custom load balancer
* **Meets R1?** Yes (p95 ~210–315 ms with G=200 ms)
* **Meets R3?** Limited (writer bottleneck as symbols grow)

---

### Option B — ClickHouse + per-pod sort (no Kafka keying required)

**Pattern:** IB → Kafka (round-robin) → Spark (each pod sorts its batch by `(symbol, ts)`) → ClickHouse MergeTree → Grafana.

* **How it works**: Each Spark pod sorts its micro-batch locally; ClickHouse stores data **partitioned by symbol, sorted and indexed by timestamp within symbol**. No global ordering requirement; ingestion scales horizontally by pods.
* **Pros**

  * **Strong use of parallelism** ingestion; scales with symbol count and throughput.
  * Excellent for symbol-scoped queries; natural fit for Kafka and Spark.
  * Predictable latency without global-order fragility.
  * No use of custom partitioner for Kafka
  * Easier architecture
  * ClickHouse is better suited for horizontal scaling
* **Cons**

  * Clickhouse more complicated to set up leads to longer development time.
  * Slightly higher baseline than QuestDB’s ideal append path, still well <600 ms.
* **Meets R1?** Yes (p95 ~250–315 ms with G=200 ms).
* **Meets R3?** Strong (add pods/partitions to scale)


## Decision

Option B is clearly better, given its still within our latency goals. The Business case clearly prioritizes scalability and throughput than low latency solutions.

