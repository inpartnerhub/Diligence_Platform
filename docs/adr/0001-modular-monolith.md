# ADR-0001: Modular monolith for the backend

- **Status:** Accepted
- **Date:** 2026-10-30
- **Deciders:** Platform architecture

## Context

The platform covers several capabilities (documents, ingestion, search, audit, security) that will evolve at
different speeds. The team is small enough that operating many services would cost more than it returns, but
boundaries must stay clean so capabilities can be extracted later if scale or team structure demands it.

## Decision

Build the backend as a single Spring Boot deployable organised into **Spring Modulith application modules**:

- Each top-level package under `com.example.platform` is a module; `internal` sub-packages are private.
- Boundaries and allowed dependencies are verified in the build (`ModularityTests`).
- Modules interact through public APIs and domain events persisted in a transactional outbox
  (`event_publication`); integration events are externalized to RabbitMQ.
- Each module owns its own PostgreSQL schema; no cross-module foreign keys.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Microservices from day one | Independent deploy/scale | Distributed-systems overhead, more infra, premature boundaries |
| Unstructured monolith | Fastest start | Boundaries erode; extraction later becomes a rewrite |
| Gradle multi-project per module | Compile-time isolation | More build boilerplate; Modulith verification gives equivalent guarantees with less ceremony |

## Consequences

- One artifact to build, deploy, observe and secure.
- Extraction path: a module already communicates via events and owns its schema, so it can become a service by
  moving its package, schema and queue bindings.
- Scaling is per deployable; CPU-heavy ingestion can later run as a separate deployment of the same image with only
  the ingestion listeners enabled, before any code split.
