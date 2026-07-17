package dev.vatn.plugins.mongodb;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import dev.vatn.api.VService;
import org.bson.Document;

/**
 * MongoDB service registered by {@link MongoPlugin}.
 *
 * <pre>{@code
 * MongoService mongo = ctx.getService(MongoService.class).orElseThrow();
 *
 * MongoCollection<Document> users = mongo.collection("users");
 * users.insertOne(new Document("name", "Alice").append("role", "admin"));
 *
 * // Or get the raw database for full driver access
 * MongoDatabase db = mongo.database();
 * }</pre>
 */
public interface MongoService extends VService {

    /** Returns the configured database. */
    MongoDatabase database();

    /** Convenience shortcut for {@code database().getCollection(name)}. */
    MongoCollection<Document> collection(String name);
}
