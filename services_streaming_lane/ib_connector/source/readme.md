# IB Connector Architecture

## 1. Overview

The IB connector is a distributed real-time market data ingestor for the Interactive Brokers Client Portal Web API.  
The main idea is to run and manage the ib client portal per pod and with a dedicated paper trading account whose credentials are stored in azure key vault. The use of dedicated paper trading credentials per pod is an unfortunate but hard constraint from the web api. Further to use the web api one must run and keep alive a console app and regurarly authenticate on it. We aimed to minimize these inconveniences as much as possible to be able to use that 'api' as a regular api.

Next, we want to be scalable (under the constraint of having enough paper trading accounts) by distributing all trading symbols that we want to stream as evenly as possible across all pods at any given point in time and dynamically respond to more pods joining the ib-connector kubernetes namespace or pods failing.

Also the symbols should land on the same partition in kafka to ensure the data is ordered and thus improve the ingestion performance of clickhouse down the line. The symbols should obviously be also evenly distributed across the partitions.

On top of that we want to run in parallel the reading of the streaming data , keeping the portal alive, and managing the symbol responsibilities across the pods to minimize latency.

So each pod does the following:

1. Start and keep the Client Portal gateway healthy.
2. Establish and maintain a WebSocket connection to `/v1/api/ws`.
3. Shard the futures symbol universe across pods.
4. Subscribe/unsubscribe to tick-by-tick (SMD) and DOM (SBD) streams for its shard.
5. Normalize SMD frames into ticks and publish them to Kafka with stable partitioning.


The composition root is `object IbConnector`.

---

## 2. Major Components Overview

The components are designed in such a way that they maximize cohesion and minimize coupling. Another neat thing is that its highly adaptable since most of the components can be swapped out as long as fundamental functionality is preserved. This leads also to easy testability of the single compoennts but also their interactions.

The ApiHandler simplifies calling the endpoints from the ib we api we are interested in. But it also starts the portal and keeps its session authenticated, in case of problems it tries to restart the portal. It also defines the symbol universe that is all the futures contracts we want to stream across the cluster and its the only place where this is defined (DRY). This ApiHandler needs to be initialized first since most other components depend on it see ExampleUse.scala. Note that it depends on a particular paper trading account to use which is why computeUser needs to be implemented (if you store the credentials with a number at the end in azure key vault then you may simply enumerate the pods in the namespace and the order corresponds to the order in the key vault, the pod names are constant in kubernetes)

The StreamManager creates the websocket, manages access to it via locks, manages its lifecycle via the incoming heartbeats and provides an interface to implement where one can decie how the lifecycle of particular connections need to be handled (for instance with a sharding algorithm that distribues the futures acros the pods and thus on upscaling or downsclaing events some connections need to be unsubscribed or subscribed) and functions that are called by the reader when da dataframe is received from the websocket. 

Notice that for stateful streaminng (which the order book data might be) the processor could send unsubscribe and subscribe messages directly to the websocket since ib claims it to be threadsafe however be aware that it may take multiple lifecycles to reach a conclusive state since the connection manager could unsubscribe a symbol but since the processor received a frame that results in a inconsistent state it could accidently resubscribe to the symbol even though it shouldnt.

But right now this is not the case since we stream only tick data that is stateless and the processor is the only entity reading from the socket and the connection manager the only entity sending messages to the websocket.

The SmdProcessor processes messages from the smd topic which are tick data for all subscribed symbols.

The SbdProcessor processer messages from the sbd topic which are  order book / l2 data , due to time constraints and the fact that ibs documentation is not extensive ennough to properly understand the data schemantics, this is not finished.

The Connection Manager subscribes or unsubscribes symbols form particular topics it provides an interface for a symbolsharding algorithm. It does depend on the websocket however it does not create it but receives it as function arguments (remember StreamManager is the entity with authority over the socket and manages access to it).

Finally the KubernetesApiHandler simplifies the kubernets api for our use case and decouples it from the other components.

We could have created a component called 'Reader' that is called on any frame in 'startReader' in StreamManager to fully decouple api details from the StreamManager.


### 2.1 `ApiHandler` (trait)

**Responsibility**

- Thin client for IB Client Portal REST and WebSocket API.
- Lifecycle management of the Client Portal process.
- Session cookie acquisition and WebSocket establishment.
- Symbol universe discovery.

**Key behaviour**

- HTTP endpoints are pre-bound in `endpointsMap : Map[EndPoints, Request[String]]`.
- `computeSymbolUniverse()`:
  - Calls `/trsrv/futures?symbols=CL` and `/trsrv/futures?symbols=NG`.
  - Builds `Vector[(Long,String,String)]` = `(conId, expirationDate, symbol)`.
  - Keeps only the front `numberOfFrontMonths` per root symbol.
