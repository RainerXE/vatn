# 01 — Hello World

The smallest possible VATN plugin. One HTTP endpoint, zero boilerplate.

## What it does

- Registers a `GET /hello` endpoint that returns `"Hello from VATN!"`
- Logs startup and shutdown lifecycle events

## Run it

```bash
mvn package -q
java -jar target/hello-world.jar
curl http://localhost:8080/hello
```

## Key concepts

- `VNodePlugin` — the plugin SPI every VATN app implements
- `VNodeContext.register(path, service)` — mount an HTTP service
- `VHttpRoutes` — fluent, transport-neutral route builder
- `VNodeRunner.create(port).addPlugin(...).start()` — one-line bootstrap
