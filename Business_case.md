
# Quant Data Platform for Energy Futures markets

This is a portfolio project to showcase understanding, skills and expertise in the field of Data Engineering. This project has been done in the context of the business case below.

# Introduction & Goals
Business Case:  
You got hired as a Data Engineer at a commodity trading firm in Zug focusing on energy markets and quantitative approaches.  

The firm wants to reduce operational risk and latency in its macro data handling to support analysts and potentially open new short-term trading strategies.  

---

Objective 1:  
Right now the analysts manually download CSV files containing macro data for US Oil (futures) and store them on SharePoint. Until now it wasn’t a problem because most of these reports are published weekly or monthly. However, sometimes analysts forget to download these reports and the organization of these files varies widely amongst analysis teams, leading to duplicate and inconsistent work.  
Your first objective is to provide a unified, clean and simple-to-use data platform for the various analysis teams and their analysts. You may assume that the analysts are comfortable in basic SQL and can self-service dashboards and analytics. The Platform needs to be scalable and open for integration of other business data from other sources and Types (product and service data, suppliers, clients, ect)

Metrics of success:  
- Pipeline reliability: 99% (out of 100 proper releases, 99 work flawlessly).
- Pipeline timeliness: the released reports should be in the data warehouse within 1 hours of release.
- Easy-to-understand and explorable as well as scalable data warehouse (clean data catalogue and data model).
- Clear and automated data and dev Ops
- Clear and sensisble Data governance

---

Objective 2:  
The firm trades on larger time frames with trades often running from days to months. However, some analysts are speculating that using the macroeconomic data might present short-term opportunities if the data is read and processed with minimal time delay. They believe that they can design algorithms which can predict price action of the next 2 to 4 minutes starting from the point in time where the macro data is made public.  

Thus your second objective is to engineer a pipeline from live market data of Interactive Brokers to a real-time chart where it is indicated when the macro data is released and then updated in the chart with minimal delay such that analysts can observe the impact of the release and results of these reports in real-time on futures markets. This chart should support basic chart functionality and loading of historical data (historical charts).  
Metrics of success:  
- Dashboard must be responsive and fluid ≤500 milliseconds lag or sampling.
- Use of true event tick data (no sampling)
- Clear and automated data and dev Ops
- Clear and sensisble Data governance
- Tick data should be stored in Data warehouse as historical data

---

Objective 3:  
The firm uses various statistical and machine learning models, however the process of training and feeding these models with new data involves a lot of manual labor from the analysts part. Furthermore it is currently not possible to feed live data into the models. Your third objective is to implement an API that provides real time market data from IB and historical data from the warehouse.


Metrics of success:  
- The api should be in accordance to best practices of REST
- End-to-end latency ≤500 milliseconds from Interactive Brokers API users in Switzerland.
- Use of true event tick data (no sampling)
- Clear and automated data and dev Ops

For more details on the objectives see the user_stories.md
---

Constraints
- The entire project must be completed within 15 weeks with at most 10 hours per week
- The entire project cannot cost more than 150 USD per month to keep running
- To keep costs low we only run the streaming pipeline during US cash open (15:30 - 18:30 CET)

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

# Financial jargon and context
If the reader is not somewhat familiar with financial jargon (futures, futures contracts, derivatives, tick data, limit orders, volume, open interest ) then this section is dedicated to explaining the terms used here.

**Market** a place where buyers and sellers agree on a price on which an exchange of goods or services happens

**Trade** - successfull exchange between a buyer and seller on a financial market is called a trade, trades are usually done with short term intentions otherwise its usually called an investment. In the context of futures contracts duties are traded.

**Futures / futures contract** — A standardized contract to buy or sell a commodity at a future date for a price that will be agreed on now (fix price now, buy or sell commodity later), this allow producers to hedge themselves against falling prices because they can sell their commodity in the future but for the current price. Likewise this allows buyers of commodities to hedge themselves against rising prices since they can secure a price now and buy the commodity later. The buyer of the contract has the duty to buy the commodity for the agreed upon price (which is the price the future contract is currently listed) for the delivery time defined by the  contract (which is in the future, hence futures). The seller of the contract has the duty to sell the commodity at the agreed price also at the defined delivery date, when the date arrives and the commodity is exchanged then the contract is considered (physically) settled. A party (buyer or seller) can only opt out of the contract, by reverting the trade and thus closing the position, concretely if a party is the buyer, then it sells the contract to another buyer, to replace the buying party of the contract and thus opting out of the contract. Likewise a seller needs to buy "back" the contract by finding a new seller to opt out of the contract. The more people participate in a financial market the easier it is to open and close these contracts and the less influence a single party has over the entire market, such a market is said to be liquid.

