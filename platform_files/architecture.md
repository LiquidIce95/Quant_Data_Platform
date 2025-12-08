You can open the architecture diagram in draw.io with the file 'architecture_diagram.drawio.xml'
Or you can open the svg file

# Conceptual Architecture

In this section we define the conceptual architecture which is completely technology agnostic and focuses on the key relationships that we need to build a good platform for this use case.

We do this by defining **components** which can be viewed as abstract entities. We do not care about what exactly these entities are, we only care about the properties they need to exhibit in order for the platform to achieve its goal.

Then tech selection boils down to selecting the tools that satisfy the definitions of our components, because then they satisfy the inferred relationships which compose the platform. The data platform is thus an instantiation of the conceptual architecture via the instances of the components that are given by concrete technologies.


# Major Design decision

## Lambda Architecture

As you know from the `Business_use_case.md` and the user stories, we have the following requirements and constraints to satisfy.

1. The platform has to be cheap (200 dollars per month at most)  
2. We need a centralized place for the data to manage access and users  
3. That place should allow API access  
4. We should have the capacity to add or remove sources and destinations  
5. The platform should be responsive or at the very least not lag noticeably  
6. We need processes to ensure quality and governance practices  
7. My life as a maintainer should be as easy as possible  
8. The processing of the data cannot take too long otherwise people start using the source systems again  
9. The platform needs to be stable and reliable otherwise people start using the source systems again  
10. Other people need to know that the system is healthy and working (SRE, security, users, etc.)

The overall architecture resembles the **Lambda architecture**. The reasons why I chose such architecture are as follows.

- Real-time data users care about recency not volume, hence we can use a specialized database for that  
- Historical data users care about volume not recency, hence we can use a specialized database for that  
- The split minimizes cost and maximizes performance for batch and real-time data (replaying batch data as real-time introduces unnecessary cost and performance penalty on the system)  
- Collected real-time data can be treated as a batch job to insert into the historical database  
- Real-time data sources usually have much slower schema evolution  

# Streaming lane

## Major Components

We will assume that components themselves do not fail but that communication between them can. In reality we try to realize this property of components by using distributed solutions that provide scaling and fault tolerance. In the *technical* architecture we do consider that components can fail and cover these situations in a failure matrix, together with other things that we need to consider operationally like incidents and adaptions.

### Category Schema

A **Category Schema** is a set of canonical field names for a type of data.

We say **category row** for a row / tuple in the category schema. We call the schema of data from a source system **source schema**. We may say **source field** or **category field** to refer to fields from a source schema or category schema respectively.

**Invariant**  
- Every source schema needs to be related to a subset of fields of at least one category schema.
- all category schemas have a field uniquely identifying the source system, we call that field "source_system_name"
- Every category schema defines a designated timestamp and a designated identifier
- the designated identifier needs to be unique within a source system
- the designated timestamp needs to be unique within a source system and designated identifier

Note: Category schemas are **conceptual**. Physical schemas must at least cover all the category fields, but may add additional technical fields (for example multiple ingestion timestamps) without breaking the category contract. Ideally we want the category schema to preserve or even enhance information from the relation between the source fields and category fields. Together with a **Glossar** which defines the domains of the category fields we greatly enhance data discovery and understanding.

### Connectors

**Definition**  
A piece of software that extracts real-time data from a data source, maps the source fields to the category fields and fills out the category fields with correct format.

**Invariants**

- Selects at least one category  
- Maps the source field names to category field names  
- Transforms the values to fit the category fields dtypes/domains and units  
- Sends the data as a category row to the buffer  
- Can resend data if lost or corrupted  
- Only connectors can access source systems  
- Each source system is managed by at least one connector  
- If data from the source system does not provide data relatable to the designated timestamp, then the connector fabricates a timestamp to use.
- If data from thee source system does not provide data relatable to the designated identifier, then the connector fabricates an identifier to use. 

Important to realize is that if we disallow connectors to communicate with each other, then no connector can ensure ordering of the data across source systems. If we do allow communication between different connectors then we introduce high coupling and overheat. We choose connectors to be isolated since ordering is not needed in this pipeline.


### Buffer

**Definition**  
Persistent storage that simply holds the category data / rows belonging to a category schema.

**Invariants**

- The category data contains at least all the fields of the category schema 
- The category data is formatted according to the category fields
- Data is stored persistent for at least the entire runtime of the streaming pipeline  
- Provides at-least-once-delivery  
- Provides redelivery of data if lost or corrupted during transmission
- Only connectors can store data in the buffer
- Only processors can access data from the buffer

### Processor

**Definition**  
Gets the category data from the buffer and prepares it for a destination. Sends data to that destination.

**Invariants**

- For every destination there is at least one processor  
- Prepares the category data for its destination  
- Loads the prepared category data to its destination  
- Reprocessing the same data for the same destination again is idempotent  
- The processor must be able to reprocess lost or corrupted data  

### realtime store

The realtime store is the default destination of realtime data. We store data from all category schemas in a dedicated ingestion table and then build materialized views for users to get the latest values of the data, according to the designated timestamp for that category schema. With materialized view here we mean a physical table that is automatically updated if its source table in the store changes.

**Invariants**
- Implements category schemas as ingestion tables
- These ingestion tables are ordered and indexed sparsely by source_system_name, designated identifier, designated timestamp
- It is the only component that is in direct contact with users  
- Provides user interface, API access and SQL use  
- Provides roles as a means of managing users  
- Provides users with access to materialized views that present the most recent data according to the designated timestamp, by source system and designated identifier.
- Only processors can store data in the realtime store

The idea is to lay foundations to maximize query and ingestion performance via the desiganted timestamps and identifiers.  

## Discussion of streaming pipeline

### pipeline garuantees

1. end-to-end at least once delivery of data generated at the source system
2. availability of data by source_system_name , designated identifier  by latest designated timestamp
3. Transmission Failure isolation, a failure in transmission concerns at most two components
4. schema normalization garuantee completely decouples source form remaining parts of pipeline
5. information preservation, No loss of semantic information before the realtime store
6. Replay / backfill guarantee, any destination can be rebuild from buffer
7. Idempotency, reprocessing data from previous compoenent is idempotent
8. Source, Destination independance, A change at source does not (necessarily) imply a change at destination and vice versa


### Strengths

#### No ordering required

Which makes out of order or late arrivals form source systems not a problem. Trying to impose order garuantees, especially in a many sources setting, is unrealistic anyways.

#### Decoupled and cohesive

The pipeline is strongly decoupled. Every component depends on exactly its predecessor and every component has guarantees from its predecessor (invariants). This allows us to scale, deploy, monitor and test each component individually and select technologies that are suited for exactly that component (since the components are so clearly defined, we can choose technologies that have a more precise use case instead of selecting general purpose solutions).

#### Scalable

The pipeline also allows us to deal with any source or destination as independent as possible. Adding a source requires only creating a connector and the connector satisfying the invariants. Then by design all the destinations that are interested in a category will receive the new source since it is processed within a category. Adding a destination only requires adding a processor. Different workloads from the sources average out on the processor side since the processors work with categories and not sources.

Lastly, if we are not happy with the choice of one component we can simply switch it out by something else that also satisfies the invariants.

#### Simple

Simplicity allows us to better maintain, understand, develop and debug the platform.

