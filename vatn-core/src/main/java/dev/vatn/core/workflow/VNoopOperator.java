package dev.vatn.core.workflow;

import dev.vatn.api.workflow.VOperator;
import dev.vatn.api.workflow.VTaskContext;

/**
 * A no-op operator that completes immediately.
 * Useful for placeholder tasks, testing DAG structure, and gate tasks.
 */
public class VNoopOperator implements VOperator {

    @Override
    public String operatorType() {
        return "noop";
    }

    @Override
    public String execute(VTaskContext ctx) {
        ctx.log("noop: completed");
        return "ok";
    }
}
