package dev.vatn.demo.taskqueue.operators;

import dev.vatn.api.workflow.VOperator;
import dev.vatn.api.workflow.VTaskContext;

/**
 * Notifies the shipping/fulfilment service once inventory has been reserved.
 *
 * Pulls the inventory-updated flag, calls the (simulated) warehouse API to
 * create a shipment record, and returns the tracking number.
 */
public class NotifyShippingOperator implements VOperator {

    @Override
    public String operatorType() {
        return "order.notify-shipping";
    }

    @Override
    public String execute(VTaskContext ctx) throws Exception {
        ctx.log("Notifying shipping for run %s", ctx.getRunId());

        String inventoryUpdated = ctx.getXCom().pull("update-inventory", "inventory_updated")
            .orElseThrow(() -> new IllegalStateException("inventory_updated XCom not found"));

        if (!"true".equals(inventoryUpdated)) {
            throw new IllegalStateException("Inventory was not successfully updated — aborting shipment");
        }

        ctx.log("Inventory confirmed — creating shipment record");

        // Simulate call to warehouse / logistics API
        Thread.sleep(150);

        String trackingNumber = "TRK-" + ctx.getRunId().substring(0, 12).toUpperCase();
        ctx.log("Shipment created — tracking number: %s", trackingNumber);

        return trackingNumber;
    }
}