#### Restricted

Users can only directly interact with the database (or the other destinations) but not with the other components. This gives us maximal control over the pipeline.

#### Stability

The last two points, simplicity and restrictiveness, make this pipeline stable.

#### Flexible

If a particular team has very good reasons not to use the database, we can set up a new processor for a new destination. If speed is very important, we can set up the destination to directly consume data from the buffer which is already cleaned up and prepared to fit the category schema it belongs to.

#### Requirements satisfied

It provides a unified solution for all streaming sources and all consumer groups / destinations. In particular the realtime store provides users with access to the latest data.

### Weaknesses

#### Latency

Depends on implementation but if we use different machines or clusters of machines for each component, then network latency will add up. Given the firm’s positioning as a midterm commodities trader and the fact that they became used to experiencing lags, as long as we can keep overall latency below 4 seconds, I'd consider it as “good enough”.

#### Performance of the realtime store
Since the data is unordered we need to sort at least once the data in the realtime store, this presents a risk for performance.

#### The creation of these Category schemas

Right now we employed a bit of “wishful thinking” concerning the actual design of the category schemas. In the real world, this step should be done with someone that has extensive domain knowledge of the business and its data which is one of the reasons why I choose these data sets for this project, since I have traded options and futures for several years. I will give the concrete category schemas at the very end of this document.

#### Development time

If we want fault tolerance and scalability with respect to data throughput (not sources or destinations) then this will inherently take more time than just a single machine setup.

#### Costly

If we go with a cluster, then we will need to keep an eye on infrastructure cost. Notice however that we do not force that different components need to have different instances, in fact if something happens to satisfy the invariants of the buffer and the processor and we have enough scalability and fault tolerance, then it is a good choice.

After talking to the stakeholders, most of them agree that if I am able to hold these invariants in production then they can accept higher development times and latency. Cost and treatment of the fields is largely dependent on implementation; we should not worry too much about it at this stage.

#### Risk of backpressure of the connectors
We need to monitor and react on backpressure by scaling vertically or horizontally which is possible by the invariants of connectors.



# Batch Processing lane

## Major components

### Category Schemas

The same definition as in the streaming lane. In fact we will define one set of category schemas for both lanes.

### Extractors

**Definition**  
An extractor loads data from a source without transforming it or altering it into the data lake. Either in its original file format or as JSON if it did not have a format (data from APIs).

**Invariants**

- Loads data from the source into the Data Lake, or loads data from Data Lake to Bronze, as-is in its raw state  
- No metadata is added by extractors to prevent accidental changes to the data  
- Reprocessing the same data for the same destination is idempotent  
- Extractors can reprocess the data if the destination data is lost or corrupted  

### Connectors

A connector is more or less consistent with the notion of connectors we had in the streaming lane.

**Invariants**

- processes data from the bronze layer  
- There is at least one connector for each table in the bronze layer  
- Selects for its source schema at least one category  
- Maps the source fields to the category fields  
- Reprocessing the same data for the same destination is idempotent  
- Connectors can reprocess the data if the destination data is lost, has failed or is corrupted  
- The only possible destinations are the silver layer or a data mart  

Notice an important exception from this rule, if a Connector moves data from bronze to a data mart, then it does not need to comply with category schemas, the data mart decides how the data needs to conform.

### Transformers

Transformers start from the silver layer and transform data into the gold layer or data marts.

if a transfomer transforms data from the silver layer then the following holds:
**Invariants**
- Transforms data from the silver layer 
- There is at least one transformer for each table in the silver layer  
- Transforms the data from the silver layer to satisfy the invariants of the destination  
- The destination is the gold layer
- Reprocessing the same data for the same destination is idempotent  
- Transformers can reprocess the data if the data is lost or corrupted.

if a transformer transforms data form the gold layer then the following hols:
**invariants**
- Transforms data form the gold layer
- The destination is a data mart and nothing else can be a destination
- Reprocessing the same data for the same destination is idempotent  
- Transformers can reprocess the data if the data is lost or corrupted.

Notice an important exception from this rule, if a transformer mvoes data from silver to a data mart, then it does not need to comply with the data model, the data mart decides how the data needs to be transformed.

### Data Lake

The data lake works as a simple persistent storage layer.

**Invariants**

- All data from any source is stored in raw format  
- For all sources and data there is a designated storage place  
- All data is clearly distinguishable by source_system and type (market data, supplier data, etc.)  
- Only extractors can load into the Data Lake

Beware that 'source' here means strictly batch processing sources.

### Bronze layer

Here we still store the data raw but as tables. These are included in the data catalogue and data lineage.

**Invariants**

- All data from any source is stored as a raw string table  
- All data is clearly distinguishable by source and type (market data, supplier data, etc.)  
- The data here comes from the lake and nowhere else  
- Only extractors can load into the bronze layer

### Silver layer

The silver layer holds the data fitting a category schema. These are included in the data catalogue and data lineage.

**Invariants**

- Only category data is stored as tables with the category schemas implemented  
- The data stored here comes from the bronze layer and nowhere else  
- Data that does not qualify for the gold layer is also stored in a designated place 
- Only connectors can load into the silver layer 


### Gold layer

The gold layer holds data fitting the data model. Users can access this layer directly. These tables are included in the data catalogue and data lineage.

**Invariants**

- Implements the data model with data from the silver layer  
- Only Transformers can load data into the gold layer
- Users here have only read permission and nothing else

### Data marts

If a team has very good reasons not to use the data model then we prepare the data as they need in a designated place.

**Invariants**

- Only the teams whose data mart this is has access to it  
- Data can come from any layer but one layer at most (it cannot come from different layers)  
- Users here have read and write permission

### Historical store
Or We may call it warehouse since it will be also the hub for non market data.

**invariants**
- Users have only access to this compoenent no other component of the batch processing pipeline
- It contains the bronze, silver and gold layer

If Users need access to raw data in the data lake (because they need the original file format for example) then we can create a data mart for that team holding these files. We would need to define a new entity that can perform these kinds of jobs since no current entity can move data from the data lake directly to a data mart. Because the business only deals with structured data there is no need to work with original files directly.

## Discussion of Batch pipeline

### Pipeline garuantees

1. Raw data preservation, All data is available in its raw format
2. Idempotency, Data from any layer and the data lake, can be reprocessed multiple times while the results being consistent.
3. Lineage, all data in a layer or a data mart, came from exactly one layer.
4. Category schema normalisation guarantee, silver layer implements the category schemas as a base line for downstream components.
5. Restriction, Users can only access gold layer or data marts, each layer has a specific type of entity that can access it 
6. Backfill, all layers and marts can be rebuild from the Data lake

Important to note is that the buffer and realtime store will be two source systems for batch processing.

### Strengths

#### Decoupled and cohesive

Same arguments as in the streaming lane.

#### Scalable

The pipeline also allows us to deal with any source or destination as independent as possible. Adding a source requires only creating a connector and the connector satisfying the invariants. However, if a team uses a data mart that pulls from the bronze layer, then we'd need to change their data mart if they are also interested in the new source. For all downstream consumers that start from the silver layer, this is handled automatically by the category schemas.

A new destination that is not well served by the data model can be implemented as a data mart.

Lastly, if we are not happy with the choice of one component we can simply switch it out by something else that also satisfies the invariants.

