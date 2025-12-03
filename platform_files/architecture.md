<img width="1352" height="741" alt="image" src="https://github.com/user-attachments/assets/2e27421a-3f85-4ba1-b9f7-48767731eb4d" />

I am going to refer to this diagram throughout this file. If you prefer to laod it into draw.io, you can do so by using architecture.drawio.xml

I also assume that you know what a data model is.

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


The overall architecture resembles the *Lambda architecture*. The reasons why I chose such architecture are as follows.

- Real-time data users care about recency not volume, hence we can use a specialized database for that
- Historical data user care about volume not recency, hence we can use a specialized database for that
- The split minimizes cost and maximizes performance for batch and realtime data (replaying batch data as realtime introduces unnecessary cost and performance penalty on the system).
- Collected realtime data can be treated as a batch job to insert into the historical database.
- Realtime data sources usually have much slower schema evolution.


# Streaming lane
## Major Components

### Category Schema
A *Category Schema* is the maximal set of canonical field names for a type of external data. Any Category schema has meta fields for every field name it contains. These meta fields depend on "field_name" and are : "field_name"_dtype, "field_name"_unit, "field_name"_state.

We say 'category row' for a row / tuple in the category schema.

Examples of external data types and respective field names:

- **Market Tick Data** → (source_system, symbol, timestamp, price, size, Open Interest,...)  
- **Order Book Data** → (source_system, symbol, side, level, price, size,...)  
- **Economic Event Data (numeric)** → (source_system, event_publisher, value, unit, description,...)


**Invariants**
- Every data stream schema needs to be a subset of least one category schema.

We are addressing the elephant in the room later: How can we design a *good* category schema?


### Connectors

Definition:
A piece of software that simply extracts realtime data from a data source, maps the source fields to the category fields and fills out the meta category fields.

**invariants**
- selects at least one category
- Maps the source field names to category field names.
- fills out the meta category fields.
- Transforms the values to fit the category dtypes and units.
- sends the data as a category row to the buffer.


### Buffer

Definition:
Persistent Storage that simply holds the category data / rows belonging to a category. 

invariants
- The category data satisfies the category schema
- data is stored persistent for at least the entire runtime of the streaming pipeline
- provides at-least-once-devlivery

### Processor 

Definition:
Gets the category data from the buffer and prepares it for a destination. Sends data to that destination.


invariants
- For every destination there is at least one processor
- loads any category data the destination is interested in
- prepares the data for the destination
- sends data to destination
- reprocessing the same data again is idempotent

### database
The streaming database is one of the possible destinations of the data but the most frequently used. We store data from all topics in a dedicated ingestion table and then build a suitable data model from these ingestion tables. We aim to make this database the central hub to consume realtime data.

invariants
- holds all category data satisfying category schemas
- it is the only component that is in direct contact with users
- provides user interface and api access
- provides roles as a means of managing users
- provides tables where only the latest by timestamp category data is available (snapshots of current state)


## Discussion of streaming pipeline
### Strengths
#### Decoupled and cohesive
The pipeline is strongly decoupled, Every component depends on exactly its predecessor and every component has garantees from its predecessor (invariants). This allows us to scale, deploy, monitor and test each component individually and select technologies that are suited for exactly that component (since the compoenents are so clearly defined, we can choose technologies that have a more precise use case instead of selecting general purpose solutions). 

#### Scalable
The pipeline also allows to deal with any source or destination as independant as possible. Adding a source requires only creating a connector and the connector to satisfy the invariants. Then by design all the destinations that are interested in a category will receive the new source since its processed within a category. Adding a destination only requires adding a processor. Different workloads from the sources average out on the processor side since the processor work with categories and not sources.

Lastly, if we are not happy with the choice of one component we can simply switch it out by something else that also satisfies the invariants.

#### Simple
Simplicity allows us to better maintain, understand, develop and debug the platform. 

#### Restricted
Users can only directly interact with the database (or the other destinations) but not with the other components, this gives us maximal control over the pipeline.

#### Stability
The last two points, simplicity and restrictivness make this pipeline stable.

#### Flexible
If particular team has very good reasons not to use the database, we can set up a new processor for a new destination. If speed is very important, we can set up the destination to directly consume data from the buffer which is already cleaned up and prepared to fit the category schema it belongs to.

#### Requirements satisfied
It provides a unified solution for all streaming sources and all consumer groups / destinations.

### Weaknesses
#### Latency
Depends on implementation but if we use different machines or cluster of machines for each component, then Network latency will add up. Given the firms positioning as a midterm commodities trader and the fact that they became used to experiencing lags, as long as we can keep overall latency below 6 seconds, I'd consider it as 'good enough'.

