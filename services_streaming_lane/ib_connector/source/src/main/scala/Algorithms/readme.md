# The Problem of symbol sharding

Anytime we want to distribute Data units (or really anything, which we call symbols) on some set of servers or entities in general, we want to achieve two things. On the one hand, at any point in time, we want the that the symbols are as evenly as possible distributed across the entities. If the number of symbols is not divisible by the current number of entities, then some entities will have at least one symbol more than the others, thus the difference between the entity with most and the entity with least symbols, under best balancing, should be no more than one.

But we also want that if an entity goes offline, then we only want to redistribute the symbols that are stale / lost on the entity that went offline. We call the first propery 'perfect loadbalance' and the second 'minimal data loss'. We call the event of at least one entity going offline, downscaling, and if an entity goes online, upscaling. The Problem is at heart of distributed systems since one of the main motivations of using a distributed system is scaling, using more machines to do more work. 

