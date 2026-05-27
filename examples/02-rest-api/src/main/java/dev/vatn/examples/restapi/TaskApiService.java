package dev.vatn.examples.restapi;

import dev.vatn.api.VHttpRequest;
import dev.vatn.api.VHttpResponse;
import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.api.VPersistenceService;
import dev.vatn.api.VRateLimiter;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class TaskApiService implements VHttpService {

    private final VPersistenceService db;
    private final VRateLimiter        rateLimiter;

    public TaskApiService(VPersistenceService db, VRateLimiter rateLimiter) {
        this.db          = db;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void routing(VHttpRoutes routes) {
        routes.get("/",       this::listTasks);
        routes.post("/",      this::createTask);
        routes.get("/{id}",   this::getTask);
        routes.put("/{id}",   this::updateTask);
        routes.delete("/{id}", this::deleteTask);
    }

    private void listTasks(VHttpRequest req, VHttpResponse res) throws Exception {
        if (rateLimiter != null && !rateLimiter.tryAcquire("tasks.read")) {
            res.status(429).send("rate limit exceeded");
            return;
        }
        try (Connection conn = db.getConnection();
             var st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, title, done, created FROM tasks ORDER BY id")) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(taskJson(rs.getLong(1), rs.getString(2), rs.getInt(3) == 1, rs.getString(4)));
            }
            res.sendJson("[" + String.join(",", rows) + "]");
        }
    }

    private void createTask(VHttpRequest req, VHttpResponse res) throws Exception {
        if (rateLimiter != null && !rateLimiter.tryAcquire("tasks.write")) {
            res.status(429).send("rate limit exceeded");
            return;
        }
        String body = req.getBody();
        String title = extractField(body, "title");
        if (title == null || title.isBlank()) {
            res.status(400).send("title is required");
            return;
        }
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement("INSERT INTO tasks (title) VALUES (?)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, title);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);
                res.status(201).sendJson(taskJson(id, title, false, null));
            }
        }
    }

    private void getTask(VHttpRequest req, VHttpResponse res) throws Exception {
        long id = Long.parseLong(req.getPathParam("id"));
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement("SELECT id, title, done, created FROM tasks WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { res.status(404).send("not found"); return; }
                res.sendJson(taskJson(rs.getLong(1), rs.getString(2), rs.getInt(3) == 1, rs.getString(4)));
            }
        }
    }

    private void updateTask(VHttpRequest req, VHttpResponse res) throws Exception {
        if (rateLimiter != null && !rateLimiter.tryAcquire("tasks.write")) {
            res.status(429).send("rate limit exceeded");
            return;
        }
        long id = Long.parseLong(req.getPathParam("id"));
        String body = req.getBody();
        String doneStr = extractField(body, "done");
        boolean done = "true".equalsIgnoreCase(doneStr);
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement("UPDATE tasks SET done=? WHERE id=?")) {
            ps.setInt(1, done ? 1 : 0);
            ps.setLong(2, id);
            if (ps.executeUpdate() == 0) { res.status(404).send("not found"); return; }
        }
        getTask(req, res);
    }

    private void deleteTask(VHttpRequest req, VHttpResponse res) throws Exception {
        if (rateLimiter != null && !rateLimiter.tryAcquire("tasks.write")) {
            res.status(429).send("rate limit exceeded");
            return;
        }
        long id = Long.parseLong(req.getPathParam("id"));
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement("DELETE FROM tasks WHERE id=?")) {
            ps.setLong(1, id);
            if (ps.executeUpdate() == 0) { res.status(404).send("not found"); return; }
        }
        res.sendEmpty();
    }

    private static String taskJson(long id, String title, boolean done, String created) {
        String createdVal = created != null ? "\"" + created + "\"" : "null";
        return "{\"id\":" + id + ",\"title\":\"" + escape(title) + "\",\"done\":" + done
                + ",\"created\":" + createdVal + "}";
    }

    // Minimal JSON field extractor — use VJson in production code
    private static String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