#### Simple

Same argumetns as in the streaming lane.

#### Restricted

Users can only directly interact with their data mart or the gold layer but not with the other components, this gives us maximal control over the pipeline.

#### Stability

The last two points, simplicity and restrictiveness, make this pipeline stable.

#### Flexible

If a particular team has very good reasons not to use the database, we can set up a new data mart for a new destination.

### Good foundation for DataOps and governance

By involving data cataloguing and lineage in the conceptual design, we make it easier to implement. Separating the data that did not qualify for the next layer and storing these values will make debugging and quality controls very easy.

#### Requirements satisfied

It provides a unified solution for all batch sources and all consumer groups / destinations.

### Weaknesses

#### Data Model

Whether this devolves into lots of data marts that simply filter the source systems the particular teams are using right now or if this creates a strong simplification of all the data involved in the company is largely dependent on a very good data model.

On the one hand it needs to be simple enough for people to understand but it also needs to be performant enough for peoples queries to finish in time.

But it also needs to be flexible enough to adapt to schema evolution which will be a much bigger problem than with real-time data.

#### Storage

In the worst case we are increasing storage cost by a factor of three or four. In the age of “cheap storage” this might not be a problem but it can become one if storage costs change significantly. In that case adapting the architecture will be very difficult since “cheap storage” is the axiom every module uses here and the entire pipeline is built on.

## What about schema evolution, governance policies, security, user management?

I consider these to be technical questions and thus they are discussed at the end of the Technical Architecture section.


## Discussion of conceptual architecture

Right now we have the following bets going:

- We can create good category schemas
- We can create good data models from these schemas
- Ordering guarantees along pipelines do not matter (to us)
- We can make components almost unfailable or failures of components can be handled
- Performance will be good enough

# Technical Architecture

Now that we have a conceptual understanding of what our data platform is and what properties the different parts need to exhibit, we start looking for suitable instances of these abstractions.

## General thoughts on Technology selection

In general, a good piece of technology should satisfy the following constraints.

- It needs to have active maintenance (especially if open source)  
- It needs to be well documented (especially if proprietary)  
- It needs to be testable on small scale (for prototyping or proof of concept)  
- It needs to be transparent in cost  
- It should be widely used (easy to onboard new people)  
- It should describe the main use cases it was built for  
- It should be open about vulnerabilities and shortcomings  

In addition to that we need the components to be as reliable as possible since we implicitly assumed them not to crash or fail. So we need technologies that run on multiple machines, scale and provide fault tolerance.

## Platform as Software

We want to treat our platform as a single piece of software. That is we want one code repository, one CI pipeline, one test suite for the whole thing. This will make our life easier as a maintainer.

## Control Plane

The control plane is a selection of practices and tools to make our life as platform developer as easy and pleasant as possible.

### Azure Monitor for logging and metrics

We want persistent and accesible storage that holds **all** metrics and logs from every single part of the platform.

### System Dashboard and Alerts

From the Azure monitoring we can create alerts and dashboards for observability. The specific events we are interested in observing are covered in the failure, adaption and incident matrix.

### Azure Container Registry

Will hold **all** of our app images that need to be deployed in a registry. We will distinguish between a 'test' and 'prod' registry, how the images move between the registries is explained in the CI section.

### Azure Key Vault

**All** secrets are stored in a dedicated secrets key vault and nowhere else. Secrets are only pulled at runtime and are not persistently stored by **our** applications.

### Kubernetes

Kubernetes is our primary tool for (self) managing our cluster for the streaming lane.

### Docker

For abstracting as much as possible from the host machine and managing dependencies.

### Platform configuration files
The provide a unified and simple way of defining properties of infrastructure and components. They will be provided as inputs for the templating engines to handle tool specific formats and language. The platform files also serve as source of truth for all components and infrastructure on the platform, this allows us to *force* invariants and constraints.

### Templating engines
Instead of writing configuration files by hand we try to parametrize them with the variables that matter on a platform level, the engine then produces the correctly formatted files with the values we actually care about. This allows us to decouple system level configuration from tool specific configuration. For example a service developer only wants to specify start size of the cluster, namespace, number of pods and just minimal rbac and permissions. He provides that information via a dedicated config file that implements our platform logic and contract which also prevents him to change stuff he should not.

### Airflow

Airflow works for us as a workflow orchestrator on the platform level. So for example if we want to automate and schedule a workflow that involves multiple technologies (let’s say Helm, Terraform, Azure and Kubernetes) then Airflow does it. It is basically an automated version of a platform maintainer for anything that can be automated and scheduled.

Important, it does not reinvent existing technologies or takes over their responsibilities.

A rule of thumb will be: if a workflow can be fully implemented using one technology then we do it there, if it involves multiple technologies, we do it in Airflow. An exception to this rule are workflows that even though can be done in one technology, they cannot be automated or scheduled in said technology (or I feel like it is better to do in Airflow).

We want the Airflow project to work as the single source of truth for infrastructure files (Terraform, Kubernetes) and platform files (category schemas, Kafka topic names, ports, etc.) for the test and production environment (dev is the problem of developers). This allows us to be “DRY” on a system level. Another advantage of this is that we can gitignore security critical files since we can generate them at will with the latest values for the platform config files and secrets from the key vault.


Lastly, these platform config files will be available to all services working on the platform by mounting them on their app containers.

**Invariants**

- All Airflow pipelines that are stateful depend only on data that is stored in Azure monitoring 
- All Airflow pipelines are idempotent
- No data from data sources is stored on the Airflow instance or its metadata database  
- No computations are performed on the Airflow instance, not even lightweight batch processing jobs  
- It creates all infrastructure and platform level files for the environments test and prod and nothing else can do that
- Every service has access to the platform config files as mounted files on their app images

Ovious excpetion is the data stored in the metadatabase of airflow. With these invariants its not a problem if airflow becomes unavailable or crashes, because the pipelines are idempotent and can be rerun and those piplines that are stateful can get the current state from azure monitoring and lastly the config files can be regenarated if needed.

To avoid airflow becomming unavailable or stale for too long we can periodically send heartbeats from airflow to azure monitoring and set alarms if airflow has not send an heart beat for a perfiod of time.

### Environments & CI

We distinguish the following environments:

- (Local) dev, minikube or kind for quick and lightweight platform testing  
- Test, deployment on a real but separate test cluster together with separate test system DB, separate test Azure Container registry and the cluster size is non trivial and we conduct stress testing  
- Prod, deployment to the real cluster that actually processes real data and serves real users. Container images from the test Azure Container registry are moved to the production Azure Container registry  

It goes without saying that only code, configuration or app images that pass dev (unit tests, software integration tests, etc.) will be moved and tested on the test environment. And only what passes the test environment will be pushed to production. The core idea is to design the test environment such that in 99.999% of cases it also passes the production environment.

If possible we first use GitHub CI tools and then Azure CI tools.

### Monorepo of services

The base repository on GitHub is an Airflow app. Then there are two folders: `services_streaming_lane` and `services_batch_lane`. Each of those folders contains subfolders for the respective services, they are set up in such a way that any service is developed in a devcontainer that mirrors the same Docker container that will be used in test and production as close as possible. Devcontainers are especially useful with IDEs like VS Code where one can open the IDE inside the devcontainer and develop as if it were an app on the host machine. On top of that git works from the devcontainer as well and has the same build context as the Docker file.

