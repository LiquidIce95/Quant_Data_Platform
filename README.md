
# Quant Data Platform for Energy Futures markets

This is a portfolio project to showcase understanding, skills and expertise in the field of Data Engineering. This project has been done in the context of the business case below.

# Introduction & Goals
Business Case:  
You got hired as a Data Engineer at a trading firm in Zug focusing on energy markets and quantitative approaches.  

The firm wants to reduce operational risk and latency in its macro data handling to support analysts and potentially open new short-term trading strategies.  

---

Objective 1:  
Right now the analysts manually download CSV files containing macro data for US Oil (futures) and store them on SharePoint. Until now it wasn’t a problem because most of these reports are published weekly or monthly. However, sometimes analysts forget to download these reports and the organization of these files varies widely amongst analysis teams, leading to duplicate and inconsistent work.  
Your first objective is to provide a unified, clean and simple-to-use data platform for the various analysis teams and their analysts. You may assume that the analysts are comfortable in basic SQL and can self-service dashboards and analytics.  

---

Objective 2:  
The firm trades on larger time frames with trades often running from days to months. However, some analysts are speculating that using the macroeconomic data might present short-term opportunities if the data is read and processed with minimal time delay. They believe that they can design algorithms which can predict price action of the next 2 to 4 minutes starting from the point in time where the macro data is made public.  

Thus your second objective is to engineer a pipeline from live market data of Interactive Brokers to a real-time chart where it is indicated when the macro data is released and then updated in the chart with minimal delay such that analysts can observe the impact of the release and results of these reports in real-time on futures markets. This chart should support basic chart functionality and loading of historical data (historical charts).  

---

Objective 3 (optional):  
If the analysts are confident in their speculation, your third objective is to implement an API for the quant engineers to use the low-latency data (provided to the real-time dashboard) for implementing these trading algorithms.  

---

Metrics of success:  
- Report timeliness and availability 99% (out of 100 proper releases, 99 work flawlessly).  
- Easy-to-understand and explorable as well as scalable data warehouse.  
- End-to-end latency ≤500 milliseconds from Interactive Brokers to chart / dashboards / API users in Switzerland.


# Contents