In the below graphic imagine the price curve to be of a commodity, depending on at which piont in the future the contract is settled the buyer or the seller profits.

<img width="467" height="251" alt="image" src="https://github.com/user-attachments/assets/fdda3afa-6855-4e56-a07d-f47b479c7f49" />


**Derivatives** — Financial contracts whose value comes from something else (an asset), hence futures are derivatives because the value of the contract is "derived" from the value of the commodity.  

**How the auction works (what actually happens when you trade)**

An Exchange runs an auction process where participants either place market or limit orders. If someone submits a limit order for price x , then the exchange garantees them that they will get paired with a counterparty for x or better. If someone submits a market order then the exchange promises them to fullfill that order as fast as possible without a garantee about the price they get, the algirthm will simply move up or down the price "ladder" until a counterparty is found (limit orders). For instance if someone submits a sell market order and the next limit order is at 100 then the market order will be filled at 100 even if the last traded price was 120. Through the interaction of these order types the price "moves" up or down. If there are more buy market order than sell limit orders at a given price level then the limit orders will be completely consumed and the remaining market orders are "filled" at the next higher "tick" (see below). Beware that this description is an extreme simplification of the actual auction process (for instance can market order also fill each other, buy and sell market orders) but it captures the core idea. Or look at the image and you will immediately understand

<img width="482" height="758" alt="image" src="https://github.com/user-attachments/assets/4d4b0d82-c145-44d2-bb1b-facad4dd0c3b" />

On the left column you have the total quantity of submitted buy limit orders right now (just snapshot in time), on the right you have total quantity of sell limit orders right now. suppose for the next point in time that only a total of 7 sell market orders arrive at the exchange and nothing else happens (everything else remains the same) them that 3 buy limit orders at 5947,75 are consumed and the algorithm matches the remaining 4 sell market order with the buy limit orders at 5947,50. In this example the tick size is 0,25 because the price can only move in increments or decrements of 0,25


**Tick** - smallest unit a price can move in the auction process

**Tick data** — The smallest real-time updates from the market (every trade and order-book change).  
**Level 1 (L1) vs Level 2 (L2)** — L1 shows the best available buy/sell price (closest limit orders to last traded price); L2 shows multiple price levels (“depth”) on both sides (limit orders further away from current price).  
**Depth of Market (DOM)** — A ladder-style view of that L2 depth, shows you 'all' the limit orders at varous price levels.

**Volume** — How much traded over a period.  
**Open interest (OI)** — How many futures contracts are still open (positions that haven’t been closed).  

---

# The Data Sources
We use three main data sources: Interactive Brokers API, www.cftc.gov, www.eia.gov

Interactive Brokers API is an API provided by interactive brokers a global broker, brokers enable institutions and individuals alike to place orders at various exchanges around the globe, such as NYMEX (New York Mercantile Exchange) where energy futures are traded. Interactive Brokers also provides very cheap real-time streaming data for NYMEX and level 2 market depth. The Author of this project is well aware that for a real business case, the institution should consider switching to a more expensive but faster alternative.

The CFTC (commodity futures trading commission) is the official U.S. regulatory body for futures market and its goal is to promote the integrity, resilience, and vibrancy of the U.S. derivatives markets through sound regulation.

The U.S. Energy Information Administration (EIA) is the Department of Energy’s independent statistical agency that collects, analyzes, and disseminates impartial energy data and forecasts to inform policy, ensure efficient markets, and enhance public understanding of energy’s economic and environmental impacts.

These sources were chosen not only because they provide relevant data and are credible but also because they are the first publishers of that data (except from Interactive Brokeres) and thus its performance critical to use the actual source to reduce latency (again, conscious choice about interactive brokers due to budget limitations of the author).

It also represents a more realistic scenario when gathering data from public sources, organizations like these are the most likelily to publish good public data (non-profit or government).

## Data from the sources

### Interactive Brokers API

**What data are we interested in ?**

From the IB API we request real-time live tick-by-tick streaming data (tick is the smallest unit the price of an asset or a derivative can move) as well as full market depth level 2 data (buy and sell limit orders at varous levels / prices) for all futures contracts of crude oil WTI and natural gas. The API is authenticated with a demo account to prevent financial ruin of the author.