- Portal lifecycle:
  - `startIbPortal()` starts the `clientportal.gw` process via Bash and waits for the “Open https://localhost:5000 to login” marker.
  - `authenticate(userId: Int)` runs the Python authenticator script.
  - `isHealthy()` = “portal logs look OK” ∧ “auth status endpoint reports authenticated” ∧ “portal process still running”.
  - `startPortalLifeCycleManagement()`:
    - If healthy: re-authenticate to keep the session alive.
    - Else: start portal and authenticate.
  - `startApi()`:
    - Schedules periodic lifecycle management (via `CommonUtils.scheduleAtFixedRate`).
    - Waits for portal to become healthy or throws.

- WebSocket:
  - `fetchAndStoreCookie()` calls `/tickle` to obtain `session` token.
  - `establishWebSocket()` connects to `webSocketUrl` with `Cookie: api={"session":"..."}`
    and returns a `SyncWebSocket`.

- **Abstract**: `def computeUser(): Int`
  - Implemented by `IbConnector` using Kubernetes pod ordering (see below).

---

### 2.2 `KubernetesApiHandler` (object)

**Responsibility**

- Cluster introspection for sharding and user selection.

**Key behaviour**

- Reads namespace from `/var/run/secrets/kubernetes.io/serviceaccount/namespace`.
- Uses `KubernetesClient` in-cluster config to list pods in the current namespace.
- `getPeers(): Vector[Pod]` returns all pods sorted by name.
- `getHealthyPeers()` filters peers with `status.phase == "Running"`.
- `getOfflinePeers()` filters peers with `status.phase == "Failed"`.
- `getPodName(pod: Pod): String` returns `metadata.name`.
- `getThisPodName()` and `getThisNameSpace()` read env `POD_NAME` and `POD_NAMESPACE`.

**Usage**

- `IbConnector` uses peers and their ordering to:
  - Pick `userId` for IB login (`computeUser()`).
  - Define sharding keys for `RoundRobin`.

---

### 2.3 `RoundRobin[E, S]`

**Responsibility**

- Stateful online/offline-aware sharding algorithm.

**State**

- `entityNames: Vector[E]` – all logical entities (here: pod names).
- `symbolNames: Vector[S]` – all symbols ((conId, expiry, symbol)).
- `stateMap: Map[E, Status]` – online/offline status.
- `entityMap: Map[E, Vector[S]]` – current assignment.

**Behaviour**

- `initEntityMap()`:
  - Initializes `entityMap` with empty vectors.
  - Distributes `symbolNames` evenly across `entityNames` in strict round-robin.

- `onEntityLost(entity, onlineEntities)`:
  - Takes all symbols from `entity`.
  - Sorts remaining online entities by current load (ascending).
  - Redistributes lost symbols round-robin over the least loaded.

- `onEntityWon(entity)`:
  - Given a newly online entity:
    - Sorts current online entities by load (descending).
    - Computes `atLeast = floor(totalSymbols / (onlineCount + 1))`.
    - Iteratively steals the last symbol from the heaviest entities and moves them
      to the new entity until it holds `atLeast` symbols.

- `apply(onlineEntities)`:
  - Lazily calls `initEntityMap()` once.
  - For each entity:
    - If status `Online` but not in `onlineEntities` → `onEntityLost` and mark `Offline`.
    - If status `Offline` but in `onlineEntities` → `onEntityWon` and mark `Online`.
  - Returns current `entityMap: Map[E, Vector[S]]`.

**In this system**

- `E = String` (pod name).
- `S = (Long,String,String)` (conId, expiry, symbol).
- Used inside `ConnectionManager` via `IbConnector`.

---

### 2.4 `ConnectionManager` (abstract)

**Responsibility**

- Compute symbol shards per pod and enforce subscriptions on the WebSocket.

**State**

- `api: ApiHandler`
- `symbolShards: Map[String, Vector[(Long,String,String)]]`
- `symbolUniverse: Vector[(Long,String,String)] = api.computeSymbolUniverse()`
- `podIdentity: String = determinePodIdentity()`

**Behaviour**

- `computeShards(): Map[String,Vector[Symbol]]` (abstract):
  - In `IbConnector`:
    - Gets healthy peers via `KubernetesApiHandler.getHealthyPeers()`.
    - Maps to peer names.
    - Calls `roundRobin(onlinePeerNames)` and returns the resulting shard map.

- `determinePodIdentity(): String` (abstract):
  - Implemented by `IbConnector` as `KubernetesApiHandler.getThisPodName()`.

