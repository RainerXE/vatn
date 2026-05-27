package dev.vatn.api;

import java.util.List;
import java.util.Map;

/**
 * Standard schema for Workspace Ontology rules.
 * Supports hierarchical merging of System vs. Workspace rules.
 */
@VatnApi(since = "1.0")
public record VOntologyRules(
    String id,
    String version,
    List<FolderRule> folders,
    Map<String, String> fileExtensions,
    ViolationAction defaultAction
) {
    public enum ViolationAction {
        ALLOW,
        BLOCK,
        PENDING_APPROVAL
    }

    public record FolderRule(
        String name,
        String pathGlob,
        List<String> allowedExtensions,
        boolean recursive,
        ViolationAction actionOverride
    ) {}
}
