package dev.vatn.plugins.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

final class MongoServiceImpl implements MongoService {

    private final MongoClient client;
    private final MongoDatabase db;

    MongoServiceImpl(MongoConfig config) {
        this.client = MongoClients.create(config.getConnectionString());
        this.db     = client.getDatabase(config.getDatabase());
    }

    void close() { client.close(); }

    @Override public MongoDatabase database() { return db; }

    @Override public MongoCollection<Document> collection(String name) {
        return db.getCollection(name);
    }
}
