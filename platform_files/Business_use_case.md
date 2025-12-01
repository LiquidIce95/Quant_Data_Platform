
# Quant Data Platform

This is a portfolio project to showcase understanding, skills and expertise in the field of Data Engineering. This project has been done in the context of the business case below.


# Business Case:  
You got hired as a Data Engineer at a commodity trading firm in Zug.  

The firm hired you to solve the following two major problems.  

---

# Problem 1 Manual Macro Data Handling:  
Right now the analysts manually download CSV files containing macro data for US Oil (futures) and store them on SharePoint. Until now it wasn’t a problem because most of these reports are published weekly or monthly. However, sometimes analysts forget to download these reports and the organization of these files varies widely amongst analysis teams, leading to duplicate and inconsistent work. The firm also would like to start using internal data like clients, employee or supplier data to make better business decisions. Most of the analysts are comfortable with basic SQL.

## Solution 1:
Design and implement a Datawarehouse which serves as a centralized place for gathering all data the company is interested in using and preparing the data for each team.

Metrics of success:  
- Pipeline reliability: 99% (out of 100 proper releases, 99 work flawlessly).
- Pipeline timeliness: the released reports should be in the data warehouse within 1 hours of release.
- Easy-to-understand and explorable as well as scalable data warehouse (clean data catalogue and data model).
- Clear and automated data and dev Ops
- Clear and sensisble Data governance
- Clear and sensible Access and User management
- Quickly develop a proof of concepts with the most important data sources for one team of analysts

---

# Problem 2:  
The firm uses realtime data from many sources, right now each source is handled by some team that happened to understand the data best. However many sources are needed by multiple teams and not every team does a good job of maintaining the data source also leading to lots of duplicate work and disorganization. Even though the data usually represents the same thing the naming is widly inconsistent which makes it difficult to find related data. To make matters worse, the source systems capabilities are exhausted for delievering that realtime data and users of these systems experience lags. Lastly, if the system is overwhelmed and the data is not saved in time, the firm loses valuable realtime data. This can happen even when the system is not lagging since the realtime data can burst in unexpected ways. The teams also need the data in different tools which makes finding a unified solution difficult. Since the firm is losing many clients due to wrong or slow reports , the solution must be as cheap as possible.

## Solution 2:
Design and implement a realtime streaming pipeline that dynamically scales to meet the realtime data's demand, its source system agnostic such that new source systems can be easily onboarded and used. The data is organized in a constistent way and serves to all teams via one OLAP database which should cover most analytical data queries. If a team does not want to use an OLAP database then we stream the data to a different destination. Finally the reatime data should land in the datawarehosue as historical data, reducing the demand on the OLAP database that only holds realtime data of a few days.

Metrics of success:  
- The pipeline responds to increased data throughput in a dynamic and automated way
- The pipeline should be fault tolerant and store the realtime data in a persistent way as soon as possible.
- The pipelne should work for any data source and any consumer group and should scale with sources or consumer groups.
- Monetary cost should be minimal
- Clear and automated data and dev Ops
- Clear and sensisble Data governance
- Tick data should be stored in Data warehouse as historical data

---

Read "architecture.md" to find out how this can be achieved


