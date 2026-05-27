# 06 — Scheduled Report

A cron-triggered DAG that runs every night at 02:00, queries the database, and writes a markdown report. Equivalent to a `node-cron` job backed by a Bull queue — but expressed as a typed DAG.

## Schedule

```
cron: "0 2 * * *"   (02:00 UTC daily)
```

## Pipeline

```
query_db ──► aggregate ──► render_markdown ──► write_file ──► notify
```

- **query_db** — SELECT summary from SQLite, push JSON rows via XCom
- **aggregate** — compute totals/averages, push summary object
- **render_markdown** — template the markdown report
- **write_file** — write to `workspace/reports/YYYY-MM-DD.md`
- **notify** — publish path to `reports.ready` topic

## Run it

```bash
mvn package -q
java -jar target/scheduled-report.jar
# Trigger manually (no need to wait for 02:00)
curl -X POST http://localhost:8080/report/trigger
# List past runs
curl http://localhost:8080/report/runs
```

## Key concepts

- `VDag.scheduled(id, description, cron, tasks)` — cron DAG factory
- `VDagScheduler` — evaluates cron expressions and fires runs
- `VTaskContext.getNodeContext().getWorkspacePath()` — resolve file paths safely
- `VMessaging.publish(topic, payload)` — notify downstream consumers
