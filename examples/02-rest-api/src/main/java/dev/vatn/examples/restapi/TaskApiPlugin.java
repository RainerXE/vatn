package dev.vatn.examples.restapi;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VPersistenceService;
import dev.vatn.api.VRateLimiter;

public class TaskApiPlugin implements VNodePlugin {

    private TaskApiService service;

    @Override public String getId()      { return "dev.vatn.examples.rest-api"; }
    @Override public String getName()    { return "Task Manager API"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        VPersistenceService db = ctx.getService(VPersistenceService.class)
                .orElseThrow(() -> new IllegalStateException("VPersistenceService not available"));

        VRateLimiter rateLimiter = ctx.getService(VRateLimiter.class).orElse(null);
        if (rateLimiter != null) {
            rateLimiter.configure("tasks.read",  100); // 100 req/s for list/get
            rateLimiter.configure("tasks.write",  10); // 10 req/s for create/update/delete
        }

        db.registerSchemaContributor(st -> st.execute("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        title   TEXT NOT NULL,
                        done    INTEGER NOT NULL DEFAULT 0,
                        created TEXT NOT NULL DEFAULT (datetime('now'))
                    )
                    """));

        service = new TaskApiService(db, rateLimiter);
        ctx.register("/tasks", service);
    }
}