Each of these service folders contains a folder called `infra_dev` which holds all infrastructure files that are not generated by Airflow and can thus be modified for the developers needs.

**This gives**

- Single source of truth  
- Consistency and dynamic dependencies of platform variables / config via Airflow  
- Possible to work on single services as “independent” apps  
- Config file generating logic encodes platform level invariants  

**Things to be aware of**

One side effect of this approach (because apparently git does not work otherwise) is that the devcontainer has access to every file in the monorepo whereas the actual app image that goes to test and prod does not have (only platform config files). But the working dir of the devcontainer should be the same working dir as the test / prod app image so one notices this only by using the command line inside the devcontainer.

### CD

Continuous delivery is now a consequence of the structure of the platform, if we change services, or configuration (where configuration files are generated by Airflow) then these changes, if they are non breaking, will be applied **in the next run** of the pipelines.

We can add backdoors for cases where changes need to be applied to production immediately.

### Discussion
The control plane might look like overkill and unncecessary bureaucraty, but if you really think about it, it *automates* and *formalizes* tasks you would do anyways just reliable. And for greenfield projects like this, especially with real users, change is garanteed to happen, the control plane makes sure that these changes happen within our developed architecture to ensure the necessary relationships. The following is just a narrow catalogue of problems it avoids.

- Test environment runs fail immediately because kubernetes or terraform config files have syntax errors.
- A connector uses the wrong buffer port, wrong ingestion table.
- A component is deployed as a single machine instance, our reliability assumptions are under threat.
- A connector deos not know the category schemas or uses an outdated version of it, buffer invariants are possibly violated.
- Everyone just needs to understand the platform specific config language and not ten different versions of config languages.
- job schedulings are not spread across different technologies.
- Debugging does not devolve into hopping across machines and tools, all the relevant info for debuggin is in the monitoring solution.
- Common failures, adaptions and incidents covered by the respective matrices can be automated or easier handled.

As a last remark I want to mention that the control plane is something that is very slowly changing since its far away from the users and in our full control.

## Streaming lane

### Hetzner as cluster provider

Yes, it is probably far easier to use Azure Kubernetes Services, but because budget is tight and we really want to run a non trivial cluster somewhat regularly and I don’t mind learning how to self manage a Kubernetes cluster, this is the optimal solution. Hetzner is not only cheap but also has positive reviews and a well documented REST api for managing clusters. And I love the idea of spending less money on microsoft so there's that.

### Instances

Ib connector is a connector for our source system of interactive brokers web api websocket streaming data.
kafka is the buffer where kafka topics represent data conforming to a particular category schema. So the number of topics coincides with the number of category schemas. Spark is a processor for heavy computations that are unfeasible in the realtime store. The realtime store is given by clickhouse. As long as access to clickhouse is not a problem (latency or performance wise) we will always use clickhouse as destination.

### Ib connector

Handles the very unpleasant but extremely cheap realtime data source of Interactive Brokers which they call “web” API. We call *symbols* the trading futures, stocks or option identifiers that we are streaming data from. Recall that **kafka** will **instantiate** the **buffer** component and kafka **topics** represent data conforming to a **category schema**. 

The designated identifier of the category schemas the ib connector sends to is called 'symbol' referreing to the trading symbols that we want to stream from interactive brokers.

**Invariants**
- Is an instance of a 'connector' component
- handles data comming form interative brokers web api wesocket and nothing else
- Keeps sockets for streaming alive  
- Keeps the client portal alive  
- Automates start and authentication of the client portal  
- Processes the data and prepares it to be sent the Kafka topic of the category scheam 
- Sends the raw data to the designated topic of the corresponding category schema  
- Sends data to the correct Kafka topic via the kafka producer api, waits for acknowledgements, resends otherwise 
- Implements scaling via symbol sharding 

Important to note is that due to time constraints we are *not* streaming l2/order book data and we have *not* built in fault tolerance. Fault tolerance can be achieved the same way as we implemetned scaling, via the kubernetes api. For given replication factor, select peers of the ib connector namespace to create replicas of the data. In the context of streaming this means that these replica pods will also stream the same symbols. This will create duplicates (ignoring metadata) equal in number to the replication factor. 

In this setting, since we are using clickhouse which deduplicates the data per default, this would be already taken care of. The scaling also provides a 'weak fault tolerance', if a pod crashes then in the next connections life cycle (that we can set to happen every x milliseconds) the symbols will be redistributed on the remaining pods and they will restart the streams for those symbols. So the maximum time intervall of data we can loose is given by computation time + latency + life cycle intervall.


### Kafka message broker + Strimzi cluster operator

We use Kafka as our buffer, for one because it satisfies our conceptual invariants and because it is easy to ensure fault tolerance and scalability with it. Strimzi is for convenience when working with Kubernetes and Kafka.

**Invariants**
- Is an instance of a buffer
- For each category schema there are exactly two topics for it, one for the processed data one for the raw data
- Data is stored persistent for at least the entire runtime of the streaming pipeline  
- Provides at-least-once-delivery  
- during runtime, provides an arbitrary number of redeliveries
- Sends acknowledgements to the connector to indicate successfull receival of data

As long as the connector does not fail (which can be made less likely with fault tolerance as discussed with the ib connector example) then data is garuanteed to arrive in the buffer and processors can reprocess the data as many times as they want, since its persistent for the entire runtime of the pipeline and kafka enables resending of already send data. Because data is very important to the core business, any loss of it is a direct loss in value.

Another neat property is that by holding the data for the entire runtime in the buffer, if downstream components somehow fail permanently, we can pull the data from the kafka topics directly and reprocess the data as a batch job. Kafka further enhances this data arrival garuantee by providing fault tolerance and scaling. 

### Kafka as processor of category data for ClickHouse

We can ingest directly from Kafka topics into ClickHouse, this works since we design the ingestion tables in ClickHouse to be implementations of category schemas and by the invariants of any connector, the data will be ready for ingestion.

**Invariants**
- is an instance of a processor
- Dumps all of the data per topic into a designated ingestion table that implements the category schemas that topic represents 
- Waits for acknowledgements from clickhouse, resends data otherwise 

### Spark as processor of heavy computations for Clickhouse

As per `Business_use_case_evolution.md` we need a high performance distributed computing engine to do the heavy computations fast enough. Every team can submit the computations that need to be performed and stored in clickhouse. The engineering teams implement these formulas as Spark jobs that will be executed by a set of executor pods on the streaming lane cluster. The Spark executor pods read the necessary data from Kafka directly instead from ClickHouse, to keep latency minimal.

**Invariants**
- is an instance of a processor
- Each Spark job corresponds to one computation request of a team  
- The results of the computation are stored in designated ingestion tables in clickhosue 

This is one of the *main* use cases of spark.

### Spark as processor for the Data Lake
Spark is going to push the raw data in kafka, the processed data in kafka, the processed data in clickhouse, into the Data Lake. We will use as large as possible batch sizes to minimize performance impact on the pipeline and keep file creation in the data lake minimal. The main use case of this will be apparent in the dataOps section.

**invariants**
- is an instance of a processor (streaming lane)
- is an instance of an extractor (batch lane)
- Only pushes data to a designated place in the Data Lake

