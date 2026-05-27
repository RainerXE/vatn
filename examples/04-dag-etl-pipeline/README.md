# 04 — DAG ETL Pipeline

An Extract-Transform-Load pipeline modeled as a VATN DAG workflow. Equivalent to an Airflow or Prefect pipeline — but running in-process with virtual threads.

## Pipeline structure

```
extract ──► validate ──► transform ──► load ──► notify
                                           └──► cleanup (ALWAYS)
```

- **extract** — simulates reading from an external API, pushes record count via XCom
- **validate** — reads count from XCom, fails fast if zero records
- **transform** — applies business logic, pushes transformed count
- **load** — writes to SQLite, reports rows inserted
- **notify** — sends a summary message to `etl.complete` messaging topic
- **cleanup** — always runs (even on failure) to release locks

## Run it

```bash
mvn package -q
java -jar target/etl-pipeline.jar
# Trigger a run via HTTP
curl -X POST http://localhost:8080/etl/trigger \
     -H "Content-Type: application/json" \
     -d '{"source":"orders","date":"2026-05-24"}'
# Poll run status
curl http://localhost:8080/etl/runs
```

## Key concepts

- `VDag`, `VDagTask`, `VOperator` — DAG definition and operator registration
- `VTaskContext.getXCom()` — pass data between tasks
- `VTriggerRule.ALWAYS` — cleanup task runs even after upstream failure
- `VRetryPolicy` — automatic retry with backoff
- `VDagEngine.trigger()` — programmatic DAG run
- `VDagRegistry` — look up the engine via `context.getService(VDagRegistry.class)`