- `apply(ws: SyncWebSocket)`:
  - Updates `symbolShards = computeShards()`.
  - Finds this pod’s shard `myShardConIds`.
  - Computes `universeConIds` from `symbolUniverse`.
  - Unsubscribes SMD (`unsubscribetbt`) for all `conId ∉ myShard`.
  - Subscribes SMD (`subscribetbt`) for all `conId ∈ myShard`.

**Note**: The L2 subscription methods are present but currently commented out in `ConnectionManager.apply`.

---

### 2.5 `StreamManager` (abstract)

**Responsibility**

- WebSocket lifecycle, heartbeat handling, and frame dispatch.

**State**

- `api: ApiHandler`
- `webSocket: SyncWebSocket = api.establishWebSocket()`
- `heartBeatTimestamp: Long`
- Locks for `webSocket` and `heartBeatTimestamp`.
- Execution context `ec = CommonUtils.ec`.

**Abstract hooks**

- `connectionsLifeCycleManagement()`:
  - In `IbConnector`: calls `CM(ws)` to re-apply shard subscriptions.

- `onSmdFrame(message)`:
  - In `IbConnector`: delegates to `smdProcessor`.

- `onSbdFrame(message)`:
  - Currently a no-op in `IbConnector`.

**Behaviour**

- `socketLifeCycleManagement(timeIntervall: Long)`:
  - If `now – heartBeatTimestamp > timeIntervall`:
    - Closes current WebSocket.
    - Re-establishes `webSocket = api.establishWebSocket()`.
    - Resets `heartBeatTimestamp`.

- `startReader()`:
  - Blocking loop on `webSocket.receive()`:
    - Binary frame → parse JSON:
      - If topic `smd+…` → `onSmdFrame(parsed)`.
      - If topic `sbd+…` → `onSbdFrame(parsed)`.
      - If topic `system+…` with `hb` field → update `heartBeatTimestamp`.
      - Else log.
    - Close frame → mark heartbeat as `0` to trigger reconnection.
    - Ping/Pong → log.

- `startStream()`:
  - Schedules periodic task (every `schedulingIntervall`):
    - `socketLifeCycleManagement(heartBeatTolerance)`.
    - `connectionsLifeCycleManagement()`.
    - If `readerFuture` is completed, restart `startReader()` in a new `Future`.
  - This creates a self-healing loop for the WebSocket and subscriptions.

---

### 2.6 `SmdProcessor`

**Responsibility**

- Transform raw SMD frames into normalized tick messages and publish to Kafka.

**State**

- `producerApi: KafkaProducerApi`
- `api: ApiHandler`
- `symbolUniverse` from `api.computeSymbolUniverse()`.
- `conidToCode: Map[Long,String]`:
  - Built using `CommonUtils.buildMonthYearCode(expiry)` and symbol root (e.g., `CL`, `NG`).
- `TickState` per `conId`:
  - `tradingSymbol`, `price`, `size`, `openInterest`, `eventTime`, `marketDataType`.
- `stateByConId: Map[Long,TickState]`.
- `topic = "ticklast"`.

**Behaviour**

- `apply(message)`:
  - Extracts `conid` from numeric or string JSON field.
  - Fetches/creates `TickState` for this `conId`.
  - Updates fields based on IB field IDs:
    - `"31"` → price
    - `"55"` → tradingSymbol (mapped via `conidToCode`)
    - `"6509"` → marketDataType
    - `"7059"` → size
    - `"7697"` → openInterest
    - `"_updated"` → eventTime
  - When all fields are defined:
    - Builds a JSON object:
      - `symbol`, `conid`, `price`, `size`, `open_interest`, `event_time`, `market_data_type`.
    - Serializes and sends to Kafka via `producerApi.send("ticklast", conId, json)`.

---

### 2.7 `KafkaProducerApi`

**Responsibility**

- Kafka producer abstraction with deterministic partitioning by `conId`.

**State**

- Kafka producer configured from env:
  - `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_CLIENT_ID`, `KAFKA_LINGER_MS`, `KAFKA_BATCH_SIZE`.
- `symbolUniverse` via `api.computeSymbolUniverse()`.
- `partitionIndices: Map[Long,Int]`:
  - Maps each `conId` to its index in `symbolUniverse`.
- `partitionSizes: Map[String,Int]`:
  - `ticklast` and `l2-data` partition counts.

**Behaviour**

- `send(topic, key, value)`:
  - Uses `partition = partitionIndices(key) % partitionSizes(topic)`.
  - Ensures all ticks for given `conId` go to the same partition.

- `flush()` / `close()` – simple wrappers on the underlying producer.

---

### 2.8 `IbConnector` (object)

**Responsibility**

- Composition root and bootstrap logic.

**Key steps in `startConnector(retries: Int)`**

