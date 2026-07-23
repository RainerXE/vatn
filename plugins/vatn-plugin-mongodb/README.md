# vatn-plugin-mongodb
![Stability](https://img.shields.io/badge/stability-preview-yellow)

Provides a managed MongoDB connection as a VATN service.

## How it works

Initialises a MongoDB sync driver `MongoClient` at plugin startup using the supplied connection string, and closes it cleanly on node shutdown. `MongoService` is a thin wrapper that gives direct access to the driver's `MongoDatabase` and `MongoCollection` types, so the full MongoDB Java driver API is available without any additional abstraction layer. The driver version bundled is ~5.x (see parent POM for the exact version property `mongodb.version`).

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-mongodb</artifactId>
    <version>1.0-alpha.15</version>
</dependency>
```

```java
MongoConfig config = MongoConfig.of("mongodb://localhost:27017", "mydb");

VNodeRunner.create()
    .addPlugin(new MongoPlugin(config))
    .run();
```

## API

```java
public interface MongoService {
    MongoDatabase              database();
    MongoCollection<Document>  collection(String name);
    void                       close();
}
```

```java
MongoService mongo = ctx.service(MongoService.class);

// Insert a document
MongoCollection<Document> users = mongo.collection("users");
users.insertOne(new Document("name", "Alice").append("age", 30));

// Query
Document alice = users.find(Filters.eq("name", "Alice")).first();

// Access the full database for multi-collection operations
MongoDatabase db = mongo.database();
db.runCommand(new Document("ping", 1));
```

## Configuration

| Option             | Default | Meaning                                              |
|--------------------|---------|------------------------------------------------------|
| `connectionString` | —       | MongoDB connection URI (supports replica sets, Atlas)|
| `databaseName`     | —       | Name of the database to use                          |

## Notes

- `close()` is called automatically by the VATN shutdown hook; there is no need to call it manually.
- The sync driver blocks the calling thread; wrap calls in virtual threads for high-concurrency handlers.
- Authentication, TLS, and replica-set options are all handled inside the connection string.
- `collection(name)` always returns `MongoCollection<Document>`; use the driver's `withDocumentClass` method if you need a typed codec.