- [The Data Set](#the-data-sources)
- [Financial jargon](#financial-jargon)
- [Used Tools](#used-tools)
  - [Connect](#connect)
  - [Buffer](#buffer)
  - [Processing](#processing)
  - [Storage](#storage)
  - [Visualization](#visualization)
- [Pipelines](#pipelines)
  - [Stream Processing](#stream-processing)
    - [Storing Data Stream](#storing-data-stream)
    - [Processing Data Stream](#processing-data-stream)
  - [Batch Processing](#batch-processing)
  - [Visualizations](#visualizations)
- [Demo](#demo)
- [Conclusion](#conclusion)
- [Follow Me On](#follow-me-on)
- [Appendix](#appendix)

# Financial jargon
If the reader is not somewhat familiar with financial jargon (futures, futures contracts, derivatives, tick data, limit orders, volume, open interest ) then this section is dedicated to explaining the terms used here.

TODO

# The Data Sources
We use three main data sources: Interactive Brokers API, www.cftc.gov, www.eia.gov

Interactive Brokers API is an API provided by interactive brokers a global broker, brokers enable institutions and individuals alike to place orders at various exchanges around the globe, such as NYMEX (New York Mercantile Exchange) where energy futures are traded. Interactive Brokers also provides very cheap real-time streaming data for NYMEX and level 2 market depth. The Author of this project is well aware that for a real business case, the institution should consider switching to a more expensive but faster alternative.

The CFTC (commodity futures trading commission) is the official U.S. regulatory body for futures market and its goal is to promote the integrity, resilience, and vibrancy of the U.S. derivatives markets through sound regulation.

The U.S. Energy Information Administration (EIA) is the Department of Energy’s independent statistical agency that collects, analyzes, and disseminates impartial energy data and forecasts to inform policy, ensure efficient markets, and enhance public understanding of energy’s economic and environmental impacts.

These sources were chosen not only because they provide relevant data and are credible but also because they are the first publishers of that data (except from Interactive Brokeres) and thus its performance critical to use the actual source to reduce latency (again, conscious choice about interactive brokers due to budget limitations of the author).

It also represents a more realistic scenario when gathering data from public sources, organizations like these are the most likelily to publish good public data (non-profit or government).

## Data from the sources

### Interactive Brokers API

From the IB API we request real-time live tick-by-tick streaming data (tick is the smallest unit the price of an asset or a derivative can move) as well as full market depth level 2 data (buy and sell limit orders at varous levels / prices). The API is authenticated with a demo account to prevent financial ruin of the author.

### CFTC

From the CFTC we are interested in the committment of traders report (COT) that is published every week outside trading hours, so its impact can only be observed in the next monday trading session. The COT reports are based on position data supplied by reporting firms (FCMs, clearing members, foreign brokers and exchanges). While the position data is supplied by reporting firms, the actual trader category or classification is based on the predominant business purpose self-reported by traders on the CFTC Form 40 and is subject to review by CFTC staff for reasonableness. CFTC staff does not know specific reasons for traders’ positions and hence this information does not factor in determining trader classifications. In practice this means, for example, that the position data for a trader classified in the “producer/merchant/processor/user” category for a particular commodity will include all of its positions in that commodity, regardless of whether the position is for hedging or speculation. Note that traders are able to report business purpose by commodity and, therefore, can have different classifications in the COT reports for different commodities.

This report allows to categorize the current open Interest of a contract by Commercial, Large speculative and small speculative traders. The commercial traders (producers or buyers of the physical commodity) are reported explicit, the Large speculators or hedgers are interpreted to be the NonCommercial traders traders buying or selling these contracts not for buying or selling the commodity but presuably for speculation. Subtracting these two categories from the total open interest of a contract gives a remaining category often interpreted as "small traders" or "small speculators". This way traders may use the report to "see" how other traders are positioned in the market. 

The report is published weekly

The CFTC mentiones an API for retrieving the report but its provided by what seems to be a non government organization so using it somewhat defeats the whole purpose of getting it from the source directly. Or one can try to use the endpoints from the CFTC directly.

The report is at most 10 GB

### EIA

The U.S. Energy Information Administration (EIA) is the Department of Energy’s independent statistical agency. It collects, analyzes, and publishes impartial energy data and forecasts to inform policy, markets, and the public.

Here we are interested in the following:

Weekly patroleum stock/storage data : https://www.eia.gov/opendata/browser/petroleum/stoc/wstk

Monthly crude oil production data : https://www.eia.gov/opendata/browser/petroleum/crd/crpdn

Monthly Natural Gas withdrawal and production data : https://www.eia.gov/opendata/browser/natural-gas/prod/sum

Weekly Natural Gas storage data : https://www.eia.gov/opendata/browser/natural-gas/stor/wkly

Because these are expected to move the cude oil and natural gas market.

The data can be accessed via API and each of these reports are at most 10 GB large

- Explain the data set
- Why did you choose it?
- What do you like about it?
- What is problematic?
- What do you want to do with it?

# Used Tools
- Explain which tools do you use and why
- How do they work (don't go too deep into details, but add links)
- Why did you choose them
- How did you set them up

## Connect
## Buffer
## Processing
## Storage
## Visualization

# Pipelines
- Explain the pipelines for processing that you are building
- Go through your development and add your source code

## Stream Processing
### Storing Data Stream
### Processing Data Stream
## Batch Processing
## Visualizations

# Demo
- You could add a demo video here
- Or link to your presentation video of the project

# Conclusion
Write a comprehensive conclusion.
- How did this project turn out
- What major things have you learned
- What were the biggest challenges

# Follow Me On
Add the link to your LinkedIn Profile

# Appendix

[Markdown Cheat Sheet](https://github.com/adam-p/markdown-here/wiki/Markdown-Cheatsheet)
