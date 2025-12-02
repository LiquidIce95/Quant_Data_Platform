<img width="1352" height="741" alt="image" src="https://github.com/user-attachments/assets/2e27421a-3f85-4ba1-b9f7-48767731eb4d" />

I am going to refer to this diagram throughout this file. If you prefer to laod it into draw.io, you can do so by using architecture.drawio.xml

# Conceptual Architecture

In this section we define the conceptual architecture which is completely technology agnostic and focuses on the key relationships that we need to build a good platform for this use case. 

We do this by defining `components` which can be viewed as entities that perform work. We do not care about what exactly these entities are, we only care about their properties they need to exhibit in order for the platform to achieve its goal. 

Then tech selection boils down to selecting the tools that satisfy the definitions of our components, because then they satisfy the inferred realionships which compose the platform. The data platform is thus an instantiation of the conceptual architecture via the instances of the components that are given by concrete technologies.

# Major Design decision
## Lambda Architecture

As you know from the Business_use_case.md and the user stories, we have the following requirements and constraints to satisfy.

1. The platform has to be cheap (200 dollars per month at most)
2. We need a centralized place for the data to manage access and Users
3. That place should allow api access
4. We should have the capacity to add or remove sources and destinations
5. The Platform should be responsive or at the very least not lagging noticably
6. We need processes to ensure quality and governance practices
7. My life as a maintainer should be as easy as possible
8. The processing of the data cannot take too long otherwise people start using the source systems again
9. The platform needs to be stable and reliable otherwise people start using the source systems again
10. Other people need to know that the system is healthy and working (SRE, security ,users, ect)


The overall architecture resembles the *Lambda architecture*. Data can be categorized into streaming / micro batching or simply batch processing. So we split the Platform into Stream and batch processing. So any data source can be processed by at least one pipeline type (streaming or batch).

Hence each part of the system accomodates the correct user group (large volums of historical data or low volumes of recent data) which allows us to better serve them. 

Another benefit is that both pipelines are decoupled and allow the use of specific tools that are best suited for each pipeline. By using two seperate databases, one for historical data up to a few dayes delayed and one for all realtime data of the last few days, we force this onto our users so they cannot misuse the databases. In the database of the streaminglane we hold data form the last x days, whereas in the Warehouse all the data until the last x-th day is stored. As a result, most queries on the smaller database should run faster which is critical for financial realtime data.

Then we simply treat our streaming database as another batch processing data source.


# Streaming lane
## Major Components

### Category Schema
In the domain of finance, we have two categories of external data the company is consuming and might want to consume. The first one is market data the second is economic event data. For market data, anything that is being traded on an exchange, has a unique code. For the apple stock is AAPL for the Crude oil future that expires in december 2026 its CLZ26. Also if we stream tick data, we know that that code will also have a price, timestamp, and trading size of the tick. So our first fundamental category of data is (source_system:String,symbol:String, timestamp:Time, price:Double, size:Double), anything that is being traded on a public exchange will satisfy this schema. The second type of market data is L2 or order book data which for assets or derivatives being traded on public exchanges satisfies (source_system:String,symbol:String, side:String, level:Int, price:Double, size:Double), where side can be 'bid' or 'ask' and level is some number up to the maximum level that is being tracked which depends on the exchange.

For numeric economic event data it will always satisfy (source_system,event_publisher,value,unit,description) for non numeric economic event data the same but the value is not numeric but a string instead.

Important is that we do allow more fields to be present but these fields have to be present.

invariants
- for every data there is at least one category schema which it can satisfy


### Connectors

Definition:
A piece of software that simply extracts realtime data from a data source and sends the data to the Buffer.

invariants
- selects all fields and renames them to the Category schema if they are present
- sends these fields to the topic

### Buffer

Definition:
Persistent Storage that simply holds the raw data labeled with the category. 

invariants
- The data is raw and labeled with a correct category
- All data have at least the fields of the category schema
- data is stored persistent for at least the runtime of the streaming pipeline

### Processor 

Definition:
Gets the data from the buffer and prepares it for every destination. Sends data to each destination.


invariants
- all data from a category is processed
- all destinations receive data from all topics they want

### database
The streaming database is one of the possible destinations of the data but the most frequently used. We store data from all topics in a dedicated ingestion table and then build a suitable data model from these ingestion tables.

invariants
- processed data from every topic is stored 
- it is the only component that is in contact with users


## Discussion of streaming pipeline
### Strengths
The pipeline is strongly decoupled, Every component depends on exactly its predecessor and every component has garantees from its predecessor. This allows us to scale each component individually and select technologies that are suited for exactly that component (since the compoenents are so clearly defined, we can choose technologies that have a more precise use case instead of selecting general purpose solutions). The pipeline also allows to deal with any source or destination as independant as possible. The pipeline is also simple, which means that its faster to implement, easier to test, easier to maintain, easier to monitor, easier to debug and easier to find maintainers.

### Weaknesses
primarily latency and storage cost and the number of topics. In fact keeping the topic numbers as low as possible is what makes this pipeline either great or mediocre.


# Batch Processing


## Major components

### Connectors
The same reasoning applies here since our reasoning depended on source not data frequency.

### Data Lake
The data lake is the place where we store our raw data. 

Advantages:
We have access to the raw data as a baseline for DataOps. It also decouples the source system from the pipeline, (if the source system is down we still have access to the last version of the raw data) which also allows us to conduct tests without needing the source systems.

Disadvantages:
storage cost and latency



Advantage:
This achieves the goal of landing the data in persistent storage as fast as possible, since kafka stores the data as logs in the filesystem and the data can be consumed multiple times by consumers if they want to. Another advantage is that the computations in the connector are very light thus the risk of backpressure is minimal. Finally onboarding a new data source is also minimal and as light as possible.

Disadvantage:
Every Source needs a unique connector


Advantages:
We have three key advantages, first it is the first point in time where data lands on persistent storage and is thus 'safe'. The other purpose is to act as buffer between the connectors that send data to the remaining part of the pipeline. Each connector sends that data at different rates which can cause downstream components to be overwhelmed or underwhelmed if not decoupled like this. It also allows the control panel to 'react' since logs and metric from the connectors indicate future load before they actually happen. The third use case is to categorize the data according to the transformations it needs, a topic defines a topic mapping, which defines how the datais transformed later. The topic schema is there to enforce rules about what fields the topic data must have. Topic mapping defines what happens to these fields.

Disadvantages:
Introduces latency and overhead of managing these topics, topic schemas and topic mappings. Also the builder of the connector needs to choose the correct topic and we cannot enforce this behaviour from code alone. Finally, we could end up with lots of topics which is undesirable for a simple architecture.


Advantages:
The advantage here is that we decouple source from destination since Transformations depend on the topic, not the source or the destination. Also we keep the pipeline dry since multiple streams that requrie the same transformation dont need to be implemented multple times.

Disadvantages:
The processor also needs to know what the concrete mappings are for the topics. Latency is also greater compared to direct ingestion.

Advantages:
One central place for all steraming data allows to enforce policies and quality and enables each team to access data from every source. By restricting users access to only this database, we direct user traffic to one place which makes loadbalancing of the remaining parts of the pipeline easier.

Disadvantages:
Unnecessary storage costs if same data lands in different destination.

### Data Warehouse ingestion tables
They store the raw data from the lake, as a string table where no transformations are applied.

Advantages:
We have raw data as tables which is more pleasant to work with. Also we can add these tables to the Data Catalogue and lineage to get a complete picture of the transformations applied to the data.

Disadvantages:
storage cost and latency

