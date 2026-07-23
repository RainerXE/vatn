package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

/**
 * Standard operator for interacting with native, unsandboxed host services.
 */
@VatnApi(since = "1.0-alpha.15")
public interface VNativeOperator extends VOperator {
    String TYPE = "system.native";

    @Override
    default String operatorType() {
        return TYPE;
    }
}
