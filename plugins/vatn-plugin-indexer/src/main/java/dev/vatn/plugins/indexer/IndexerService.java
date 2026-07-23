package dev.vatn.plugins.indexer;

import dev.vatn.api.VService;
import java.util.List;
import java.util.Map;

public interface IndexerService extends VService {
    void index(String id, Map<String, Object> document);
    void indexBatch(List<Map<String, Object>> documents);
    Map<String, Object> get(String id);
    List<Map<String, Object>> search(String query);
    void clear();
    int size();
}