Yes spark in this case plays the role of a 'dumb loader' but its easy to deploy, plays nicely with kafka and clickhouse and has scaling and fault tolerance capabilities. 

### ClickHouse as distributed OLAP realtime store

ClickHouse is extremely well documented and the only distributed OLAP database I could find that has impressive performance metrics and seems to be suitable for time series data. It also provides fault tolerance and scaling capabilities. Because of performance implications we heavily restrict our users to only read permissions since the main purpose of ClickHouse is to provide the latest timestamps of the data collected from the realtime sources. Analysts primarily feed the data from ClickHouse via client libraries into their models and algorithms or dashboard solutions on the infrastructure dedicated to their team. On request we can create data marts for individual teams.

**Invariants**
- is an instance of a realtime store
- For each topic there is an ingestion table whose schema implements the category schema of the topic
- Provides materialized views for snapshots per source and symbol of latest category data  
- Users have only access to this database  
- Data is only stored for a few days  
- Users have **read-only** permissions in ClickHouse  
- For every processor that ingests into clickhouse there are designated ingestion tables and the processor has only write permissions on these tables


The ingestion tables will use merge tree engine, this means that every batch insert job into a category schema ingestion table, will be sorted once when clickhouse creates a 'table part' then in a background process all the table parts are continously merged together to keep the relation between table part sizes and overall number of table parts in the optimal range. The important part to understand is that we need to set a batch intervall in such a way that the machines which run clickhouse dont get overwhelmed with too many parts at once. We mitigate this by setting an accepatble batch intervall that together with the other latency factors of the pipeline still provides an overall acceptable latency budget. Then we estimate how much throughput our current sources might produce at most, this figure serves as an basis for the machines requirements that run clickhosue, they should be fast enough in sorting and merging table parts in the event of the pipeline achieving the maximum throughput to not let build up too many table parts. More in detail in the scaling and cost section.

The batch intervall can also optimize the nubmer of parts being merged at a given point in time, if the batches are monotonic (the smallest value in this batch is larger than the largest value of last batch) then clickhosue will only merge the last few table parts which optimizes ingestion. By setting the batch intervall such that out-of-order and late arrivals stay within the same batch, we can take advantage of this fact.

### discussion of streaming lane
We have two main requirements to satisfy for the streaming lane, access to realtime data from the source systems and results of heavy computations that cannot be done elsewhere. We have already proven that if the connector does not fail, data will land in kafka and by the invariants of a connector the data is fitting to a category schema. If the connector does fail then we loose data for a time intervall. Data that has landed in kafka, can be reprocessed an 'infinite' times during runtime of the pipeline. Thus if a processor fails, data is lost or corrupted during transmission to any destination, then after restarting the processor it can reprocess the data. Since the processor is idempotent the resulting state is consistent with that if the processor was successfull the first time. So the data lands in a consistent way in the realtime store clickhouse. By the properties of the realtime store of which clickhouse is an instance of, we conclude that the data not only lands in the realtime store but also is available ordered by the latest value of the designated timestamp to users via materialized views.

As mentionned the ordering garuantess within a source system are there to improve performance, this will be discussed in depth in the section about data models and category schemas. But the main idea is to partition by source_system, then sort and index by source_system, symbol and designated timestamp. The core idea is that queries of analysts usually involve few symbols and the designated timestamp so these queries should be very fast even if involving joins, as long as these joins are done via source system, symbol or designated timestamp since clickhouse can use merge join that runs in O(n+m) where n,m are the number of rows of the tables to be joined.  

For data different than market data (economic releases) we simply choose release_code or something equivalent to replace "symbol" as the designated identifier. This core idea will also be used for historical data stored in the historical store.

Whenever working with multiple machines and technologies and strong contracts and design, development time and latency will always suffer. Latency in particular could break the architecture because if users experience lags in the realtime store they will stop using it which is why I restrict them to read only permissions. We *need* to optimize latency by keeping network traffic within the datacenter. But also by using more performant languages like scala instead of python and keeping computations somewhat efficient. Of course there is always the option of using 'stronger' machines. To mitigate development time we produce a proof of concept first that is the streaming lane with just interactive brokers as source, clickhouse as destination and no spark heavy computations.

Finally, because we are storing the actual raw data in dedicated topics that relate to the category topics we can prove to stakeholders that the pipeline is healthy.

### Limitations of a Portfolio Project done by one single human

“Few days” here means actually “few hours” and “60 minutes” means “5 minutes” to keep costs of running this project at bay. But the overall goal is to run a cluster of total size between 6 and 15 machines for 2h to 4h Monday until Friday during U.S. cash market open where markets are most interesting.

## Batch lane

Recall that we will have kafka as one source system for the raw streaming data and clickhouse as another for the processed data. Spark is a processor (streaeming lane) and an extractor (batch lane).

### Serverless functions whenever possible with Azure Functions

Serverless functions are very cheap and easy to manage. The only case where using them might be impossible is when accessing data sources that are behind a firewall and we need to deploy behind that firewall (with GitLab or GitHub runners for example). Or if the functions are computationally expensive and require lots of hardware resources. But given my current understanding of the selected data sources, this is not the case.

These serverless functions will also perform the transformations from the lake to bronze and from bronze to silver since at the lake and bronze stage we might deal with many files or tables (you don’t want to use SQL with many tables).

**Invariants**
- Are instances of either extractor or connectors
- Transformed data satisfies the invariants of the destination layer (bronze, silver, gold, data marts) and adds the necessary metadata  
- Data going from data lake or bronze to silver is transformed by serverless Python functions  

### Microsoft Fabric as Data Lake

The data lake serves to decouple the sources from the remaining parts of the batch pipeline. By storing all here we have a single source of truth for troubleshooting, DataOps and testing.

We avoid a data swamp by using a container / directory structure to distinguish the data. This should be sufficient to identify the data. We will not add metadata at this stage since this would violate the “raw” data invariant.

**Invariants**

- All data from every source is stored here in raw unaltered format  
- Every source has its own container  
- If the data had a specific format at source, then it is preserved  
- If data did not have a specific format at source (APIs) then it is stored as JSON  

### Transformations, modelling and DataOps with DBT

For transforms starting at the silver layer, where we should have just a few tables, we use dbt.

**Invariants**
- is an instance of a transformer
- Transformed data satisfies the invariants of the destination layer (silver, gold, data marts) and adds the necessary metadata  
- Data going from silver to gold or data marts is only transformed in dbt  

### Azure Fabric for Data Warehousing

All the layers (bronze, silver, gold, data marts) are implemented in Synapse. The distinction between these layers will be made by naming conventions. Schemas allow us to do access and user management so we take schemas as the logical mappings to our layers. The neat thing about Fabric is that we can use Spark instead of SQL which might come in handy for our `business_use_case_evolution.md` since the analysts probably also want to perform these computations on historical data as well and not only on the streaming data.

**Invariants**

- There is exactly one schema (container of tables) for the bronze layer  
- There is exactly one schema for silver layer  
- There is exactly one schema for gold layer  
- For each data mart there is exactly one schema that holds all of its tables  
- For every data source there is a `source_category_dictionary` that lists the mappings from the source fields to the category fields  

The last invariant will greatly enhance ease of use and data discovery and will make it easier to understand and use the category schemas.

