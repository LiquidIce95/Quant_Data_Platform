<img width="1352" height="741" alt="image" src="https://github.com/user-attachments/assets/2e27421a-3f85-4ba1-b9f7-48767731eb4d" />

I am going to refer to this diagram throughout this file. If you prefer to laod it into draw.io, you can do so by using architecture.drawio.xml

# Major Design decisions

As you know from the Business_use_case.md and the user stories, we have the following requirements and constraints to satisfy.

1. The platform has to be cheap (200 dollars per month at most)
2. We need a centralized place to manage access and Users
3. That place should allow api access
4. We should have the capacity to add or remove sources and destinations
5. The Platform should be responsive or at the very least not lagging noticably
6. We need processes to ensure quality and governance practices
7. My life as a maintainer should be as easy as possible
8. The processing of the data cannot take too long otherwise people start using the source systems again
9. The platform needs to be stable and reliable otherwise people start using the source systems again
10. Other people need to know that the system is healthy and working (SRE, security ,users, ect)

After talking to the teams they mostly agree that interactive brokers streaming data should be the first source to be onboarded onto the new system, since they rely heavily on it. They also find the notion of a OLAP store nice, as long as they dont have to write complicated SQL and not work with many different tools they are fine with it.

The overall architecture resembles the Lambda architecture. Data can be categorized into streaming / micro batching or simply batch processing. So any data source can be processed by at least one pipeline type (streaming or batch). One big Advantage is that by spliting the platform into streaming and batch, we can distribute the demands accordingly. Anyone who is interested in analyzing large amounts of data, should in general, not care if the data goes until yesterday or today since they are mostly interested in the volume not the recency of the data. And if we take into consideration that data should be split into training and validation data anyways its very unlikely that someone like this needs the data to go to the very recent data points. So these Use cases are dealt with by the Warehouse. 

On the other hand, if someone or something is interested in realtime data, then in general, to update some state about the world and use it as an input to do something, however most of its logic on how to do that 'something' is encoded via the analysis of historical data, so volume or range is not the primary concern. 

Hence each part of the system accomodates the correct user group and which allows us to better serve them. Another benefit is that both pipelines are decoupled and allow the use of specific tools that are best suited for each pipeline. By using two seperate databases, one for historical data up to a few dayes delayed and one for all realtime data of the last few days, we force this onto our users so they cannot misuse the databases. In the database of the streaminglane we hold data form the last x days, whereas in the Warehouse all the data until the last x-th day is stored. We simply treat our streaming database as another batch processing data source.

Finally the entire system is managed by a control panel, the control panel schedules and monitors all platform jobs to setup or teardown specific parts,creates necessary configuration files and conducts testing on the platform level. Each part of the platform sends logs and metrics to the panel, such that the panel has access to its current state and all important events.

# Streaming lane
## Major Components

let realtime_period be the number of days that the data is stored in the streaming database.

### Connectors
In this architecture, a `compoent` is a piece of software that simply extracts realtime data from a data source. Each data source has exactly one component. This forcees a clear separation of concerns since every data source is quite unique in the following sense:
- different schemas
- different throttling limits 
- different formats
- different ways to authenticate
- different throughputs
- different garantees
- different robustness

So treating each data source with its own component completely decouples the source from the system, which is what we want. Every component extracts the data and does minimal preparation to store it in one of the kafka topics. Concretely each connector extracts the fields and renames them to fit to the schema of the topic. A connector does *not* clean or formats any data. 

Advantage:
This achieves the goal of landing the data in persistent storage as fast as possible, since kafka stores the data as logs in the filesystem and the data can be consumed multiple times by consumers if they want to. Another advantage is that the computations in the connector are very light thus the risk of backpressure is minimal. Finally onboarding a new data source is also minimal and as light as possible.

Disadvantage:
Every Source needs a unique connector

invariants
- selects all fields and renames them to the topic schema
- if fields required by topic schema are missing, fill in "not present in source system" values
- sends these fields to the topic

### Message Broker
The streaming data from any connector lands then in our message broker in a topic. 

Advantages:
We have three key advantages, first it is the first point in time where data lands on persistent storage and is thus 'safe'. The other purpose is to act as buffer between the connectors that send data to the remaining part of the pipeline. Each connector sends that data at different rates which can cause downstream components to be overwhelmed or underwhelmed if not decoupled like this. It also allows the control panel to 'react' since logs and metric from the connectors indicate future load before they actually happen. The third use case is to categorize the data according to the transformations it needs, a topic defines a topic mapping, which defines how the datais transformed later. The topic schema is there to enforce rules about what fields the topic data must have. Topic mapping defines what happens to these fields.

Disadvantages:
Introduces latency and overhead of managing these topics, topic schemas and topic mappings. Also the builder of the connector needs to choose the correct topic and we cannot enforce this behaviour from code alone. Finally, we could end up with lots of topics which is undesirable for a simple architecture.

invariants
- all data stored on a topic, satisfies that topic schema by field name (not dtype or values)
- data is stored persistent for at least the runtime of the streaming pipeline
- for every streaming data and destination pair there is at least one fitting topic

### Processor 
All data in each topic is then consumed by a `processor`. A processor prepares the data for ingestion into the streaming database, (and any other destination) by making sure the data fits the schema of the destination. Since each connector renamed the fields to correspond to the correct schema fields, all the processor has to do is casting the data to comply with the fields dtype and other constraints. From the perspective of the processor, the data source does not matter (its just a field) it only needs to know what is in the field and what should be according to the topic  schema and mapping which are defined on a per topic basis (each topic defines the transform so to speak). After transforming / processing the topic data, the processor ingests that data into all destinations that receive from that topic. 

Advantages:
The advantage here is that we decouple source from destination since Transformations depend on the topic, not the source or the destination. Also we keep the pipeline dry since multiple streams that requrie the same transformation dont need to be implemented multple times.

Disadvantages:
The processor also needs to know what the concrete mappings are for the topics. Latency is also greater compared to direct ingestion.

invariants
- all data from a particular topic is processed the same way
- all destinations receive data from all topics they want

### streaming database
The streaming database is one of the possible destinations of the data but the most frequently used. We store data from all topics in a dedicated ingestion table and then build a suitable data model from these ingestion tables.

Advantages:
One central place for all steraming data allows to enforce policies and quality and enables each team to access data from every source. By restricting users access to only this database, we direct user traffic to one place which makes loadbalancing of the remaining parts of the pipeline easier.

Disadvantages:
Unnecessary storage costs if same data lands in different destination.

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
The data lake is the place where we store our raw data

