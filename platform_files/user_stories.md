# Analysts

As an analyst I want to have realtime dashboads, where I can observe the impact of the release of macro economic data to gain insights and ideas for models or algorithms. These dashbords should support candle stick charts (1 min, 5 min, 15 min, 60 min, 1 day, 1 week), selection of single futures contracts (crude oil and natural gas current 8 contract months, historical data, forward curves in real time and the  macro economic data as well as L2 (full order book) data. Dashboard should feel responsive and (less than 500ms lags or sampling).

As an analyst I want to have a data catalogue for our data warehouse to quickly discover and understand datasets we currently have in a centralized place.

As an analyst I want to load (historical) data into my jupiter notebooks to conduct experiments.

As an analyst I want to query Data in the datawaerehouse by Timestamp ranges and symbol (crude oil, natural gas, S&P E-mini) to quickly select the data I am interested in investigating. In particular I want to quickly be able to get all the information we have in the DWH about a certain symbol and on a certain time range.

As an analyst I want to wait at most one hour until the freshest data is available to me from the DWH to not delay my work.

As an analyst I want the data to be cleaned and well documented such that I can focus on analytical work.

# Quant / ML engineers

As an engineer I want to access several streaming sources (right now just IB) and data releases from a unified, well documented and simple to use API to feed our models with low latency streaming data (below 500ms ingestion to api response in switzerland) during US cash market open.

As an egnineer I want to access historical data from our DWH via an api to build ML and model pipelines for training

# Business Owners

As a business owner I want our data to be well protected from unauthorized access and that User rights are managed properly to prevent loss or corruption of data.

As a business owner I want the warehosue to be long-term solution to our data needs to be able to add data from other sources of the company (supplier data, client data, employee data ect.)

As a business owner I want the entire platform / system to be scalable in terms of compute and storage to quickly adapt to growing data needs of the company.

As a business owner I want the operational costs of the platform to be as low as possible since they directly impact the profitability of models or algorithms.

# Data engineers

As a data engineer I want the platform to be easy to maintain such that I can quickly sovle problems when they arise.

As a data engineer I want the platform to be as automated, reliable and fault tolerant as possible to avoid catastrophic business consequences.

# Data provider

As a data provider I want that all users respect our throtling and rate limits to ensure fair and consistent availability of the data.


