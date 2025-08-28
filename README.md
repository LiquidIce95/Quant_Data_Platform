
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

- [The Data Set](#the-data-set)
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


# The Data Set
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
