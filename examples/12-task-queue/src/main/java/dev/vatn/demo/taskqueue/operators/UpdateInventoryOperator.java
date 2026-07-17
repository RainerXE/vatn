package dev.vatn.demo.taskqueue.operators;

import dev.vatn.api.workflow.VOperator;
import dev.vatn.api.workflow.VTaskContext;

/**
 * Decrements stock levels for every item in the order.
 *
 * Pulls the charge ID to confirm payment succeeded, then writes inventory
 * reservations to the (simulated) warehouse database.
 */
public class UpdateInventoryOperator implements VOperator {

    @Override
    public String operatorType() {
        return "order.update-inventory";
    }

    @Override
    public String execute(VTaskContext ctx) throws Exception {
        ctx.log("Updating inventory for run %s", ctx.getRunId());

        String chargeId = ctx.getXCom().pull("charge-payment", "charge_id")
            .orElseThrow(() -> new IllegalStateException("charge_id XCom not found"));

        ctx.log("Charge %s confirmed — reserving stock", chargeId);

        // Simulate database write
        Thread.sleep(100);

        ctx.log("Inventory updated successfully");
        ctx.getXCom().push(ctx.getTaskId(), "inventory_updated", "true");

        return "inventory_updated:true";
    }
}
