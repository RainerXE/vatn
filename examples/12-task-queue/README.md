# Demo 01 — Task Queue: Bull.js → VATN

This demo shows how to migrate a Node.js job queue built on
[Bull](https://github.com/OptimalBits/bull) to the VATN DAG engine.
The example domain is an e-commerce **order processing pipeline**:
validate the order, charge the customer, update inventory, send a
confirmation e-mail, and notify the shipping service.

---

## 1. What Bull.js does

Bull is the most popular Redis-backed job queue for Node.js.
You create named queues, add jobs to them, and register worker functions
that process those jobs.  A minimal order-processing flow looks like this:

```javascript
// bull-order-pipeline.js  (Bull v4)
import Queue from 'bull';

const validateQueue   = new Queue('validate-order',   { redis: { port: 6379 } });
const chargeQueue     = new Queue('charge-payment',   { redis: { port: 6379 } });
const inventoryQueue  = new Queue('update-inventory', { redis: { port: 6379 } });
const confirmQueue    = new Queue('send-confirmation',{ redis: { port: 6379 } });
const shippingQueue   = new Queue('notify-shipping',  { redis: { port: 6379 } });

// Worker: validate
validateQueue.process(async (job) => {
  const { order } = job.data;
  if (!order.items.length || order.totalAmount <= 0) {
    throw new Error('Invalid order');
  }
  return { validatedOrder: order, customerEmail: order.customerEmail };
});

// Worker: charge (3 retries)
chargeQueue.process(async (job) => {
  const chargeId = 'ch_' + job.id.toString().substring(0, 8);
  // ... call payment gateway ...
  return { chargeId };
});

// Worker: inventory
inventoryQueue.process(async (job) => {
  const { chargeId } = job.data;
  // ... update warehouse DB ...
  return { inventoryUpdated: true };
});

// Worker: confirmation
confirmQueue.process(async (job) => {
  const { customerEmail } = job.data;
  // ... send e-mail ...
});

// Worker: shipping
shippingQueue.process(async (job) => {
  const { inventoryUpdated } = job.data;
  // ... call logistics API ...
  return { trackingNumber: 'TRK-' + job.id };
});

// Glue: chain queues manually by listening to completed events
validateQueue.on('completed', async (job, result) => {
  await chargeQueue.add({ ...result });
});
chargeQueue.on('completed', async (job, result) => {
  // fan-out: both queues run in parallel
  await inventoryQueue.add({ chargeId: result.chargeId });
  await confirmQueue.add({ customerEmail: job.data.validatedOrder.customerEmail });
});
inventoryQueue.on('completed', async (job, result) => {
  await shippingQueue.add({ inventoryUpdated: result.inventoryUpdated });
});

// Submit an order
await validateQueue.add({ order: {
  orderId: 'ORD-001',
  customerId: 'CUST-42',
  customerEmail: 'alice@example.com',
  items: [{ sku: 'WIDGET-A', quantity: 2, price: 19.99 }],
  totalAmount: 39.98,
  currency: 'USD'
}}, { attempts: 3, backoff: { type: 'exponential', delay: 2000 } });
```

This works, but notice the friction:

- **Redis required** — you must run and maintain a Redis instance.
- **Manual fan-out wiring** — the `completed` event handlers are glue code
  that must be kept in sync with the actual pipeline shape.
- **No visual topology** — there is no built-in way to see the pipeline as a
  graph or to know which step failed mid-run.
- **No crash recovery** — if the Node process dies while a job is running,
  you must implement your own stalled-job detection and replay logic.
- **State lives in Redis** — debugging requires querying Redis directly or
  running the Bull Arena UI.

---

## 2. Concept mapping

| Bull concept | VATN equivalent |
|---|---|
| `Queue` | `VDag` (the workflow blueprint) |
| `queue.add(data, opts)` | `VDagEngine.trigger(dagId, conf, true)` |
| `queue.process(fn)` | `VOperator.execute(ctx)` |
| `job.data` | `ctx.getConf()` (run-level) + `ctx.getXCom()` (cross-task) |
| `job.returnvalue` / passing data between jobs | `VXCom.push/pull` |
| `attempts` + `backoff` options | `VRetryPolicy` on `VDagTask` |
| Manual `completed` event chaining | `VDagTask.upstream` set — declarative |
| `queue.on('completed')` fan-out | Parallel tasks sharing the same upstream task |
| Redis persistence | VATN's embedded SQLite event log — zero external deps |
| Stalled-job recovery | `VDagEngine.resumeInterruptedRuns()` |
| Bull Arena / Bull Board | Built-in REST API (`GET /orders/{runId}`) |

---

## 3. The VATN implementation

### Pipeline shape

```
validate-order
      │
charge-payment        ← VRetryPolicy(3, 2000ms, 2x, 30s)
      │
   ┌──┴──┐
update-inv  send-confirm   ← these two run in parallel
   │
notify-ship
```

The shape is declared once, in `OrderPipelinePlugin.registerDag()`, by
setting the `upstream` set on each `VDagTask`.  There is no event-handler
glue — the engine resolves execution order from the graph at runtime.

### Plugin entry point (`OrderPipelinePlugin.java`)

```java
@Override
public void onInitialize(VNodeContext ctx) {
    engine   = ctx.getService(VDagEngine.class).orElseThrow();
    registry = ctx.getService(VDagRegistry.class).orElseThrow();

    registerOperators(registry);
    registerDag(registry);

    // Resume any in-flight runs from before a crash or restart.
    engine.resumeInterruptedRuns();

    ctx.register("/orders", new OrderApiService(engine));
}
```

### DAG definition

```java
VRetryPolicy paymentRetry = new VRetryPolicy(3, 2_000, 2.0, 30_000);

Map<String, VDagTask> tasks = new LinkedHashMap<>();
tasks.put("validate-order",
    VDagTask.of("validate-order", "order.validate", Set.of(), Map.of()));

tasks.put("charge-payment",
    new VDagTask("charge-payment", "order.charge-payment",
        Set.of("validate-order"),
        VTriggerRule.ALL_SUCCESS, paymentRetry,
        VPool.DEFAULT_POOL, 0L, 0, false, 0L, null, Map.of()));

tasks.put("update-inventory",
    VDagTask.of("update-inventory", "order.update-inventory",
        Set.of("charge-payment"), Map.of()));

tasks.put("send-confirmation",
    VDagTask.of("send-confirmation", "order.send-confirmation",
        Set.of("charge-payment"), Map.of()));   // same upstream → parallel

tasks.put("notify-shipping",
    VDagTask.of("notify-shipping", "order.notify-shipping",
        Set.of("update-inventory"), Map.of()));

registry.register(VDag.manual("order-pipeline",
    "E-commerce order pipeline", tasks));
```

### XCom data flow

Operators exchange data via `VXCom` without knowing about each other's
implementation:

```
validate-order  →  push("validated_order", orderJson)
                   push("customer_email", email)

charge-payment  →  pull("validate-order", "validated_order")
                   push("charge_id", "ch_abc12345")

update-inventory → pull("charge-payment", "charge_id")
                   push("inventory_updated", "true")

send-confirmation → pull("validate-order", "customer_email")

notify-shipping  → pull("update-inventory", "inventory_updated")
```

### Operator example: `ChargePaymentOperator`

```java
public class ChargePaymentOperator implements VOperator {

    @Override
    public String operatorType() { return "order.charge-payment"; }

    @Override
    public String execute(VTaskContext ctx) throws Exception {
        ctx.log("Charging payment (attempt %d)", ctx.getTryNumber());

        String orderJson = ctx.getXCom()
            .pull("validate-order", "validated_order")
            .orElseThrow();
        Order order = MAPPER.readValue(orderJson, Order.class);

        Thread.sleep(300);  // simulate gateway latency

        String chargeId = "ch_" + ctx.getRunId().substring(0, 8);
        ctx.getXCom().push(ctx.getTaskId(), "charge_id", chargeId);
        return chargeId;
    }
}
```

If this operator throws, the engine waits 2 s and retries (up to 3 times),
then marks the task FAILED and the entire run FAILED.  No `Bull.process(fn,
{ attempts: 3 })` needed — the retry policy lives on the task definition.

---

## 4. Running the demo

### Prerequisites

```bash
# Install VATN to local Maven repo
cd /path/to/vatn
mvn install -DskipTests
```

### Build

```bash
cd 01-task-queue
mvn package
```

### Start

```bash
java -jar target/01-task-queue-1.0-SNAPSHOT.jar
```

The node starts on port 8080.  You should see:

```
[main] INFO  dev.vatn.core.VNodeRunner - VATN Node Ready on port 8080.
```

### Submit an order

```bash
curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "orderId": "ORD-001",
    "customerId": "CUST-42",
    "customerEmail": "alice@example.com",
    "items": [
      { "sku": "WIDGET-A", "quantity": 2, "price": 19.99 }
    ],
    "totalAmount": 39.98,
    "currency": "USD"
  }'
```

Response:

```json
{"runId":"3f8a1b2c-...", "status":"QUEUED"}
```

### Poll run status

```bash
curl -s http://localhost:8080/orders/3f8a1b2c-... | jq .
```

```json
{
  "runId": "3f8a1b2c-...",
  "state": "SUCCESS",
  "logicalDate": "2026-05-27T09:00:00Z",
  "startDate": "2026-05-27T09:00:00.012Z",
  "endDate": "2026-05-27T09:00:01.247Z",
  "tasks": [
    { "taskId": "validate-order",   "state": "SUCCESS", "tryNumber": 1 },
    { "taskId": "charge-payment",   "state": "SUCCESS", "tryNumber": 1 },
    { "taskId": "update-inventory", "state": "SUCCESS", "tryNumber": 1 },
    { "taskId": "send-confirmation","state": "SUCCESS", "tryNumber": 1 },
    { "taskId": "notify-shipping",  "state": "SUCCESS", "tryNumber": 1 }
  ]
}
```

### List recent runs

```bash
curl -s http://localhost:8080/orders | jq .
```

### Cancel a run

```bash
curl -s -X DELETE http://localhost:8080/orders/3f8a1b2c-...
```

---

## 5. What you gain over Bull

| Concern | Bull | VATN |
|---|---|---|
| External dependency | Redis required | None — embedded SQLite |
| Crash recovery | Stalled-job detection (separate config) | `resumeInterruptedRuns()` — one line |
| Parallel fan-out | Manual `completed` event handlers | Declarative upstream sets |
| Retry policy | Per-`queue.add` option | Per-`VDagTask`, versioned with the DAG |
| Pipeline visibility | Bull Arena (separate service) | Built-in REST API |
| Task dependency graph | Implicit in glue code | Explicit in the DAG definition |
| Audit log | Redis TTL — ephemeral | Persistent event log — queryable |
| Language | JavaScript / TypeScript | Java 25 — AOT-ready, GraalVM native |
| Deployment | Node.js + Redis | Single fat JAR |
