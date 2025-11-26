# The Problem of Symbol Sharding

When distributing *symbols* (units of data or work) over a set of *entities* (servers, pods, workers), we want to achieve two goals:

1. **Perfect load balance:**  
   At any point in time, symbols should be distributed as evenly as possible.  
   If the number of symbols `m` is not divisible by the number of entities `n`, then the optimal difference between the entity with the most symbols and the one with the fewest is at most **1**.

2. **Minimal data loss:**  
   When an entity goes offline, only the symbols hosted by that entity should be redistributed. No other symbols should move.  
   If an entity comes online, the same principle applies: ideally, symbols should remain stable unless strictly necessary.

These goals are central in distributed systems. One of the main motivations for distributed architectures is scalability—adding or removing machines dynamically.

---

## Key Insight 1 – Upscaling and Minimal Data Loss Conflict

When an entity goes **offline** (downscaling), only its symbols become stale, so minimal data loss is achievable by only moving those symbols. 
If we were to move other symbols then we make them unnecessarily stale and create more data loss.

When an entity goes **online** (upscaling), no symbols are stale.  
But to restore perfect load balance, some symbols must move — which **violates** minimal data loss (to only move those that are stale).

Thus, load balancing and minimal disruption are inherently conflicting objectives.

---

## Key Insight 2 – The n! Combinatorial Explosion

Let `n` be the number of entities.

There are **n! different sequences** in which the entities can fail:

- First, at the beginning any of the `n` entities may go offline.
- Then any of the remaining `n−1` can go offline.
- Then any of the remaining `n−2`, and so on.

This creates a full permutation tree of size:

\[
n \times (n-1) \times (n-2) \times \dots \times 1 = n!
\]

Each path corresponds to a unique offline sequence.

So if we want to use a method that can handle all cases then it needs to consider at least n! cases.


---

## Consistent Hashing

Consistent Hashing guarantees minimal data loss:  
only the symbols on the failed node move.

But it is **very unbalanced**, because all stale symbols move to a single successor.  
Load distribution becomes skewed.

---

## Rendezvous (Highest Random Weight) Hashing

Rendezvous hashing also satisfies minimal data loss but improves load balancing in downscaling events:

- For each `(symbol, entity)` pair, compute a **score** using a hash function.
- A symbol is assigned to the entity with the highest score.
- If one entity goes offline, each symbol moves to the entity with the next highest score.

This preserves minimal data loss and reduces skew—**in theory**.

Notice that if e1 and then e2 go offline and we had perfect load balance, then it is preserved if e2 goes online and then
e1 goes online, but not necessarily if e1 goes online first.

However, in practice:

- For small `n` or small `m`, the distribution becomes uneven.
- Experiments in `PerformanceComparison` show significant imbalance.

A refinement is to create a **score table** so that scores are not purely random but crafted to achieve better balancing.  
This is implemented in `OptimalDistributionFunction.scala`.

Testing for m=n! shows that it works for any offline sequence in such cases and for m=n!+1 or m=n!-1 the
maximum difference between the total symbols between two entities does not exceed 1.
However if m has a large distance to n! than this error rate (maximal difference in symbols) grows very quickly.

Even though it performs better on n up to 5, this approach hits a hard limit:

> To reflect every possible offline ordering, the score table needs `n!` rows.

Thus, for `n ≥ 5`, this becomes infeasible in time and memory.

Even if you could build such a table for arbitrary m, initializing it would be extremely slow since it would have at least n! entries.



---

## Practical Solution – Round Robin

For small to medium `n` and `m`, the best approach is **simple Round Robin**:

- Ignore attempts to encode all offline sequences in a score table.
- Assign symbols cyclically.
- When the set of online entities changes, rebalance by redistributing symbols evenly.

Round Robin guarantees:

- **Perfect load balance:** `maxDiff ≤ 1 even on upscaling`  
- **Predictable performance**
- **No huge score tables**
- **Fast initialization**

This makes it far more suitable than “optimal” score-table hashing for realistic cluster sizes.
## Performance Characteristics

Every sharding strategy has two relevant costs:

1. **Initialization cost** — time and memory required before the system starts processing.  
2. **Runtime redistribution cost** — work needed when entities go online/offline.

### Precomputed Score-Table Approaches

Methods based on a precomputed score table (such as `OptimalDistributionFunction`) have the following profile:

- **Initialization cost:**  
  Extremely high for larger `n`.  
  To encode all possible offline sequences, the table must contain at least `n!` rows.  
  Building such a table takes **O(n!)** time and memory, which becomes infeasible already for `n ≥ 5`.

- **Runtime cost:**  
  When an entity goes offline, each stale symbol requires a score lookup.  
  In the worst case, up to **O(m)** redistributions are needed.  
  (Symbols do not “move themselves”—you still iterate through all stale symbols.)

Thus, precomputed approaches trade **massive initialization cost** for **moderate runtime cost**, but are only viable for very small cluster sizes.

---

### Round Robin

Round Robin has a radically different profile:

- **Initialization cost:**  
  *Near zero*.  
  Just assign the symbols cyclically once — **O(m)** time, no large auxiliary structures.

- **Runtime cost:**  
  No score lookups.  
  When the set of online entities changes, we simply:
  - collect all symbols from currently online entities  
  - redistribute them evenly using a cyclic pass  

  This takes at most **O(m)** operations.

Crucially:

> Round Robin guarantees perfect load balance (`maxDiff ≤ 1`) after every redistribution, with no need for `n!` precomputation.

It retains predictable and stable behavior even for medium-sized clusters where score-table methods become completely impractical.

---

### Summary

| Method                      | Initialization Cost | Runtime Cost     | Load Balance | Feasible for Medium n |
|-----------------------------|----------------------|------------------|--------------|------------------------|
| Score-Table (Optimal)       | **O(n!)**            | O(m)             | Very high (when m divisible by n!) | ❌ No |
| Hash-based Rendezvous       | O(1)                 | O(m) for stale symbols | Medium / depends on m, n | ✔ Yes |
| **Round Robin**             | **O(m)**             | **O(m)**         | **Perfect (`≤1`)** | ✔✔ Best |

Round Robin avoids the factorial blow-up entirely and consistently produces near-optimal distributions, making it the most practical and robust strategy in realistic distributed systems.


---
