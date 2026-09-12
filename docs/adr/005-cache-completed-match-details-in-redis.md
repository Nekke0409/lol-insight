# ADR-005: Cache Completed Match Details in Redis

Status: Accepted

## Context

The single Match Detail endpoint and the recent-Matches endpoint both call
`RiotMatchClient.findMatchById`. The recent-Matches flow can call several Details concurrently, and
the same completed Match can be requested again later. Repeating these Match-V5 Detail calls adds
Riot API usage and avoidable latency.

The existing bounded concurrency of four limits only active Detail requests. It does not share
completed results, does not cap requests over time, and does not prevent a cache miss from receiving
a Riot 429 response.

## Decision

Use Spring Cache backed by Redis only for successful `Match` results returned by
`RiotMatchClient.findMatchById`.

- Cache name and Redis key form: `match:detail:{matchId}`
- TTL: seven days
- Value format: JSON, using the Spring Boot `ObjectMapper` and a typed `Match` serializer
- Scope: only Match-V5 single-Match Detail; cache null values and all failures are excluded

The cache is configured at the infrastructure boundary. Application services continue to call the
Riot Match client and do not access Redis directly. The same cache therefore applies to both the
single-Match endpoint and each task in the existing recent-Matches bounded-concurrency fan-out.

## Result

```text
matchId
  -> Redis match:detail:{matchId}
      -> hit: return Match
      -> miss: Riot Match-V5 Detail -> map to Match -> write Redis -> return Match
```

## Reason

- Completed Match Detail data does not normally change, so it is a good cache candidate.
- The client method is already the common boundary for both request flows.
- Spring Cache and Spring Data Redis provide the required behavior without a new cache port or
  repository abstraction.
- A seven-day TTL keeps the MVP policy simple and gives stale entries a bounded lifetime.

## Alternatives Considered

### Local in-memory cache

It avoids a Redis dependency but does not share entries across application instances and loses data
when an instance restarts.

### Persisting Match Details in the application database

It would require a Match storage model, migrations, retention decisions, and a separate data
ownership policy that are outside this cache-only MVP.

### No cache

It preserves the current behavior but leaves repeated Match Detail requests to Riot unchanged.

## Consequences

### Positive

- Cache hits avoid Match-V5 Detail HTTP requests and reduce repeated lookup latency.
- Existing REST contracts, partial-response behavior, and bounded-concurrency behavior remain
  unchanged.
- JSON serialization avoids Java native serialization and does not require domain models to be
  `Serializable`.

### Negative / Trade-offs

- Redis must be available for cache access in environments where this feature is enabled.
- The first request for an uncached key still makes a Riot request and can receive 429, 5xx, or a
  transport failure.
- This decision deliberately does not add a distributed rate limiter, process-wide cooldown, retry,
  exponential backoff, negative cache, Match ID list cache, account cache, or database persistence.

## Follow-up

- [ ] Record separate cold-cache and warm-cache benchmark results when a stable local Riot test
  window is available.
- [ ] Evaluate rate-limit controls independently from cache effectiveness when observed traffic
  requires them.
