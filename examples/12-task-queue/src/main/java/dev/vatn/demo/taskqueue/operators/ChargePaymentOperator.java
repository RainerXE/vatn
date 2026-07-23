package dev.vatn.demo.taskqueue.operators;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.workflow.VOperator;
import dev.vatn.api.workflow.VTaskContext;
import dev.vatn.demo.taskqueue.model.Order;

/**
 * Simulates charging the customer's payment method.
 *
 * Pulls the validated order from XCom, contacts the (simulated) payment gateway,
 * and pushes a charge ID for downstream tasks to reference.
 *
 * Configured with a 3-attempt retry policy so transient gateway failures are
 * handled automatically — no queue-side retry logic needed.
 */
public class ChargePaymentOperator implements VOperator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String operatorType() {
        return "order.charge-payment";
    }

    @Override
    public String execute(VTaskContext ctx) throws Exception {
        ctx.log("Charging payment (attempt %d) for run %s", ctx.getTryNumber(), ctx.getRunId());

        String orderJson = ctx.getXCom().pull("validate-order", "validated_order")
            .orElseThrow(() -> new IllegalStateException("validated_order XCom not found"));

        Order order = MAPPER.readValue(orderJson, Order.class);

        ctx.log("Contacting payment gateway for %.2f %s", order.totalAmount(), order.currency());

        // Simulate payment gateway round-trip
        Thread.sleep(300);

        // Derive a stable, deterministic charge ID from the run ID
        String chargeId = "ch_" + ctx.getRunId().substring(0, 8);

        ctx.log("Payment authorised — charge ID: %s", chargeId);
        ctx.getXCom().push(ctx.getTaskId(), "charge_id", chargeId);

        return chargeId;
    }
}
