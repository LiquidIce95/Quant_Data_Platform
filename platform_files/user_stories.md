# User Stories

## Analysts

- As an analyst, I want to build realtime dashboards so that I can quickly visualize the data I care about.
- As an analyst, I want a data catalogue for the data warehouse to quickly discover and understand available datasets.
- As an analyst, I want to load historical data into Jupyter notebooks to run experiments.
- As an analyst, I want to query data in the data warehouse by timestamp range and symbol (crude oil, natural gas, S&P E-mini) to retrieve the exact data I need.
- As an analyst, I want the freshest data to be available in the warehouse within one hour so that my work is not delayed.
- As an analyst, I want the data to be cleaned and well documented so that I can focus on analysis rather than preprocessing.

## Quant / ML Engineers

- As an engineer, I want access to streaming sources (currently IB) through a unified, well-documented API to feed my models with realtime data.
- As an engineer, I want access to historical data from the DWH through an API to build and train models.
- As an engineer, I want consistent schemas and naming conventions to minimize preprocessing logic in my pipelines.

## Business Owners

- As a business owner, I want our data to be protected and user rights managed correctly to prevent unauthorized access or data corruption.
- As a business owner, I want the warehouse to support future company-wide datasets (suppliers, clients, employees, etc.).
- As a business owner, I want the platform to be scalable in compute and storage so it can grow with business needs.
- As a business owner, I want the operational cost of the platform to be as low as possible because it influences profitability.

## Data Engineers

- As a data engineer, I want the platform to be easy to maintain so that issues can be resolved quickly.
- As a data engineer, I want the platform to be automated, reliable, and fault-tolerant to avoid business disruptions.
- As a data engineer, I want a clear CI/CD process so deployments are predictable and safe.
- As a data engineer, I want observability (metrics, logs, alerts) to diagnose problems in realtime.

## Data Providers

- As a data provider, I want users to respect throttling and rate limits to ensure consistent and fair usage.


## Security / Compliance
- As a security officer, I want audit logs of data access and system changes so the platform remains compliant.
- As a security officer, I want encryption at rest and in transit so sensitive data is always protected.

## Operations / Site reliability Engineer
- As an SRE, I want health checks, liveness probes, and readiness probes so the platform can self-heal and scale.
- As an SRE, I want metrics (CPU, memory, throughput, lag) to automatically trigger scaling.

## Governance / Metadata
- As a data steward, I want versioned schemas and lineage so every dataset can be traced back to its source.

## FinOps
- As a FinOps engineer, I want per-component cost visibility so I can optimize cloud spend.

## Platform Team
- As a platform engineer, I want all environments (dev/test/prod) to be reproducible from configuration files so the system is consistent and deterministic.

