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

Lastly, these platform config files will be available to all services working on the platform. Some might say that this introduces strong coupling between the control plane and the environments and individual services. The developer of individual services should not concern himself with infrastructure since that is mostly determined by the actual data (throughput and frequency). And if specific services require specific infrastructure settings, then these can be applied via the control plane. Additionally the control plane will handle mostly files that are coupled anyways like category schemas, data models, ports ect so whether you create these files by hand or programmatically does not change the fact that they are coupled.

**invariants**
- all airflow pipelines that are stateful, depend only on data that is stored in the system database
- No data form data sources is stored on the airflow instance or its meta data database
- No computations are performed on the airflow intance, not even lightweight batch processing jobs.
- it creates all infrastructure and platform level files for the environemnts test and prod and nothing else
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
Clickhouse is extremely well documented and the only distributed database I could find that has impressive performance metrics and seems to be suitable for time series data. It also provides fault tolerance and scaling capabilities. Because of performance implications we heavily restrict our users to only read permissions since the main purpose of clickhouse is to provide the latest timestamps of the data collected from the realtime sources. Analysts primarily feed the data from clickhouse via client libraries into their models and algorithms or dashboard solutions on the infrastructure dediated to their team. On request we can create data marts for individual teams.

**invariants**
- for each topic there is an ingestion table whose scheam coincides with the category schema
- provides materialized views for snapshots per source and symbol of latest category data 
- Users do have access to this database
- Data is only stored for a few days 
- Users have only read permissions

### Redis as hot destination
The results form the heavy computations perfomed by spark are stored in redis to serve the teams with the lowest possible latency.

**invariants**
- Only the teams that submitted a job request for spark, have access on it.
- Only Spark can store data here.
- Data is deleted after 60 minutes
- Users have only read permissions

### Limitations of a Portfolio Project done by one single human
"few days" here means actually "few hours" and "60 minutes" means "5 minutes" to keep costs of running this project at bay. But the overall goal is to run a cluster of total size between 6 and 15 machines for 2h to 4h monday until friday during U.S cash market open where markets are most interesting.

## Batch lane
### Serverless functions whenever possible with Azure Functions
Serverless functions are very cheap and easy to manage. The only case where using them might be impossible is when accessing data sources that are behind a firewall and we need to deploy behind that firewall (with gitlab or github runners for example). Or if the function are computationally expensive and require lots of hardware ressources. But given my current understanding of the selected data sources, this is not the case.

These serverless functions will also perform the transformations from the lake to bronze and from bronze to silver since at the lake and bronze stage we might deal with many files or tables (you dont want to use SQL with many tables).

**invariants**
- transformed Data satisfies the invariants of the destination layer (brone,silver,gold, data marts) and add the necessary meta data
- Data going from Data Lake or bronzo to silver is transformed by serverless python functions

### Microsoft Fabric as Data Lake
The Data Lake serves to decouple the sources from the remaining parts of the batch pipeline. By storing all here we have a single source of truth for troubleshooting, dataOps and testing. 

We avoid a data swamp by using a container / directory structure to distinguish the data. This should be sufficient to identify the data. We wont add metadata at this stage since this would violate the "raw" data invariant.

