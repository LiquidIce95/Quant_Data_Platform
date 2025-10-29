# ib-connector-legacy

**IMPORTANT:** This is a first draft and should not be treated as production-grade code.  
If you want to use this code in production, read through the official Interactive Brokers TWS API documentation and be aware that test coverage is not complete or extensive in this version, and that it has never been deployed to production by the author.

This module implements the **Interactive Brokers (IB) connector** using the **legacy TWS / IB Gateway socket API**.  
It preserves a fully working end-to-end connector for reference and comparison with the upcoming **Client Portal Web API** rewrite.

You can copy and paste the code in `classDiagram.mmd` into a Mermaid editor to interact with a visual representation of this connector.

---

## Class diagram explanation

The class diagram illustrates the structure and interactions between the core components:

- **EClientSocket, EReader, EJavaSignal** — provided by IB’s official Java API.  
  These form the I/O backbone:
  - `EClientSocket` sends requests to TWS / IB Gateway.
  - `EReader` reads messages from the socket and triggers callbacks.
  - `EJavaSignal` handles wakeups for `EReader`.

- **EWrapperImplementation** — our concrete callback handler.  
  It implements the IB (default) `EWrapper` interface and processes all incoming data:
  - Manages contract discovery.
  - Handles tick-by-tick (L1) and order book (L2) updates.
  - Maintains `BookState` per symbol to reconstruct the full book from IB’s INSERT / UPDATE / DELETE events.
  - Publishes transformed JSON events to Kafka through `KafkaProducerApi`.
  - Communicates State changes to `Connections` (e.g., invalidation).

Note that it's impossible to decouple contract discovery from the EWrapper because only the EWrapper receives the results of callback function calls (the requested contracts). Otherwise, this would have belonged to the `ConnManager`’s responsibilities.

- **BookState** — a fast in-memory order book representation.  
  It tracks prices and sizes per `(side, level)` and enforces monotonicity and no-holes constraints.  
  It supports the three IB L2 operations: `insert`, `update`, and `delete`, returning the changed rows to be emitted downstream (Kafka).

- **ConnManager** — supervises lifecycle and manages streams.  
  Runs on its own thread, independent from the callback thread (`EReader`).  
  It:
  - Can use a sharding algorithm to compute sharding (per pod) and decide which symbols to stream.  
    If no sharding algorithm is provided, all symbols are handled by this pod or app instance.
  - Cancels feeds for invalid legs.
  - Starts or restarts valid legs through `ClientIo`.
  - Updates State and Status in `Connections` according to the connection management algorithm.

- **ClientIo** — serializes all socket operations.  
  It wraps `EClientSocket` calls inside a single-threaded queue to ensure thread safety and deterministic order.

- **Connections** — a guarded singleton managing shared state.  
  It stores:
  - Discovery mapping (`reqId ↔ code`)
  - Connection **State** (`VALID` or `INVALID`)
  - Connection **Status** (`ON` or `DROPPED`)  
  The manager and wrapper are registered as authorized actors, and only they can mutate allowed transitions. This ensures the invariants assumed by the connection management algorithm.

- **KafkaProducerApi** — thin wrapper around a Kafka producer.  
  It publishes both tick-by-tick and L2 JSON events to the Kafka message broker.

- **Transforms** — dependency-free JSON helper functions used to serialize tick and L2 data.

Together, these components implement a **safe, concurrent, and deterministic data pipeline** between IB Gateway and Kafka.

---

## Summary of the architecture & key decisions

### What the socket API gives you out of the box
- **`EClientSocket`** — imperative client for requests (subscribe, cancel, etc.).
- **`EReader` + `EJavaSignal`** — the message pump that pulls events off the socket and calls back into…
- **`EWrapper`** — a *callback interface* where **all market data** (L1/L2) and **errors** arrive asynchronously.

### Why we had to track **order book state (L2)**
- The legacy socket API **does not** stream a full book on every tick. It also does not stream the current states of a side and level (current price and size for that side and level).  
  Instead, `updateMktDepthL2` provides **operations**:
  - **INSERT / UPDATE / DELETE** at `(side, position)`, with price/size.
  - Downstream consumers just want to know `(timestamp, side, level, price, size)` — during an interval where no such events occur, the level is assumed constant, just like a trading application’s order book display.
- To output coherent rows (e.g., to Kafka / ClickHouse), we had to **apply those ops** to a local structure:
  - `BookState` enforces invariants (monotonicity, no holes) and returns the list of **changed rows** to emit.
- This creates the fundamental problem that if such an operation is lost (which IB admits may happen), the resulting order book may no longer make sense.  
  If that happens, we must cancel the feed and restart it, since at the beginning of the feed IB sends all rows to start from a clean order book.

### Why we decoupled **ConnManager** from the **callback**
- If `EWrapperImplementation` tried to (re)start feeds directly, we could **block the callback thread** (which must stay hot to avoid back pressure).
- We therefore route lifecycle control through **`ConnManager`**, which runs on its own thread and uses **`ClientIo`** to schedule **FIFO, single-threaded** calls to `EClientSocket`.  
  - Result: **thread-safe** socket usage; **no reentrancy** from callbacks; predictable ordering of IB calls.

### Shared state between **ConnManager** and **EWrapperImplementation**
- However, decoupling `ConnManager` from the callback forces us to share Connection **States** and **Status** between both processes.  
  And everyone with experience in parallel computing knows that as soon as resources are shared between concurrent processes, really weird things can happen — extensive testing and careful design are required. 
