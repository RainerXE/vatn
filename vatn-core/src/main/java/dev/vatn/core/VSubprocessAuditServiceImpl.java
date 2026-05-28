package dev.vatn.core;

import dev.vatn.api.VSubprocessAuditEntry;
import dev.vatn.api.VSubprocessAuditService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default in-memory implementation of {@link VSubprocessAuditService}.
 *
 * <p>Entries are kept for the lifetime of the node. For persistent history,
 * applications may register a database-backed replacement before node start.
 */
public final class VSubprocessAuditServiceImpl implements VSubprocessAuditService {

    private final CopyOnWriteArrayList<VSubprocessAuditEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void record(VSubprocessAuditEntry entry) {
        entries.add(entry);
    }

    @Override
    public List<VSubprocessAuditEntry> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    @Override
    public List<VSubprocessAuditEntry> getForSession(String sessionId) {
        return entries.stream()
            .filter(e -> sessionId != null && sessionId.equals(e.sessionId()))
            .toList();
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public String toJsonArray() {
        if (entries.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(entries.get(i).toJson());
        }
        sb.append("]");
        return sb.toString();
    }
}
