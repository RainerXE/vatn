package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

/**
 * Standard operator for executing WASM modules.
 * Backed by {@link dev.vatn.api.VWasmRuntime}.
 */
@VatnApi(since = "1.0-alpha.15")
public interface VWasmOperator extends VOperator {
    String TYPE = "system.wasm";

    @Override
    default String operatorType() {
        return TYPE;
    }
}