#### The creation of these Category schemas
Right now we employed a bit of 'wishful thinking' concerning the actual design of the category schemas. In the real world, this step should be done with someone that has extensive domain knowledge of the business and its data which is one of the reasons why I choose these data sets for this project, since I have traded options and futures for several years. I will give the concrete category schemas at the very end of this document.

#### Development time 
If we want fault tolerance and scalability with respect to data throughput (not sources or destinations) then this will inherently take more time than just a single machine setup.

#### Costly
If we go with a cluster, then we will need to keep an eye on infrastructure cost. Notice however that we do not force that different components need to have different instances, in fact if something happens to satisfy the invariants of the buffer and the processor and we have enough scalability and fault tolerance, then its a good choice.


After talking to the stakeholders, most of them agree that if I am able to hold these invariants in prodcution then they can accept higher development times and latency. Cost and treatment of the fields is largely depandant on implementation we should not worry too much about it at this stage.


# Batch Processing lane

## Major components

### Category Schemas
The same definition as the connector form the Streaming lane since the same reasoning applies here.


### Connectors
A connector loads  data form a source without transforming it or altering it into the data Lake. Either in its original file format or as json if it did not have a format (data from api).

**invariants**
- loads all the data we want from the source into a designated place in the data lake as is in its raw state


### Data Lake
The data lake works as a simple persistent storage layer.

**invariants**
- All data from any source is stored in raw foramt
- For all sources and data there is a designeated storage place
- All data is clearly distinguishable by source and type (market data, supplier data, ect)

### Bronze layer
Here we still store the table raw but as tables. These are included in the Data Catalogue and Data lineage

**invariants**
- All data from any source is stored as a raw string table
- All data is clearly distinguishable by source and type (market data, supplier data, ect)
- The data here comes from the lake and nowhere else

### Silver layer
The bronze layer holds the data fitting a category schema. These are included in the Data Catalogue and Data lineage

**invariants**
- Only Cateegory data is stored as tables with the category schemas and correctly casted types
- The data stored here comes form the Bronze layer and nowhere else
- Data that does not qualify for the gold layer is also stored in a designated place.

### Gold layer
The Gold layer holds data fitting the data model. Users can access this layer directly.
These tables are included in the Data Catalogue and Data lineage

**invariants**
- Implements the data model with data from the silver layer

### Data marts
If a team has very good reasons not to use the data model then we prepare the data as they need in a designated place.

**invariants**
- Only The teams whose data mart this is , has access to it
- Data can come from any layer but one layer at most
- Data cannot come from the Gold layer


## Discussion of Batch pipeline
### Strengths
#### Decoupled and cohesive
The pipeline is strongly decoupled, Every component depends on exactly its predecessor and every component has garantees from its predecessor (invariants). This allows us to scale, deploy, monitor and test each component individually and select technologies that are suited for exactly that component (since the compoenents are so clearly defined, we can choose technologies that have a more precise use case instead of selecting general purpose solutions). 

#### Scalable
The pipeline also allows to deal with any source or destination as independant as possible. Adding a source requires only creating a connector and the connector to satisfy the invariants.
However, if a team uses a data mart that pulls from the bronze layer, then we'd need to change their data mart if they are also interested in the new source. For all downstream consumers that start from the silver layer, this is handled automatically by the category schemas. 

A new destination that is not well served by the data model can be implemented as a data mart

Lastly, if we are not happy with the choice of one component we can simply switch it out by something else that also satisfies the invariants.

#### Simple
Simplicity allows us to better maintain, understand, develop and debug the platform. 

#### Restricted
Users can only directly interact with their data mart or the gold layer but not with the other components, this gives us maximal control over the pipeline.

#### Stability
The last two points, simplicity and restrictivness make this pipeline stable.

#### Flexible
If particular team has very good reasons not to use the database, we can set up a new data mart for a new destination.

### Good foundation for DataOps and governance
By involving Data cataloging and lineage into the conceptual design, makes it easier to implement. Seperating the data that did not qualify for the next layer and storing these values, will make debugging and quality controls very easy.

#### Requirements satisfied
It provides a unified solution for all batch sources and all consumer groups / destinations.

### Weaknesses
### Data Model
Whether this devolves into lots of data marts that simply filter the source systems the particular teams are using right now or if this creates a strong simplification of all the data involved in the company is largely dependant on a very very good data model.

On the one hand it needs to be simple enough for people to understand but it also needs to be performant enough for peoples queries to finish in time. 

But it also needs to be flexible enough to adapt to schema evolution which will be a much bigger problem than with realtime data.

