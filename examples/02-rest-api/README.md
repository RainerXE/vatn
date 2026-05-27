# 02 — REST API (Task Manager)

A full CRUD REST API backed by SQLite via `VPersistenceService`. Equivalent to a Node.js Express + SQLite task-manager app.

## What it does

- `POST   /tasks`        — create a task `{ "title": "...", "done": false }`
- `GET    /tasks`        — list all tasks
- `GET    /tasks/{id}`   — get one task
- `PUT    /tasks/{id}`   — update (mark done)
- `DELETE /tasks/{id}`   — delete

## Run it

```bash
mvn package -q
java -jar target/rest-api.jar
# Create a task
curl -X POST http://localhost:8080/tasks \
     -H "Content-Type: application/json" \
     -d '{"title":"Write docs"}'
# List tasks
curl http://localhost:8080/tasks
```

## Key concepts

- `VPersistenceService` — JDBC connection to the node's database
- `VSchemaContributor` — DDL that runs on first boot
- `VHttpRoutes` — CRUD route registration
- `VHttpRequest.getPathParam()` — URL path parameters
- `VHttpResponse.sendJson()` / `.status(404).send()`