### Microsoft Purview as our Data Catalogue, Governance and lineage tool

**Invariants**

- Every table from bronze, silver, gold and all data marts is in the data catalogue and lineage tool  
- But users only see the data they have access to  

### Discussion of Batch pipeline
One key distinction between realtime and batch data, is that batch data usually is persisted on the source system. So on failure we can simply rerun the Batch pipeline since extractors, connectors and transformers are all idempotent. If a source system does not persist the complete batch data then the probability of loosing data is very low because extractors are extremely lightweight and thus will finish fast, resulting the data being stored in the Lake or already in the bronze layer. With backup mechanisms of the cloud environment loosing data due to failed extractors, connectors or transformers becomes very unlikely. This justifies deploying them as serverless functions on single instances. So the data arrvies in the historical store and is modelled according to the user's needs. Requirements that cannot be satisfied in the gold layer are always satisfiable with a dedicated data mart.

Latency and peformance of the extractors, connectors and transformers is neglible since data processed here needs to be updated only daily or hourly at most. Performance in the gold layer and the marts does however matter since analysts should not be forced to write queries that perform poorly to satisfy their needs. So the modelling aspect is crucial here, more so than on the streaming lane.


## Operations
### Access and User management

Fortunately for us this is a problem that has been already solved for us. We copy the same access management processes that the IT of the firm is using. Usually this boils down to:

- Client data can only be accessed by the client service team for that client  
- External market data and economic data can be accessed by analysts team  
- In general data from stakeholders can be accessed only by the team that works with that stakeholder  
- User access needs to be fully compliant with any data compliance framework that may apply  

If this is not enough then we need to employ row level security with specific business rules. For this first version of the project where our only users are analysts and we only store market data or economic data that is publicly available, we treat that data as “public” which means anybody can access it.

### Security

Security is achieved if our platform does exactly what we want and nothing more. We have touched on this with Azure Key Vault but this is not enough.

#### Firewalls

We need a firewall surrounding the Hetzner cluster and blocking inbound traffic in such a way that we can authenticate as the admin (with a very strong and secure password) to access the cluster. We are only going to expose ClickHouse to be accessible and every analyst that does have access will have a dedicated user.

We also build a firewall around our cloud infrastructure, to only allow access via dedicated ports if necessary or with Microsoft user accounts.

### Service accounts

Each component instance has clearly associated service accounts that specify rights according to the invariants.

#### User management ClickHouse

Since we teardown and restart ClickHouse we need to backup all users and their passwords (hashed) to our data lake. This allows us to define users and their rights once and teardown and restart ClickHouse as much as we want.

#### Airflow User management

Every data engineer that has the rights for tampering with the control plane will need a dedicated Airflow user. Due to the inconvenience of needing to define all users on startup of Airflow, we will solve this via the control plane directly and pull the secrets from Key Vault and create the Docker Compose files at runtime in test and production.

#### Data security

Access rights per user are either determined by IT processes or row level security business logic. We can maintain this with some kind of access matrix, where each role in the company in a particular team is associated with access definitions of various resources. But again at this stage since we only handle market data or public data and we only have analysts or engineers as users we will allow “everyone to access everything”. This however should be immediately changed once the platform gets traffic from real users.

**Invariants**

- Everything that belongs to the platform is surrounded by exactly one firewall that blocks “all” inbound traffic  
- Every entry point needs authentication via some kind of user  
- If we need to persist user secrets then we do so by hashing them  
- Users only have access to gold layer, their data marts, ClickHouse and nowhere else  
- Users have only read permissions for gold layer  
- Users have read and write permissions only in their data mart  
- Users have read-only permissions in ClickHouse   

In the real world we would also employ password rotation plans (probably every 6 months at least), 2FA and minimal password requirements to further enhance security. We would also need to periodically reassess the impact of compliance frameworks on our current governance policies. Also if the users were not analysts that are comfortable with SQL, then I would not give them write permissions anywhere, not even in their data mart because if they make a mess out of it, it is us that needs to clean after them.

### DevOps

We already covered a lot in the control plane but reiterate the most important things here.

**Invariants**

- Only services, code, config that passes the dev environment can proceed to test cluster and registry  
- Only services, code, config that passes the test environment can proceed to prod cluster and registry  
- There is exactly one repository with all files and code for the entire platform, the monorepo  
- Each service is decoupled from host by Docker containers  
- Test and prod environment have their dedicated “system db” (logging storage) and Azure Container Registry  
- Technology stack is fixed to the tech discussed here and Python, Scala, Bash, JSON, YAML and nothing else  
- If possible CI is done via GitHub, otherwise Azure CI  

Note: in a real setting we should also fix libraries and tools for Python and Scala.

### DataOps

Anytime data moves from one layer to the next, we store all data that did not qualify for the next layer in a separate table with equal name but suffix `_unqualified` with all fields from the source table. This allows us to investigate which rows did not qualify and will make it significantly easier to find out why.

Also for our current data sources (not kafka or clickhosue) it does not appear to make sense to store their “history”. If CFTC or EIA notice a mistake in their data and correct it, we will download the entire reports and simply rewrite the old and wrong values on the next pipeline run. This is very pragmatic and easy for small data sources where we simply can load and overwrite the whole thing. The analysts don’t care what the old, possibly wrong values were; they just want the (correct) data. This also makes the pipelines idempotent which is nice.

Special treatment is given to the raw streaming data and the processed streaming data. One nice observability solution or testing solution (for the test environment for example) is to run batch jobs on kafka to pull the raw data *and* the category data. Another batch job pulls data from clickhouse. Then we can analyze, during runtime of the streaming pipeline:

- How much data is lost between raw and processed ? 
- How much data is lost between processed in kafka and clickhouse?
- What is the latency between ib connector and clickhouse (via metadata fields)?
- What is the current throughput of the pipeline ?

The answer to the first two questions should be zero, by design of the architecture. So in theory we dont need to track that, however , depending on the current trust level of the user base we might still track it in production.

Surely there are other ways of answering these questions but by doing so we also backup the raw and processed data in the data lake during runtime!

**Invariants**

- Data that did not qualify for the next layer is stored in a dedicated table on the same layer it is currently in and with the suffix `_unqualified`  
- Every table in the silver, gold layer and data marts is a **logical** materialized view, i.e. fully recomputed on each load (using dbt / Fabric / Spark), implemented as a physical table with truncate+insert semantics  
- All unqualified rows are ingested into a DataOps dashboard to oversee data quality  
- All tables in all layers are completely overwritten on every load job (or at least all partitions that participate in that load job)  
- Silver, gold and data mart tables are enriched with the following metadata: `time_ingested` : timestamp with date, `source_table_name` : the fully qualified table name from which this data comes, `platform_worker_name` : the name of the serverless function or dbt job that produces this table  
- Any sight of “missing” in status-like fields (once they exist in concrete schemas) is monitored; if the usual threshold is breached an alert and email is created and sent  

In the real world it would be imperative to have at least one responsible person per source system that either contacts the source system maintainer to solve data quality issues or if the data is user generated then to fix the data in the source system.

### Failure Matrix

Which failures should we expect and how are they handled?

#### A pod in the streaming lane crashes