### Storage
In the worst case we are increasing storage cost by a factor of three or four. In the age of "cheap storage" this might not be a problem but it can become one if storage costs change significantly. In that case adapting the architecture will be very difficult since "cheap storage" is the axiom every module uses here and the entire pipeline is built on.

## What about schema evolution, governance policies, security, user management?

I consider these to be technical questions and thus they are discussed at the end of the Technical Architecture section.

# Technical Architecture
Now that we have a conceptual understand of what our data platform is and what properties the different parts need to exhibit we start looking for sutiable instances of these Abstractions.

## General thoughts on Technology selection
In general, a good piece of technology should satisfy the following constraints.

- It needs to have active maintenance (especially if open source)
- It needs to be well documented (especially if propriatery)
- It needs to be testable on small scale (for prototyping or proof of concept)
- It needs to be transparent in cost
- It should be widly used (easy to onboard new people)
- It should describe the main use cases it was built for
- It should be open about vulnerabilities and short commings

## Platform as Software
We want to treat our platform as a single piece of software. That is we want one code repository, one CI pipeline, one Test suite for the whole thing. This will make our life easier as a maintainer.

## Control Plane
The control plane is more a selection of practices and tools to make our life as platform developer as easy and pleasant as possible.

### Azure Cloud loggin as the system database
We want to have a system database that holds *all* metrics and logs from every single part of the platform. 

### System Dashboard and Alerts
From the system database (logging system) we can create alerts and dashboards for observability.

### Azure Container Registry
will hold *all* of our app images that need to be deployed somewhere.

### Azure Key Vault
*All* secrets are stored in a dedicated secrets key vault and nowhere else. Secrets are only pulled at runtime and are not presistently stored by *our* applications (for example database users need to be stored somewhere...)

### Kubernetes
Kubernetes is our primary tool for (self) managing our cluster for the streaming lane. 

### Docker
For abstracting as much as possible from the host machine and managing dependancies.

### Airflow
Airflow works for as as an workflow orchestrator on the platform level. So for example if we want to automate and schedule an workflow that involves multiple technologies (lets say Helm,Terraform, Azure and Kubernetes) then Airflow does it. It is basically an automated version of a platform maintainer for anything that can be automated and scheduled.

Important, it does not reinvent existing technologies or takes over their responsabilities!

A rule of thumb will be : if a workflow can be fully implemented using one technology then we do it there, if it involves multiple technologies, we do it in airflow. An exception to this rule are workflows that even though can be done in one technology, they cannot be automated or scheduled in said technology (or I feel like its better to do in airflow).

we want the airflow project to work as the single source of truth for infrastructure files (terraform, kubernetes) and platform files (category schemas, Kafka topic names, Ports, ect) for the Test and Production environment (dev is the problem of developers). This allows us to be 'DRY' on a system level. Another advantage of this, is that we can gitignore security critical files since we can generate them at will with the latest values for the platform and secrets from the key vault.

Lastly, these platform config files will be available to all services working on the platform.

**invariants**
- all airflow pipelines that are stateful, depend only on data that is stored in the system database
- No data form data sources is stored on the airflow instance of its meta data database
- No computations are performed on the airflow intance, not even lightweight batch processing jobs.
- Only it creates all infrastructure and platform level files for the environemnts test and prod and nothing else
- Every service has access to the platform config files

### Environments & CI
We distinguish the following environments

- (local) dev, minikube or kind for quick and lightweight platform testing
- test, deployment on a real but seperate test cluster together with seperate test system DB, seperate test Azure Container registry and the cluster size is non trivial and we conduct stresstesting
- prod, deployment to the real cluster that actually processes real data and serves real users. Container images from the test Azure Container registry are moved to the production Azure Container registry

It goes without saying that only code, configuration or app images that passes dev (unit tests software integration tests, ect) will be moved and tested on test environemnt. And only what passes the test environment will be pushed to production. The core idea is to design the test environemnt such that in 99.999% of cases it also passes the production environment.

If possible we first use github CI tools and then Azure CI tools.

### Monorepo of services
The base repository on github is an airflow app. Then there are two folders: services_streaming lane and services_batch_lane. Each of those folders contains subfolders for the respective services, they are set up in such a way that any service is developed in a devcontainer that mirrors the same Docker container that will be used in test and production as close as possible. Devcontainers are especially useful with IDE's like VScode where one can open the IDE inside the devcontainer and develop as if it were an app on the host machine. On top of that git works from the devcontainer as well and has the same build context as the docker file. 

Each of these Service folders contains a folder called 'infra_dev' which holds all infrastructure files that are not generated by airflow and can thus be modified for the developers needs.

**this gives**
- single source of truth
- consistency and dynamic dependencies of paltform variables / config via airflow
- possible to work on single services as 'independant' apps
- config files generating logic encodes platform level invariants

