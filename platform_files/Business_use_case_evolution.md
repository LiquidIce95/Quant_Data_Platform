# business_use_case_evolution.md  
**(Motivation for Advanced Distributed Compute / Spark Introduction)**

## Background  
After releasing the first iteration of the Quant Data Platform, the organization achieved all primary goals:

- Realtime market data is delivered through ClickHouse with minimal latency.  
- Macro data lands automatically in the warehouse.  
- Data governance, lineage, and access control are centralized and reliable.  
- All realtime sources follow unified Category Schemas.  
- Analysts consume data through a single OLAP interface without juggling multiple tools.

Reliability increased, duplication vanished, and most analyst teams standardized on the platform.

For a while, the system met all demands.

As clean data became abundant, analysts developed increasingly complex quantitative models. These models introduced heavy computational patterns, significantly exceeding what the streaming database or analyst desktops could handle. Despite optimizations, execution remained too slow. Given the financial value unlocked by the platform, management approved investment to address this bottleneck.

---

# New Problem: Advanced Quant Modeling Bottlenecks

## Summary  
A specific team developed advanced models requiring computational patterns that ClickHouse is not suited for:

1. **Iterative algorithms**  
   - Fixed-point iterations  
   - Newton–Raphson optimizers  
   - Monte Carlo simulations  
   - Nonlinear factor models  

2. **Large cross-instrument analytical joins**  
   - Volatility surface construction  
   - Cross-contract correlation matrices  
   - Intraday synthetic index construction  

3. **Heavy rolling-window computations**  
   - Multi-window regressions  
   - Cross-sectional factor estimation  
   - PCA-based features on dense time-series  

4. **Hybrid workloads (historical + realtime)**  
   - Compute features over multiple years  
   - Update them continuously as new ticks arrive  
   - Persist updated features for downstream ML pipelines  

5. **Distributed memory requirements**  
   These models require multi-pass scans over large historical datasets, exceeding what a single-node OLAP engine is designed to do.

---

# Why ClickHouse Alone Cannot Solve This

ClickHouse is designed for:

- high-throughput columnar analytics,  
- low-latency ingestion,  
- time-series OLAP.

It is **not** designed for:

- iterative algorithms,  
- multi-pass DAG computations,  
- large distributed joins requiring shuffle,  
- long-running analytical tasks,  
- continuous model recomputation across years of data.

POCs confirmed that such workloads either:

- exceed available memory,  
- require excessive temporary tables,  
- or run for minutes to hours — too slow for production.

The bottleneck is computation, not data access.

---

# New Requirement: Distributed Analytical Compute

Leadership concluded:

> “If these new models cannot run at scale, we lose a major competitive advantage.”

Thus a new platform requirement emerges:

**R1: Provide a scalable distributed compute layer for iterative, cross-instrument, and large-window analytics.**

No existing component satisfies this.

---

# Solution: Introduce a Spark-Based Analytical Processor

To meet these requirements **without modifying or overloading the core ingestion/OLAP pipeline**, we extend the architecture by introducing:

### Spark Analytical Processor (new component)

- Consumes cleaned Category Schema data directly from Kafka (Buffer).  
- Executes heavy distributed computations in Spark (batch, micro-batch, or structured streaming).  
- Produces model outputs as structured analytical facts.  
- Writes these results **back into ClickHouse** as a dedicated analytical table family.

This provides:

- A distributed compute engine for heavy quant workloads.  
- A unified serving layer: **ClickHouse remains the single analytical source of truth**.  
- No additional operational overhead from introducing new storage systems.  
- No load impact on the existing ingestion or OLAP queries.  
- Fully modular extension: Spark is simply another Processor writing to the existing Destination.

---

# Business Impact

- Advanced quant models run at production scale.  
- Model features become immediately available via ClickHouse with consistent semantics and existing tooling.  
- The serving layer remains simple — no Redis, no additional infra.  
- Kafka + Category Schemas ensure there is no duplicated or re-engineered ingestion path.  
- The platform supports new high-value analytical use cases with minimal architectural expansion.

---

# Conclusion

The platform evolves due to genuine business needs:

- The original architecture supports the majority of analytics.  
- A small but crucial segment requires **distributed compute beyond OLAP**.  
- Spark fills this gap naturally and industry-proven.  
- ClickHouse remains the unified serving destination, keeping the stack lean and avoiding unnecessary technologies.

This evolution preserves architectural cleanliness, reduces operational overhead, and provides the necessary computational power for next-generation quant workflows — while avoiding the introduction of Redis and keeping implementation straightforward.
