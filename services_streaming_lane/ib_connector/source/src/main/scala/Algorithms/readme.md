# The Problem of Symbol Sharding

When distributing *symbols* (units of data or work) over a set of *entities* (servers, pods, workers), we want to achieve two goals:

1. **Perfect load balance:**  
   At any point in time, symbols should be distributed as evenly as possible.  
   If the number of symbols `m` is not divisible by the number of entities `n`, then the optimal difference between the entity with the most symbols and the one with the fewest is at most **1**.

2. **Minimal data loss:**  
   When an entity goes offline, only the symbols hosted by that entity should be redistributed. No other symbols should move.  
   If an entity comes online, the same principle applies: ideally, symbols should remain stable unless strictly necessary.

These goals are central in distributed systems. One of the main motivations for distributed architectures is scalability—adding or removing machines dynamically. However, balancing and stability are **in tension** with each other.

---

## Key Insight 1 – Upscaling and Minimal Data Loss Conflict

When an entity goes **offline**, only its symbols become stale, so minimal data loss is achievable.

When an entity goes **online**, no symbols are stale.  
But to restore perfect load balance, some symbols must move — which **violates** minimal data loss.

Thus, load balancing and minimal disruption are inherently conflicting objectives.

---

## Key Insight 2 – The n! Combinatorial Explosion

Let `n` be the number of entities.

There are **n! different sequences** in which the entities can fail:

- First, any of the `n` entities may go offline.
- Then any of the remaining `n−1`.
- Then any of the remaining `n−2`, and so on.

This creates a full permutation tree of size:

\[
n \times (n-1) \times (n-2) \times \dots \times 1 = n!
\]

Each path corresponds to a unique offline sequence.

If we want a scoring-based method to allocate symbols **perfectly** for *all* these cases, the number of distinct states needed is also `n!`.

This leads to an important conclusion:

> To guarantee perfect balancing for all offline sequences, a score table must encode at least **n! rows**.

And therefore, the number of symbols `m` must be divisible by `n!` to keep all states perfectly balanced.

---

## Consistent Hashing

Consistent Hashing guarantees minimal data loss:  
only the symbols on the failed node move.

But it is **very unbalanced**, because all stale symbols move to a single successor.  
Load distribution becomes skewed.

---

## Rendezvous (Highest Random Weight) Hashing

Rendezvous hashing improves load balancing:

- For each `(symbol, entity)` pair, compute a **score** using a hash function.
- A symbol is assigned to the entity with the highest score.
- If one entity goes offline, each symbol moves to the entity with the next highest score.

This preserves minimal data loss and reduces skew—**in theory**.

However, in practice:

- For small `n` or small `m`, the distribution becomes uneven.
- Experiments in `PerformanceComparison` show significant imbalance.

A refinement is to create a **score table** so that scores are not purely random but crafted to achieve better balancing.  
This is implemented in `OptimalDistributionFunction.scala`.

Testing for m=n! shows that it works for any offline sequence in such cases and for m=n!+1 or m=n!-1 the
maximum difference between the total symbols between two entities does not exceed 1.
However if m has a large distance to n! than this error rate (maximal differnce in symbols) grows very quickly.

Thus this approach hits a hard limit:

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

- **Perfect load balance:** `maxDiff ≤ 1`  
- **Predictable performance**
- **No huge score tables**
- **Fast initialization**

This makes it far more suitable than “optimal” score-table hashing for realistic cluster sizes.

## Performance comparisons

Any approach that uses a precomputed table will have more initialization cost but lower runtime cost than those approaches that do not initialize but perform computations at runtime. But as said, for best possible balancing, a precomputed table that achieves it would have at least n! rows and thus would take O(n!) time and space to build. At runtime, when an entity fails one still needs to lookup the score for the stale symbols which in worst case can create O(m) lookups and redistributions (you still need to move the symbols they do not move by themselves).

Round Robin on the other hand does not have any initialization cost in form of time or space. At runtime it does not perform a lookup but instead makes at most O(m) distributions to the remaining entities. So even at runtime the Round robin approach performes as good as the precomputed table approaches.

---
