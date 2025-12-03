# business_use_case_evolution.md  
**(Motivation for Advanced Distributed Compute / Spark Introduction)**

## Background  
After releasing the first iteration of the Quant Data Platform, the organization achieved all primary goals:

- Realtime market data is delivered through ClickHouse with minimal latency.  
- Macro data lands automatically in the warehouse.  
- Data governance, lineage, and access control are centralized and reliable.  
- All realtime sources follow unified Category Schemas, eliminating naming inconsistencies.  
- Analysts consume data through a single OLAP interface without juggling multiple tools.

This greatly improved reliability and reduced duplicated work. Most analyst teams migrated their workflows to the platform.

And for some time, all was good.

Because the analysts now have access to lots of clean data they started to develop more advanced and complex models and algorithms. These advanced models started to perform more and more complicated computations. The streaming Database started to experience lags and if the team ran the computations on own hardware it still was too slow. Due to the financial gains driven by the platform, management has decided to further invest into the platform to solve this issue.

These new requirements exceed the computational capabilities of ClickHouse and cannot be handled efficiently within an OLAP engine.

---

# New Problem: Advanced Quant Modeling Bottlenecks

## Summary  
A specific team within the firm has begun developing advanced quantitative models that require computational patterns unavailable or impractical in ClickHouse. These include:

1. **Iterative algorithms**  
   - Fixed-point methods  
   - Newton–Raphson style optimizers  
   - Monte Carlo path simulations  
   - Nonlinear factor models  

2. **Large cross-instrument analytical joins**  
   - Multi-symbol volatility surface construction  
   - Cross-contract correlation matrices  
   - Intraday synthetic index creation across 50–200 symbols  

3. **Heavy rolling-window computations**  
   - Multi-window regressions (5min, 1h, 1d simultaneously)  
   - Cross-sectional factor estimators  
   - PCA-based features on thousands of points  

4. **Hybrid workloads (historical + realtime)**  
   - Compute features over 5+ years of historical data  
   - Update them every few seconds as new ticks arrive  
   - Persist updated factors for downstream ML pipelines  

5. **Distributed memory requirements**  
   Because the models need to scan large historical periods and recompute stateful metrics, single-machine OLAP execution is insufficient.

---

# Why ClickHouse Alone Cannot Solve This

Although ClickHouse excels in:

- low-latency writes,  
- high-throughput OLAP queries,  
- time-series analytics,

it is **not** designed for:

- iterative algorithms,  
- multi-pass computations,  
- cross-table joins at TB scale,  
- computation graphs or DAGs,  
- long-running stateful model updates.

Several POCs confirmed that these workloads either:

- exceed the memory of a single node,  
- cause unreasonably large temporary tables,  
- or run for minutes to hours — unacceptable for the modeling team.

The business impact: the most promising quant models cannot be deployed.

---

# New Requirement: Distributed Analytical Compute

The Chief Strategist and the Head of Trading concluded:

> “If these new models cannot run in production, the firm loses a major competitive advantage.”

This introduces a **new non-functional requirement** for the platform:

**R1: Provide a scalable distributed compute layer capable of iterative, cross-instrument, and large-window analytics.**

No existing platform component satisfies this.

---

# Solution: Introduce a Spark-Based Analytical Processor

To meet the new modeling requirements **without disturbing the core pipeline**, we extend the architecture by simply introducing a new processor and desination:

### Spark Analytical Processor (new component)
- Consumes cleaned Category Schema data directly from Kafka (Buffer).  
- Performs heavy distributed computations using Spark (batch, micro-batch, or structured streaming).  
- Produces model outputs as structured, low-latency key-value facts.  
- Writes results into **Redis** as a new destination for low-latency analytical consumption.

This yields:

- A **distributed execution engine** for heavy quant workloads.  
- Near-realtime model outputs for analysts.  
- No load impact on ClickHouse ingestion or queries.  
- Independent scaling and reliability.  
- Seamless fit into the existing conceptual architecture (just another Processor + Destination).

---

# Business Impact

- The new quant models can run at production-scale.  
- Redis-backed model features are available to analysts with sub-millisecond read latency.  
- Spark jobs leverage Kafka’s category-cleaned data directly — no duplication and no re-engineering of ingestion.  
- The platform supports new high-value analytical use cases without altering the core OLAP path.

---

# Conclusion

The platform evolution is driven by genuine business demand:

- The original architecture covers 90% of analytics.  
- A small but crucial segment requires **distributed compute beyond OLAP**.  
- Spark is the natural and industry-standard solution for this gap.  
- Redis provides a fast serving layer for model outputs.  

This evolution keeps the system clean, modular, cost-efficient, and aligned with real quant workflows — while providing the perfect opportunity to use Spark extensively and legitimately.
