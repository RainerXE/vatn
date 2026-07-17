package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

/**
 * Standard operator for executing Containers (Docker/Podman/Distrobox).
 * Backed by the Container Management plugin.
 */
@VatnApi(since = "1.0-alpha.15")
public interface VContainerOperator extends VOperator {
    String TYPE = "system.container";

    @Override
    default String operatorType() {
        return TYPE;
    }
}