**things to be aware of**
one side effect of this approach (because apparently git does not work otherwise) is that the devcontainer has access to every file in the monorepo whereas the actual app image that goes to test and prod does not have (only platform config files). But the working dir of the devcontainer should be the same working dir as the test / prod app image so one notices this only by using the command line inside the devcontainer.

### CD
continous delivery is now a consequence of the structure of the platform, if we change services, or configuration (where configuration files are generatd by airflow) then these changes , if they are non breaking, will be applied *in the next run* of the pipeines.

We can add backdoors for cases where changes need to be applied to production immediately.



## Streaming lane
### Hetzner as cluster provider
Yes, its probably far easier to use Azure kubernetes services, but because budget is tight and we really want to run a non trivial cluster somewhat regurarly and I dont mind learning how to self manage a kubernetes cluster, this is the optimal solution.

### Ib connector
Handles the very unpleasant but extremely cheap realtime data source of interactive brokers which they call 'web' api.

**invariants**
- keeps Sockets for streaming alive
- keeps the client portal alive a
- automates start and authentication of the client portal
- processes the data and prepares it to be send to a kafka topic.
- sends data to the correct kafka topic.

### Kafka message broker + strimzi cluster operator
We use kafka as our buffer, for one because it satisfies our conceptual invariants and because its easy to ensure fault tolerance and scalability wiht it. Strimzi is for convenience when working with kubernetes and kafka.

**invariants**
- implements topics as category schemas 
- data is stored persistent for at least the entire runtime of the streaming pipeline
- provides at-least-once-devlivery

### Kafka as processor for Clickhouse
We can ingest directly from kafka topics into clickhouse, this works since we design the ingestion tables in clickhouse to be the category schemas and by the invariants of any connector, the data will already be ready for ingestion.

**invariants**
- dumps all of the data per topic into a designated ingestion table that implements the category schemas that topic represents

### Spark as processor for redis
as per 'Business_use_case_evolution.md' we need a high performance distributed computing engine to do the heavy computations fast enough.
Every Team can submit the computations that need to be performed and stored in redis. The engineering teams implements these formulas as spark jobs that will be executed by a set of executor pods on the streaming lane cluster. The spark executor pods read the necessary data from kafka directly instead from clickhouse, to keep latency minimal.

**invariants**
- each spark job corresponds to one computation request of a team
- the results of the computation are stored in redis (new destination)

### Clickhouse as distributed OLAP realtime store
Clickhouse is extremely well documented and the only distributed database I could find that has impressive performance metrics and seems to be suitable for time series data. It also provides fault tolerance and scaling capabilities. So no matter how much data is generated by all sources we can answer with more machines.

**invariants**
- for each topic there is an ingestion table whose scheam coincides with the category schema
- provides materialized views for snapshots per source and symbol of latest category data 
- Users do have access to this database
- Data is only stored for a few days 

### Redis as hot destination
The results form the heavy computations perfomed by spark are stored in redis to serve the teams with the lowest possible latency.

**invariants**
- Only the teams that submitted a job request for spark, have access on it.
- Only Spark can store data here.
- Data is deleted after 60 minutes

### Limitations of a Portfolio Project done by one single human
"few days" here means actually "few hours" and "60 minutes" means "5 minutes" to keep costs of running this project at bay. But the overall goal is to run a cluster of total size between 6 and 15 machines for 2h to 4h monday until friday during U.S cash market open where markets are most interesting.

## Batch lane
### Serverless functions whenever possible with Azure Functions
Serverless functions are very cheap and easy to manage. The only case where using them might be impossible is when accessing data sources that are behind a firewall and we need to deploy behind that firewall (with gitlab or github runners for example). Or if the function are computationally expensive and require lots of hardware ressources. But given my current understanding of the selected data sources, this is not the case.

These serverless functions will also perform the transformations from the lake to bronze and from bronze to silver since at the lake and bronze stage we might deal with many files or tables (you dont want to use SQL with many tables).

### Azure Data Lake Storage Gen2 as Data Lake

### Transformations, modelling and dataOps with DBT

### Azure Synapse for Data Warehousing
All the layers (bronze, silver, gold , data marts) are implemented in Synapse, the distinction between these layers will be made by naming conventions. Note that the invariants are enforced by DBT or the serverless functions that move Lake to bronze or bronze to silver.

### Microsoft Purview as our Data Catalogue, Governance and lineage tool


## Discussion

### security

### DevOps

### DataOps

### Failure Matrix
Wich failures should we expect and how are they handled?

### Adaption Matrix
What changes should we expect and how are they handled?

### Cost estimation and Alternatives

# Data Models and Category Schemas

## Category Schemas


