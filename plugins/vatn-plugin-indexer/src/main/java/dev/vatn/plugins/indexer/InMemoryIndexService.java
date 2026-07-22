package dev.vatn.plugins.indexer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryIndexService implements IndexerService {

    private final ConcurrentHashMap<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    @Override
    public void index(String id, Map<String, Object> document) {
        store.put(id, new ConcurrentHashMap<>(document));
    }

    @Override
    public void indexBatch(List<Map<String, Object>> documents) {
        for (Map<String, Object> doc : documents) {
            String id = (String) doc.get("id");
            if (id == null || id.isBlank()) {
                id = UUID.randomUUID().toString();
            }
            index(id, doc);
        }
    }

    @Override
    public Map<String, Object> get(String id) {
        Map<String, Object> doc = store.get(id);
        return doc != null ? new HashMap<>(doc) : null;
    }

    @Override
    public List<Map<String, Object>> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String lowerQuery = query.toLowerCase();
        return store.entrySet().stream()
                .filter(entry -> entry.getValue().values().stream()
                        .anyMatch(v -> v instanceof String s && s.toLowerCase().contains(lowerQuery)))
                .map(entry -> {
                    Map<String, Object> result = new HashMap<>(entry.getValue());
                    result.put("id", entry.getKey());
                    return result;
                })
                .collect(Collectors.toList());
    }

    @Override
    public void clear() {
        store.clear();
    }

    @Override
    public int size() {
        return store.size();
    }
}