We can configure Kubernetes to automatically restart a node. Depending on the system logs, the failure might have occurred due to higher demand on the system in which case we need to scale vertically or horizontally, this needs to be implemented in the various components directly. For instance for the IB connector we have to scale vertically since we cannot run more than two pods (restriction from their “web” API). There is no realistic risk of data loss in case of Kafka, Spark or ClickHouse due to fault tolerance and idempotency. If an IB connector node fails then we lose data for the interval where the remaining symbols are assigned and subscribed to by the remaining pod, but this cannot be avoided. If it were a really important data source then we can set the lifecycle intervals very short (200 ms) or implement some kind of fault tolerance by streaming the same trading symbol from multiple pods. This would increase the storage needed on Kafka by the replication factor however ClickHouse deduplicates data by default so there it would not be a problem. The real issue would be with L2 data where there is no “event time” from Interactive Brokers so each machine that is a replica might have a different ingestion time so spotting duplicates in this case would be very difficult.

#### Teardown or setup of the streaming lane fails

If Airflow struggles for some reason to setup or teardown the streaming lane, then this most likely requires manual intervention. The Airflow tasks would fail in which case we send a log to the system db where we can configure an alert for these kinds of logs with an automatic email.

#### Kafka or clickhouse pods all failed
In this case we lost data and we cannot recover it (except via the source system). Our observability solution needs to monitor the lowest number of replicas left across all partitions or table shards. Then we can devise a backup policy like the following:

- if replica count drops to 2, trigger a batch job to pull all of the data into our data lake.
- As long as this condition persists, keep pulling the data every few seconds.

We must be aware that kafka is "more important" than clickhouse because if the latter fails but kafka is still running we did not loose data. But as soon kafka fails the data is also lost for all downstream components.

If the source system supports historical data and the lost streaming data is available there then we also can pull from the source system directly via a batch job. This is much more feasible for historical market data.

#### Hetzner cluster is not responding or unavailable

For this portfolio project: well, bad luck I guess.  
In the real world: emergency migration plan to redeploy on AKS or other alternative, it goes without saying that this emergency plan needs to be tested periodically (every 4 months or so).

#### Azure / Fabric is unresponsive or unavailable

We trust Microsoft that backups are reliable on the cloud and that the data will be available eventually. For really important data we can do periodic backups into a different zone with cheap cold long term storage which would be somewhat costly in the event of accessing that data. From that data we can either scale up ClickHouse to also hold the historical data or ingest that data into a different cloud solution (Google BigQuery). Since we still have the backup from cold storage, we can simply delete the data in BigQuery to avoid egress costs as soon as Fabric is available.

In the meantime we do not teardown ClickHouse but let it run to keep the historical data.

#### Batch serverless job or DBT job fails

Send a log to system db, set up alerts for this type with automatic email then manual intervention (debugging and pushing the fix to prod).

#### Data Quality deteriorates

Investigation via the DataOps dashboard and the `_unqualified` tables, taking action with the source system owners / responsibles.

#### Components in Streaming lane fail during runtime

If ClickHouse (very unlikely) failed:

- Data is still in Kafka so we pull all the data from Kafka prematurely into our data lake  
- Try to redeploy ClickHouse and fix the issue  

If Kafka (very unlikely) failed:

- Data is lost (unlikely with fault tolerance), we need to redeploy Kafka as soon as possible  
- Connectors might fail since due to the producer API failing, need to restart or redeploy them as well  
- Therefore we first try to backup the data in ClickHouse, then redeploy the entire pipeline  
- If last point fails, manual investigation is needed to find the root  

If a connector failed:

- Manual investigation needed  
- If it somehow failed because the data does not fit any category schema, then continue with adaptation matrix  

If Airflow failed:

- Should only depend on data from the logs / system db so it recovers by restarting the pod  
- All workflows need to depend only on system db that depicts current state of platform  

If Kubernetes (control plane) fails:

- Manual intervention  

#### Platform experiences lags and performance issues

- If unusual data throughput is the likely cause, we add nodes or we use more powerful nodes, we then need to re-evaluate scaling rules and assumptions in the control plane  
- Otherwise manual intervention is needed  

#### Users have problems accessing the platform resources

- This most likely requires manual investigation  

### Adaptation Matrix

What changes should we expect and how are they handled?

#### New data source needs to be handled for streaming lane

- Develop connector for that source that satisfies invariants  
- Push service, develop until it passed the test environment  
- If passing the test environment is impossible because category schemas are not wide enough proceed with “category schema needs to be modified”  

#### New data source needs to be handled for batch processing

- Push service, develop until it passed the test environment  
- Done, connectors do not transform at all and are not in contact with category schemas on the batch processing lane  

#### New destination needs to be handled for streaming lane

- Discussion with stakeholders about constraints and requirement  
- Development of processor (or reuse) and destination (or reuse) until test environment is passed  
- Destinations should not have impact on category schemas  
- But they might have on data models so we need to re-evaluate the data models  

#### Category schema needs to be modified

If it should shrink then we only shrink it after some countdown (6 months) to avoid unnecessary changes to the schema. After countdown if the field is still unused by all our data sources, we start a change period (6 months).

- During the change period downstream users need to update their schemas to not rely on the fields anymore. Here the streaming lane destinations and processors are included to make these changes.  
- After the users, we can update the gold layer that implements the data model to remove the fields.  
- After the gold layer we update the silver layer.  
- Finally we can simply remove the fields from the Airflow config file.  
- Airflow will force the streaming lane to comply with the new category schemas from now on by mounting the new version on the pods.  
- Because we updated the silver layer compliance is already ensured there.  

If it needs to grow we then define a canonical name for the fields and add them to the category schema defined in Airflow.

- On next deployment of the streaming lane the changes are then applied to the entire system dynamically (every service depends on the category schema which is generated by Airflow right before runtime, then mounted by Kubernetes as a file on the pods)  
- This change should be non breaking for the connectors on the streaming lane and the connectors on the batch lane  
- If data marts use `select *` on unions or processors do something similar this might be breaking, so we simply forbid anyone from using constructs like these  
- On the silver layer in Fabric, Airflow performs the changes to the schemas since automatically since it should be non breaking  

#### Source schema from batch processing evolves

If the schema expanded then we have the new fields in the lake and bronze layer automatically by design and thus in the data catalogue (discoverable).

- Data that goes from bronze to silver and only the old fields are mapped to the category schema so no problem here  
- Anything that builds from silver won’t experience problems since silver tables schemas correspond to the category schemas that remain unchanged  
- Problems might occur in the data marts if things like `select *` is used in unions, this is easy to solve by preventing users from using `select *` …  
- If users show interest in the new fields and they cannot map to existing category schemas in the silver layer, proceed with “Category schema needs to be modified”  

If the schema shrank then silver and gold won’t experience problems due to the category schemas remaining unchanged.

- We will however notice an increase of “missing” values  
- But downstream consumers (especially data marts) might experience problems if they relied on these values  
- If it was impossible to foresee this change then there is not much we can do  
- If it was possible to foresee this change then we need to adapt our processes  

#### Analyst leaves the company, new analyst joins company

- Delete or create the user on ClickHouse, Fabric (any destination the user was present)  
- Start process to evaluate access rights (usually it should be clear by role)  

#### Source schema from stream processing evolves