**invariants**
- all data from every source is stored here in raw unaltered format
- every source has its own container
- if the data had a specific format at source, then its preserved
- if data did not have a specific format at source (API's) then its stored as json


### Transformations, modelling and dataOps with DBT
For transforms starting at  the silver layer, where we should have just a few tables, we use dbt.

**invariants**
- transformed Data satisfies the invariants of the destination layer (bronze,silver,gold, data marts) and add the necessary meta data
- Data going from silver to gold or data marts is only transformed in dbt

### Azure Fabric for Data Warehousing
All the layers (bronze, silver, gold , data marts) are implemented in Synapse, the distinction between these layers will be made by naming conventions. Schemas allow us to do access and user management so we take schemas as the logical mappings to our layers.
The neat thing about Fabric is that we can use spark instead of SQL which might come in handy for our 'business_use_case_evolution.md' since the analysts probably also want to perform these computations on historical data as well and not only on the streaming data.

**invariants**
- There is exactly one schema (container of tables) for the bronze layer
- There is exactly one schema for silver layer
- There is exactly one schema for gold layer
- For each data mart there is exactly one schema that holds all of its tables
- For every data source there is a 'source_category_dictionary' that lists the mappings from the source fields to the category fields

The last invariant will greatly enhance ease of use and data discovery and will make it easier to understand and use the category schemas


### Microsoft Purview as our Data Catalogue, Governance and lineage tool

**invariants**
- Every table from bronze, silver, gold and all data marts is in the data catalogue and lineage tool
- But users only see the data they have access to.

### Access and User management
Fortunately for us this is a problem that has been already solved for us. We copy the same access management processes that the IT of the firm is using. Usually this boild down to :
- client data can only be accessed by the client service team for that client
- external market data and economic data can be accessed by analysts team
- in general data from stakeholders can be accessed only by the team that works with that stakeholder
- user access needs to be fully compliant with any data compliance framework that may apply

If this is not enough then we need to employ row level security with specific business rules. For this first version of the project where our only users are analysts and we only store market data or economic data that is publicily available, we treat that data as "public" which means anybody can access it.

### security
Security is achieved if our platform does exactly what we want and nothing more. We have touched on this with azure keyvault but this is not enough. 

#### Firewalls
We need a firewall surrounding the hetzner cluser and blocking inbound traffic in such a way that we can authenticate as the admin (wiht a very strong and secure password) to access the cluster. We are only going to expose clickhouse (and later redis) to be accessible and every analyst that does have access, will have a dedicated user. 

We also build a firewall around our cloud infrastructure, to only allow access via dedicated ports if necessary or with microsoft user accounts.

#### User management clickhouse
Since we teardown and restart clickhouse we need to backup all users and their passwords (hashed) to our Data Lake. This allows us to define users and their rights once and teardown and restart clickhouse as much as we want.

#### Airflow User management
Every data engineer that has the rights for tempering with the control plane will need a dedicated airflow user. Due to the inconvenience of needing to define all users on startup of airflow, we will solve this via the control plane directly and pull the secrets from key vault and create the docker compose files at runtime in test and production.

#### Data security
Access rights per user are either determined by IT processes or row level security business logic. We can maintain this with some kind of Access matrix, where each role in the compoany in a particualr team is associated with access definitions of various ressources. But again at this stage since we only handle market data or public data and we only have analysts or engineers as users we will allow 'everyone to access everything'. This however should be immediately changed once the platform gets traffic from users.


**invariants**
- Everything that belongs to the platform is surrounded by exactly one firewall that blocks 'all' inbound traffic.
- Every entrypoint needs authentication via some kind of user.
- if we need to persist user secrets then we do so by hashing them.
- Users only have access to gold layer, their data marts, clickhouse and later redis and nowhere else.
- Users have only read permissions for gold layer 
- Users have read and write permissions only in their data mart

In the real world we would also employ password rotation plans (probably every 6 months at least), 2FA and minimal password requirements to further enhance security. We would also need to periodically reassess the impact of compliance frameworks on our current governance policies. Also if the users were not analysts that are comfortable with SQL, then I would not give them write permissions anywhere, not even in their data mart because if they make a mess out of it , its us that needs to clean after them.

### DevOps
We already covered a lot in the control plane but reiterate the most important things here.

**invariants**
- Only services, code, config that passes the dev environment can proceed to test cluster and registry
- Only services, code, config that passes the test environment can proceed to prod cluster and registry
- There is exactly one repository with all files and code for the entire platform, the monorepo.
- Each service is decoupled from host by docker containers.
- test and prod environment has its dedicated 'system db' (logging storage) and Azure container registry
- Technology stack is fixed to the tech discussed here and Python, scala, bash, json, yaml and nothing else
- If possible CI is doen via github, otherwise azure CI 

Note in a real setting we should also fix libraries and tools for python and scala.

### DataOps

Anytime data moves form one layer to the next, we store all data that did not qualify for the next layer in a seperate table with equal name but suffix "_unqualified" with all fields from the source table. This allows us to ivestigate which rows did not qualify and will make it significatnly easier to find out why.

Also for our current data sources it does not appear to make sense to store its 'history' if CFTC or EIA notice a mistake in their data and correct it, we will download the entire reports and simply rewrite the old and wrong values on the next pipelines run. This is very pragramtic and easy for small data srouces where we simply can laod and overwrite the whole thing. The analysts dont care what the old, possibly wrong values were they just want the (correct) data. This also makes the pipelines idempotent which is nice.

**invariants**
- Data that did not qualify for the next layer, is stored in a dedicated table on the same layer it is currenlty and with the suffix "_unqualified"
- Every table in the silver, gold layer and data marts is a materialized view
- All unqualified rows are ingested into a dataOps dashboard to oversee data quality.
- All tables in all layers are completely overwritten on every laod job.
- Silver, gold and data mart tables are enriched with the following meta data: time_ingested : timestamp with date, source_table_name : the fully qualifying table name from which this data comes, platform_worker_name : the name of the serverless function or dbt job that produces this table
- Any sight of 'missing' in the status field (see category schema below) is monitored, if the 'usual threshold' is breached an alert and email is created and send. 

In the real world it would be imperative to have at least one responsible person per source system that either contacts the source system maintainer to solve data quality issues or if the data is user generated to then fix the data in the source system.


## Failure Matrix
Wich failures should we expect and how are they handled?

### A pod in the streaming lane crashes
We can configure kubernetes to automatcially restart a node, depending on the system logs, the failure might have occured due to higher demand on the system in which case we need to scale vertically or horizontally, this needs to be implemented in the various components directly, for instance for the ib connector we have to scale vertically since we cannot run more than two pods (restriction from their 'web' api). There eis no 'realistic' risk of data loss in case of kafka, spark or clickhouse due to fault tolerance and idempotency. If an ib connector node fails then we lose data for the intervall where the remaining symbols are assigned and subscribed to by the remaining pod, but this cannot avoided. If it were an really important data source then we can set the lifeCycle intervalls very short (200ms) or implementing some kind of fault tolerance by streaming the same trading symbol from multiple pods this would increase the storage needed on kafka by the replication factor however clickhosue deduplicates data by default so there it would not be a problem. The real issue would be with L2 data where there is no 'event time' from interactive brokers so each machine that is a replica might have a different ingestion time so spotting duplicates in this case would be very difficult.

### Teardown or setup of the streaming lane fails 
if airflow struggles for some reason to setup or teardown the streaming lane, then this most likely requires manual intervention the airflow tasks would fail in which case we send a log to the system db where we can configure an alert for these kinds of logs with an automatic email.

### Hetzner cluster is not responding or unavailable
For this portfolio project : well bad luck I guess.
In the real world: Emergency migration plan to redeploy on AKS or other alternative, it goes without saying that this emergency plan needs to be tested periodically (every 4 months or so)

### Azure / Fabric is unresponsive or unavailable
We trust microsoft that backups are reliable on the cloud and that the data will be available eventually. For really important data we can do periodic backups into a different zone with cheeap cold long term storage which would be somewhat costly in the event of accessing that data. From that data we can either scale up clickhouse to also hold the historical data or ingress that data into a different cloud solution (google bigquery). Since we still have the backup from cold storage, we can simply delete the data in biguqery to avoid egress costs as soon as fabric is available.
In the meantime we dont teardown clickhouse but let it run to keep the historical data.

### Batch serverless job or DBT job fails
Send a log to system db, set up alerst for this type with automatic email then manual intervention (debugging and pushing the fix to prod)

### Data Quality deteriorates
Investiagtion via the dataOps dashboard and the "_unqualified" tables, taking action with the source system owners/ responsibles.

### Components in Streaming lane fail during runtime
If Clickhouse (very unlikely) failed:
- Data is still in kafka so we pull all the data from kafka prematurely into our Data Lake
- Try to redeploy clickhouse and fix the issue.

If Kafka (very unlikely) failed:
- Data is lost (unlikely with fault tolerance), we need to redeploy kafka as soon as possible 
- Connectors might fail since due to the producer api failing, need to restart or redeploy them as well
- Therefore we first try to backup the data in clickhouse, then redeploy the entire pipeline
- If last point fails, manual investigation is needed to find the root.

If a connector failed:
- Manual investigation needed
- if it somehow failed because the data does not fit any category schema, then continue with adaption matrix

If airflow failed:
- should only depend on data from the logs / system db so it recovers by restarting the pod
- All workflows need to depend only on system db that depicts current state of platform

If kubernetes (control plane) fails:
- Manual intervention

### Platform experiences lags and performance issues
- if unusual data throughput is the likely cause, we add nodes or we use more powerful nodes, we then need to re-evaluate scaling rules and assumptions in the control plane
- Otherwise manual intervention is needed

### Users have problems accessing the platform ressources
- This most likely requires manual investigation

## Adaption Matrix
What changes should we expect and how are they handled?

### New data source needs to be handled for streaming lane
- develop connector for that source that satisfies invariants
- push service, develop until it passed the test environment
- if passing the test environment is impossible because category schemas are not wide enough proceed with 'category schema needs to be modified'

### New data source needs to be handled for batch processing
- push service, develop until it passed the test environment
- done, connector do not transform at all and are not in contact with category schemas on the batch processing lane.

### New destination needs to be handled for streaming lane
- discussion with stakeholders about constraints and requirement
- development of processor (or reuse) and destination (or reuse) until test environment is passed
- destinations should not have impact on category schemas
- but they might have on data models so we need to re-evaluate the data models

### Category schema needs to be modified
- if it should shrink then we only shrink it after some countdown (6 months) to avoid unnecessary changes to the schema. After countdown if the field is still unused by all our data sources, we start a change period (6 months).
    - during the change period downstream users need to update their schemas to not rely on the fileds anymore. Here the streaming lane destinations and processors are included to make these changes.
    - after the users, we can update the gold layer that implements the data model to remove the fields.
    - after the gold layer we update the silver layer
    - finally we can simply remove the fields from the airflow config file
    - Airflow will force the streaming lane to comply with the new category schemas from now on by mounting the new version on the pods.
    - because we updated the sivler layer compliance is already ensured there.


- if it needs to grow we then define a canonical name for the fields and add them to the category schema defined in airflow
    - on next deployment of the streaming lane the changes are then applied to the entire system dynamically (every service depends on the category schema which is generated by airflow right before runtime, then mounted by kubernets as a file on the pods)
    - this change should be non breaking for the connectors on the streaming lane and the connectors on the batch lane.
    - if data marts use select * on unions or processors do something similar this might be breaking, so we simply forbid anyone from using constrcuts like these.
    - on the silver layer in fabric, airflow performs the changes to the schemas since automatically since it should be non breaking.

### Source schema from batch processing evolves
- if the schema expanded then we have the new fields in the lake and bronze layer automatically by design and thus in the data catalogue (discoverable)
    - data that goes form bronze to silver and only the old fields are mapped to the category schema so no problem here
    - anything that builds from silver wont experience problems since silver tables schemas correspond to the category schemas that remain unchanged
    - Problems might occure in the data marts if things like select * is used in unions, this is easy to solve by preventing users from using select * ...
    - If users show interst in the new fields and they cannot map to existing category schemas in the silver layer, proceed with 'Category schema needs to be modifies'
- if the schema shrank then silver and gold wont experience problems due to the category schemas remaining unchanged 
    - we will however notice an increase of 'missing' values 
    - but downstream consumer (especially data marts) might experience problems if they relied on these values
    - If it was impossible to foresee this change then there is not much we can do
    - If it was possible to foresee this change then we need to adapt our processes

### Analyst leaves the company, new Analyst joins company
- delete or create the user on clickhouse,redis, fabric (any destination the user was present)
- start process to evaluate access rights (usually it should be clear by role)


### Source schema from stream processing evolves
- Only the connectors might experience problems if their treatment of the schema was careless (select * in unions)
- The connector maintainer needs to provide a quick fix if it broke due to a expanding schema (which should be easy to aovid) this might cause an expansion of category schemas which we covered above.
- If the schema shrank then we need to immediately notify downstream users if the connecotr already adapted, there should nothing left for us to do since category schemas remain stable.

### Cost estimation and Alternatives

### Final remarks 

Its clear that we should avoid the shrinking of the category schemas since that event poses the greatest risk and the most cost to complete. Growing a category schema is unpleasant but managable. So one naive solution would be to make the category schemas as wide as possible, which could give us problems at scale, however if we manage to design category schemas that have at most 30 columns and are few in numbers (maybe 6 at most) but almost never change because they cover most of fields out there, then this is a very good solution.

Also a good observability solution should cover our adaption and failure matrix.

Finally for the current state of the projet where we are only handling market data on the streaming lane, schema evolution is very unikely (The understanding of a 'tick' or 'order book' has been quite consistent over the last decade)

# Data Models and Category Schemas
We have postponed the elephant in the room long enough and it goes without saying that the category schemas are what can make this architecture really good or really bad. One fundamental design decision is that downstream consumer will only get *stateless* data. This means that if the streaming source is stateful, then the connectors job is to convert incoming data into a stateless version to fit to the category schemas. 


## Category schemas
In the real world you really should define and maintain a glossar of possible values for the fields. The glossar together with the category schemas will greatly improve data discovery in the warehouse.

In the following all timestamps conform to ISO 8601:2004 and are in UTC+0 timezone. Any field containing "_unit" write the unit in the format : amount_name , examples 1000_barrels, 1_USdollar, 5_contracts

Category schema "tick_market_data"

source_system_name NOT nullable
component_instance_name NOT nullable
ingestion_time NOT nullable
exchange_identifier NOT nullable
symbol_identifier NOT nullable
symbol_type NOT nullable // futures, stocks, options, forex, ...
price NOT nullable
price_unit NOT nullable
size NOT nullable
size_unit NOT nullable
tick_time NOT nullable 


Category schema "L2_market_data"

source_system_name NOT nullable
compoent_instance_name NOT nullable
ingestion_time NOT nullable
exchange_identifier
symbol_identifier NOT nullable
symbol_type 
price NOT nullable
price_unit NOT nullable
size NOT nullable 
size_unit NOT nullable
side // ask or bid NOT nullable
level // 1 to n where n isthe maximum level that we are streaming NOT nullable 
max_level // this is n at the time of streaming NOT nullable
l2_time // the time this event occured NOT nullable 

Category schema "indicators_market_data"
source_system_name NOT nullable
compoent_instance_name NOT nullable
ingestion_time NOT nullable
market_event_time // tick_time or l2_time NOT nullable foreign key
symbol_identifier // Not nullable
indicator_identifier // Open_interest, moving_averages
indicator_value // Open interest, moving averages, ... 
indicator_value_unit // 1_contract, 1_percent, ...
indicator_type // moving average, oscillator, model, ...


Category schema "numeric_economic_data"

source_system_name NOT nullable
compoent_instance_name NOT nullable
ingestion_time NOT nullable
publsher_identifier // publisher is the institution who publishes the data NOT nullable
release_identifier // a release may publsh multiple results , CFTC-COT-report, EIA-weekly-report, ect NOT nullable
release_time // the time when the report was released NOT nullable 
release_field // which field of the release, 'Crude-Oil Stocks Forecast' Not nullable
release_field_value // the (numeric) value of said field NOT nullable
release_field_value_unit NOT nullable


It seems that these category schemas are well designed they are few in numbers, not too wide but still cover every possible field I could think of without loosing information.


### Discussion
If we dont split the indicators into their own category and these schemas wont scale well with increasing data throughput. The join donwstream should not be a huge issue as long as we keep the ingestion tables for tick_market_data and l2_market_data sorted by (source_system,symbol_identifier,) 

## Data Models
