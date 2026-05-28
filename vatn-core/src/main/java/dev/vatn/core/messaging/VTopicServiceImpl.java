package dev.vatn.core.messaging;

import dev.vatn.api.VPersistenceService;
import dev.vatn.api.VTopic;
import dev.vatn.api.VTopicService;

import java.util.concurrent.ConcurrentHashMap;

public class VTopicServiceImpl implements VTopicService {

    private final VPersistenceService db;
    private final ConcurrentHashMap<String, VTopicImpl> topics = new ConcurrentHashMap<>();

    public VTopicServiceImpl(VPersistenceService db) {
        this.db = db;
    }

    @Override
    public VTopic topic(String name) {
        return topics.computeIfAbsent(name, n -> new VTopicImpl(n, db));
    }
}