**How often is this data updated and how large is the data ?**

its quite a lot of data and could generate up to 1 TB per month.  The events from the stream could be generated at the micro second level thus its common to set a fixed sampling intervall of maybe 5 to 20 milliseconds. The stream is only active during trading hours.

**How is the data persisted ?**

The idea is to compress the data on 1 minute time intervalls (open, high, low, close, volume, open interest) to avoid excessive storage costs and to provide historical data. 
The author is well aware that in a practical setting one would actually want to store historical tick data since its very expensive to buy but because we are getting the real-time tick data from IB we are not allowed to sell it thus there is no value in storing it.


**What are potential problems of this data ?**

Managing the volume, concurrency and processing it in a timely manner and cost efficient.


**Why are we picking this data ?**

These tick event data streams are selected because they are data that is crucial for real-world low latecny trading operations and its always fascinating to observe markets with the DOM and real time charts. Furthermore IB is possibly the cheapest vendor for that data and a credible and reliable source of it and their api seems to be well maintained and documented.

### CFTC

**What data are we interested in ?**

From the CFTC we are interested in the committment of traders report (COT) that is published every week outside trading hours, so its impact can only be observed in the next monday trading session. The COT reports are based on position data supplied by reporting firms (FCMs, clearing members, foreign brokers and exchanges). While the position data is supplied by reporting firms, the actual trader category or classification is based on the predominant business purpose self-reported by traders on the CFTC Form 40 and is subject to review by CFTC staff for reasonableness. CFTC staff does not know specific reasons for traders’ positions and hence this information does not factor in determining trader classifications. In practice this means, for example, that the position data for a trader classified in the “producer/merchant/processor/user” category for a particular commodity will include all of its positions in that commodity, regardless of whether the position is for hedging or speculation. Note that traders are able to report business purpose by commodity and, therefore, can have different classifications in the COT reports for different commodities.

This report allows to categorize the current open Interest of a contract by Commercial, Large speculative and small speculative traders. The commercial traders (producers or buyers of the physical commodity) are reported explicit, the Large speculators or hedgers are interpreted to be the NonCommercial traders traders buying or selling these contracts not for buying or selling the commodity but presuably for speculation. Subtracting these two categories from the total open interest of a contract gives a remaining category often interpreted as "small traders" or "small speculators". This way traders may use the report to "see" how other traders are positioned in the market. 

**How often is this data updated and how large is the data ?**

The report is published weekly and is at most 10 GB

**How is the data persisted ?**

It will be completely persisted in the data warehouse.

**What are potential problems of this data ?**

The CFTC's API seems a bit complicated to use. Also getting the right timing to fetch the report will pose a problem even with a completely deterministic schedule adding to this comes the fact that the specific release times may vary and we need to consider holidays.

**Why are we picking this data ?**

Being able to see an estimate of how major market participants are positioned in the markets can serve as feedback for models trying to predict that positioning. Also if extreme positions are reached its indicative of high volatility. Lastly its something commonly used in retail trading circles.


### EIA

**What data are we interested in ?**

The U.S. Energy Information Administration (EIA) is the Department of Energy’s independent statistical agency. It collects, analyzes, and publishes impartial energy data and forecasts to inform policy, markets, and the public.

Here we are interested in the following:

Weekly crude oil stock/storage data : https://www.eia.gov/opendata/browser/petroleum/stoc/wstk

Monthly crude oil production data : https://www.eia.gov/opendata/browser/petroleum/crd/crpdn

Monthly Natural Gas withdrawal and production data : https://www.eia.gov/opendata/browser/natural-gas/prod/sum

Weekly Natural Gas storage data : https://www.eia.gov/opendata/browser/natural-gas/stor/wkly

**How often is this data updated and how large is the data ?**

Weekly or monthly as described above and these reports are at most 10 GB large

**What are potential problems of this data ?**

Getting the right timing to fetch the report will be challenging even though there is an email alert, its not clear of how fast these alerts are. Even with a completely deterministic schedule adding to this comes the fact that the specific release times may vary and we need to consider holidays.

**Why are we picking this data ?**

This source seems to have an easy to use and well documented API. Also the source has more interesting data that can also be fetched with the API and the data sets contain data that directly relates to price action (supply data)




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
