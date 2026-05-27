# 05 — Real-time Dashboard

A live server-metrics dashboard streamed to the browser over SSE. A background virtual thread publishes CPU/memory snapshots every second to a `VMessaging` topic; the SSE endpoint bridges them to connected browsers.

## What it does

- `GET /dashboard/stream` — SSE endpoint; each event is a JSON metrics snapshot
- `GET /dashboard`        — serves a minimal HTML page with a live-updating chart
- `POST /dashboard/reset` — resets the metric counters

Open `http://localhost:8080/dashboard` and watch numbers update in real time.

## Run it

```bash
mvn package -q
java -jar target/realtime-dashboard.jar
open http://localhost:8080/dashboard   # macOS
```

## Key concepts

- `VHttpRoutes.sse(path, handler)` — SSE endpoint registration
- `VSseSink.emit(name, data, id)` — push events to connected clients
- `VMessaging.subscribe(topic, callback)` — receive metric snapshots
- `VMessaging.unsubscribe(topic, callback)` — clean up on client disconnect
- Background virtual thread loop (`Thread.ofVirtual().start(...)`)