- Only the connectors might experience problems if their treatment of the schema was careless (`select *` in unions)  
- The connector maintainer needs to provide a quick fix if it broke due to an expanding schema (which should be easy to avoid). This might cause an expansion of category schemas which we covered above.  
- If the schema shrank then we need to immediately notify downstream users. If the connector already adapted, there should be nothing left for us to do since category schemas remain stable.  

### Incidence matrix
Here we cover the security incidents we might expect and how to handle them. There are main entry points an attacker might consider:

- Data Lake
- Clickhouse
- Airflow UI
- Hetzner UI
- Cloud UI

Everything else blocks inbound connections (firewall). These four entrypoints can be made more secure by 

- using non-standard usernames
- using strong passwords
- The need for that username and password to get through
- in reality we would rotate passwords, here we do not



### Hardware estimation, scaling rules, cost estimation and Alternatives

comming soon.

### Final remarks

It is clear that we should avoid the shrinking of the category schemas since that event poses the greatest risk and the most cost to complete. Growing a category schema is unpleasant but manageable. So one naive solution would be to make the category schemas as wide as possible, which could give us problems at scale, however if we manage to design category schemas that have at most 30 columns and are few in numbers (maybe 6 at most) but almost never change because they cover most of fields out there, then this is a very good solution.

Also a good observability solution should cover our adaptation and failure matrix.

Finally for the current state of the project where we are only handling market data on the streaming lane, schema evolution is very unlikely (the understanding of a “tick” or “order book” has been quite consistent over the last decade).

# Data Models and Category Schemas

We have postponed the elephant in the room long enough and it goes without saying that the category schemas are what can make this architecture really good or really bad. One fundamental design decision is that downstream consumers will only get **stateless** data at the ingestion / event level. This means that if the streaming source is stateful, then the connector’s job is to convert incoming data into a stateless version to fit to the category schemas. Stateful derived views (for example “latest book”) are built later inside the realtime store.

## Category schemas

In the real world you really should define and maintain a glossar (glossary) of possible values for the fields and their meaning. The glossar together with the category schemas will greatly improve data discovery in the warehouse.

In the following all timestamps conform to ISO 8601:2004 and are in UTC+0 timezone. Any field containing `_unit` writes the unit in the format: `amount_name`, examples `1000_barrels`, `1_USdollar`, `5_contracts`.

Since it is a commodities trading firm, they will be primarily interested in futures and options. Should they be also interested in assets or other derivatives, then we add category schemas in the same way and with as many common fields as possible.

These category schemas are used as conceptual contracts for topics in Kafka and ingestion tables for the silver layer and realtime store (ClickHouse). Physical schemas may add technical ingestion fields (for example `ingestion_time_realtime_store`, `ingestion_time_historical_store`) on top of the conceptual schema.

### Category schema `derivatives_tick_market_data`

```dbml
Category schema "derivatives_tick_market_data" {
  source_system_name       NOT NULL
  component_instance_name  NOT NULL
  ingestion_time           NOT NULL
  exchange_code            NOT NULL
  symbol_code              NOT NULL   // designated identifier
  symbol_type              NOT NULL   // futures, options, other derivatives
  price                    NOT NULL
  price_unit               NOT NULL
  size                     NOT NULL
  size_unit                NOT NULL
  tick_time                NOT NULL   // designated timestamp
  open_interest
  open_interest_unit
}

Category schema "derivatives_l2_market_data" {
  source_system_name       NOT NULL
  component_instance_name  NOT NULL
  ingestion_time           NOT NULL
  exchange_code
  symbol_code              NOT NULL   // designated identifier
  symbol_type
  price                    NOT NULL
  price_unit               NOT NULL
  size                     NOT NULL
  size_unit                NOT NULL
  side                     NOT NULL   // ask or bid
  level                    NOT NULL   // 1 to n where n is the maximum streamed level
  max_level                NOT NULL   // this is n at the time of streaming
  l2_time                  NOT NULL   // designated timestamp
  open_interest
  open_interest_unit
}
```

Sometimes we might be streaming indicators or computed values from the source which would be expensive to compute ourselves or is precisely the reason why we use that source. In that case these computations which we view as indicators are stored in a separate category schema, this keeps row duplication minimal, since a source row with multiple indicators for a symbol only duplicates these category rows and not the others. If we want to join downstream in ClickHouse, this should not be problematic if we ensure that the ingestion tables are ordered by (source_system_name, symbol_identifier, designated_timestamp) where the designated timestamp might be tick_time or l2_time or release_time, then ClickHouse can use merge join which completes the join in O(n+m) where n,m are the sizes of the joined tables.

We explicitly model the scope of an indicator (whether it is attached to a tick, an L2 event or a macro release) via an indicator_scope field. To keep the DBML and physical implementation simple, we do not enforce physical foreign keys from indicators to event tables; the relationship is documented and enforced at the query / modelling level.

```dbml
Category schema "indicators_market_data" {
  source_system_name        NOT NULL
  component_instance_name   NOT NULL
  ingestion_time            NOT NULL
  market_event_time         NOT NULL   // tick_time or l2_time or release_time, designated timestamp
  symbol_code               NOT NULL   // designated identifier
  indicator_scope           NOT NULL   // 'tick','l2','release'
  indicator_name            NOT NULL   // e.g. bollinger_bands_30_2
  indicator_value           NOT NULL
  indicator_value_unit      NOT NULL   // e.g. 1_contract, 1_percent, ...
}

Category schema "numeric_economic_data" {
  source_system_name        NOT NULL
  component_instance_name   NOT NULL
  ingestion_time            NOT NULL
  publisher_code            NOT NULL      // publisher is the institution who publishes the data
  release_code              NOT NULL      // CFTC-COT-report, EIA-weekly-report, etc. designated identifier
  release_time              NOT NULL      // the time when the report was released, designated timestamp
  release_field             NOT NULL      // which field of the release, 'Crude-Oil Stocks Forecast'
  release_field_value       NOT NULL
  release_field_value_unit  NOT NULL
}


```

Note that numeric_economic_data does not have a symbol_code – it is not directly tied to an instrument universe. Linking macro releases to instruments (for example “EIA crude stocks vs HO futures”) happens in the gold layer or data marts, where we define which instruments are logically associated with which releases and time windows.

These category schemas are, in my opinion, well designed: they are few in number, not too wide but still cover every possible field I could think of without losing information. Note that fields that remain more or less static during streaming time like the underlying of a derivative or its expiry date are handled in separate tables in the logical data model. The reason why we treat the units differently is because source systems might use different units (for example Interactive Brokers uses “260k” for open interest). This way we preserve information since it is explicit how to decode the values, otherwise the connector would need to transform the values (again, Interactive Brokers open interest would show 260000 which is misleading). For timestamps, if the source system does not provide it, then the connector makes an educated guess on the actual tick_time or l2_time, preserving at least the order of arrival at the source system.

### Discussion
If we don’t split the indicators into their own category then these schemas won’t scale well with increasing data throughput. The join downstream should not be a huge issue as long as we keep the ingestion tables for derivatives_tick_market_data and derivatives_l2_market_data sorted by (source_system_name, symbol_identifier, designated_timestamp).

### Data models
see realtime_store_data_model_logical.dbml, 
realtime_store_data_model_physical.dbml, 
warehouse_data_model_logical.dbml, 
warehouse_data_model_physical.dbml
you can load them to dbdiagram.io 