1. Guard: if `retries > 5` → throw.
2. In a `Try` block:
   - Build `api: ApiHandler` with concrete `computeUser()`:
     - `getPeers()` from Kubernetes.
     - Resolve peer names via `getPodName`.
     - Compute `userNumber = indexOf(thisPodName) + 1`.
   - `api.startApi()` to make Client Portal healthy.
   - Create `KafkaProducerApi(api)`.
   - Create `SmdProcessor(producer, api)`.
   - Get `peers`, `symbolUniverse`, and `peerNames` (sorted).
   - Instantiate `roundRobin = new RoundRobin[String,(Long,String,String)](peerNames, symbolUniverse)`.

   - `object CM extends ConnectionManager(api)`:
     - `computeShards()`:
       - `onlinePeers = KubernetesApiHandler.getHealthyPeers()`.
       - `onlinePeerNames` from `getPodName`.
       - `roundRobin(onlinePeerNames)`.
     - `determinePodIdentity()` returns `getThisPodName()`.

   - `object SM extends StreamManager(api)`:
     - `connectionsLifeCycleManagement()`:
       - Calls `CM(ws)` for current `webSocket`.
     - `onSmdFrame` delegates to `smdProcessor`.
     - `onSbdFrame` is a no-op.

   - `SM.startStream()` to begin WebSocket / subscription / reader lifecycle.

3. On `Success`:
   - Log “the connector started successfully”.
4. On `Failure(e)`:
   - Log error.
   - Recursively call `startConnector(retries + 1)`.

**`main(args)`**

- Simply calls `startConnector(0)`.

---

## 3. Control Flow Summary

1. **Process bootstrap**
   - `IbConnector.main` → `startConnector(0)`.
   - `ApiHandler` implementation is created and starts the Client Portal (`startApi()`).

2. **Symbol discovery**
   - `ApiHandler.computeSymbolUniverse()` queries futures metadata for `CL` and `NG`.

3. **Sharding**
   - Pods discover all peers via `KubernetesApiHandler`.
   - `RoundRobin` is initialized with all peer names and the global symbol universe.
   - On each lifecycle tick, `ConnectionManager.computeShards()` is called with the currently **healthy** peers, and `RoundRobin` updates assignment accordingly.

4. **WebSocket lifecycle**
   - `StreamManager.startStream()`:
     - Periodically:
       - Checks heartbeat and reconnects WebSocket if necessary.
       - Re-applies shard subscriptions via `ConnectionManager.apply(ws)`.
       - Ensures the reader loop is running.

5. **Data path**
   - WebSocket SMD frames → `StreamManager.startReader()` → `SmdProcessor.apply()`.
   - `SmdProcessor` accumulates partial field updates; emits complete ticks as JSON.
   - `KafkaProducerApi.send()` writes ticks to Kafka topic `ticklast` with ordered partitions per `conId`.

6. **Failure handling**
   - Portal failures:
     - Detected via logs, auth endpoint, and process future.
     - Recovered by `startPortalLifeCycleManagement()` and `startApi()`.
   - WebSocket failures:
     - Detected via stale heartbeats or close frames.
     - Recovered via `socketLifeCycleManagement()` and `establishWebSocket()`.
   - Fatal failures in `startConnector`:
     - Trigger up to 5 retries before giving up.

---



## 4. Things to be aware of 
1. **port 5000**
    the web api portal runs on port 5000 as such anytime startIbPortal is called, it kills *all* processes that listen on port 5000.
2. **SbdProcessor not finished**
    Because the docs on the book trader data seem too superficial I decided to skip book data altogether since it would take too much time via trial and error to figure out exactly how the data is send (stateless or statefull)
3. **Number of Pods needs to conincide with number of paper trading accounts**
    As mentionned in the general readme of the ib connector module, it seems not to be possible to authenticate via custom created users and TOTP for paper trading, only the dedicated paper trading credentials work and since all of our attempts failed to run the portal as a seperate pod we concluded that there is no other way around. Even if we were able to run one portal for all pods it would present a significant bottleneck in the distributed setup and thus is not desirable in the first place.
4. **authenticator python scripts**
    These scripts are not mentionned explicitly but are required for the portal to function automatically.
5. **Azure Key Vault access**
    For development in the devcontainer, run 'az login' to enable the python scripts and the ApiHandler to access the necessary secrets. On Kind there is a command in dev.sh that runs the command such that you can perform the authentication for the pods in your kind cluster. In produciton one must set up a manged identity.
6. **Certs and Truststore**
    You Need two files that for security reasons are gitignored: ibkr_client_portal.pem and ibkr_truststore.jks to create them you first must run the ib web api client portal and then use keytool to generate these files, they must be in the same folder as this readme file.
7. **Hostname verification needs to be disabled**
    Since Ib has a self signed fixed cert for the web portal and it does not include localhost we need to turn off hostname verification as currently done in build.sbt...  