- `Connections` (a guarded singleton) stores, per **trading symbol** and **leg (TBT/L2)** — by convention, the first component of the tuple is TickByTick and the second is L2:
  - **State:** `VALID` or `INVALID` — who may flip it is **restricted** (manager sets from `INVALID` → `VALID`, wrapper sets from `VALID` → `INVALID`, and no one else can do that).
  - **Status:** `ON` or `DROPPED` — controlled only by the **manager**.
- This separation lets the **manager** supervise lifecycle while the **wrapper** autonomously invalidates legs when it detects faulty connections (for example, an order book where invariants are broken).

### FIFO queue for `EClientSocket` calls
- **`ClientIo`** is a **single-thread executor** with a **`LinkedBlockingQueue`** of `Runnable`.
- All socket invocations are **serialized** (one thread) in call order.  
  This avoids data races and “socket already busy” errors without coarse locks.

---

## State-management algorithm (four-case proof sketch)

We simply say “connection” for each leg’s connection (TBT or L2) per symbol. 

**Allowed transitions**
- **Manager:** `INVALID → VALID` (idempotent if already `VALID`).
- **Wrapper:** `VALID → INVALID` (idempotent if already `INVALID`).
- **Manager:** sets **Status** (`ON` / `DROPPED`) at any time.  
Other transitions are ignored.

Connections are initialized as `(INVALID, DROPPED)` and the wrapper starts with an empty order book. 

Anytime the wrapper sets the connection’s State from `VALID` to `INVALID`, it first resets the order book to an empty state.  
This ensures that it’s ready to receive the initializing sequence of events for the order book on a clean book.

Anytime the wrapper receives data (callback called) but Status is `DROPPED`, it ignores the data, resets the order book, and sets the State to `INVALID` if it wasn’t already `INVALID`.

Anytime the wrapper receives data from an `INVALID` connection, it ignores it (the `ConnManager` has not yet restarted the feed). Since the wrapper is the only one to set `INVALID`, we know the book is empty (reset or at initialization).

Anytime the wrapper receives data from a `VALID` and `ON` connection, it processes the data as planned.

Now we take the `ConnManager`’s point of view and apply these assumptions about the wrapper and the States and Status of connections.

The `ConnManager` runs periodically in polling cycles of fixed time intervals (say 600 ms) in parallel with the wrapper.

At each polling time *t* the connection manager does the following:

1. For every connection that is **INVALID**, cancel that connection.  
2. Recompute pod sharding *S*.  
3. If a symbol is in *S*: set both connection legs for that symbol to `ON`;  
   If a symbol is not in *S*: set both connection legs for that symbol to `DROPPED`.

### Four cases

Case 1:  
Connection is `INVALID` and `DROPPED`. We have canceled the connection and the wrapper has reset the order book (either at initialization or after it was previously `VALID`). Nothing more to do — the symbol is being handled by another pod, according to the sharding algorithm’s output.

Case 2:  
Connection is `INVALID` and `ON`. By the same reasoning, the wrapper has reset the order book, and we (the connection manager) have already canceled the connection. We set `INVALID` → `VALID` and restart the feed. The wrapper has an empty order book and will start receiving the initializing sequence of events to create a valid book.

Case 3:  
Connection is `VALID` and `DROPPED`. We do nothing. We wait for the wrapper to detect that the Status is `DROPPED`. When it does, it resets the order book and sets the State to `INVALID`. At the next polling time, we cancel the connection. Since the Status is `DROPPED`, the wrapper won’t process incoming data.

Case 4:  
Connection is `VALID` and `ON`. We do nothing.

Thus, for every State or Status change, at most one polling interval later:
- The wrapper processes data from connections that are `ON` (i.e., this pod is responsible for them).
- The order book is reset before receiving the initializing sequence for the order book.
- If a connection is set to `INVALID`, the order book is reset and the manager restarts the feed.  
- This approach allows **Dynamic Symbol Sharding at Runtime (DSSR)** using Kubernetes autoscaling by adding or removing nodes.

Because we accept the “at most one polling interval later” condition, dirty reads are not a problem — especially since we store State values in immutable `val` objects.  

Dirty writes are not possible because we use mutual exclusion locks (mutex) on the singleton `Connections` write member functions (see Scala reference / code).  
By design, only one process can perform a State change depending on whether it’s `VALID` or `INVALID` (Status is managed by the connection manager only).

**Unimplemented edge case:** silent TCP death — we planned to track timestamps of the last callbacks and automatically invalidate when the interval exceeds a threshold.

**How does the sharding algorithm work?** — will be covered in the `ib_connector_modern`.

---

## Testing and limitations

- `TestConnManager.scala` is **not exhaustive**:
  - Missing edge cases (e.g., reconnection races, K8s sharding changes).
  - No watchdog tests for silent TCP failure.
- **Headless IB Gateway deployment** is missing.  
  Running IB Gateway or TWS on Kubernetes requires additional tooling (`IBController`, `Xvfb`, session keep-alive scripts).

---

## Historical value

This codebase remains a **reference baseline** for:
- Understanding the constraints of the legacy socket API.
- Demonstrating a safe architecture for asynchronous market data with DSSR.

---

## Why is it halted / not completed?

The **Client Portal Web API** is far superior for our use case since it does not require tracking of order books.  
The L2 data is already in the favorable `(timestamp, side, level, price, size)` format, which means we do not need to share resources (**States** and **Status**) between the processing thread (`EWrapper`, callback function) and the connection manager.  
This enables trivial parallelism and achieves the same functionality with much less code.

---

## Why did you not use the superior Client Portal Web API from the start?

In 2018, when I was gambling on financial markets, I wanted to automate some “ideas” using the TWS API (the Client Portal Web API did not exist back then).  
I never succeeded because I lacked the technical knowledge, but it stayed on my to-do list.

---

## But isn’t this just a waste of time?

Yes, it is — but it was satisfying and a great learning experience at the same time.
