package dev.vatn.demo.taskqueue.operators;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.workflow.VOperator;
import dev.vatn.api.workflow.VTaskContext;
import dev.vatn.demo.taskqueue.model.Order;

/**
 * Validates the incoming order from the run conf, checks that it has at least one
 * item and a positive total, then pushes the serialised order and customer e-mail
 * to XCom for downstream operators.
 */
public class ValidateOrderOperator implements VOperator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String operatorType() {
        return "order.validate";
    }

    @Override
    public String execute(VTaskContext ctx) throws Exception {
        ctx.log("Validating order for run %s", ctx.getRunId());

        String orderJson = ctx.getConf().get("order");
        if (orderJson == null || orderJson.isBlank()) {
            throw new IllegalArgumentException("No 'order' key found in run conf");
        }

        Order order = MAPPER.readValue(orderJson, Order.class);

        if (order.items() == null || order.items().isEmpty()) {
            throw new IllegalArgumentException("Order %s has no items".formatted(order.orderId()));
        }
        if (order.totalAmount() <= 0) {
            throw new IllegalArgumentException(
                "Order %s has invalid total: %s".formatted(order.orderId(), order.totalAmount()));
        }

        ctx.log("Order %s is valid — %d item(s), total %.2f %s",
            order.orderId(), order.items().size(), order.totalAmount(), order.currency());

        // Small simulated latency — reading from a database or calling an address-validation API
        Thread.sleep(150);

        ctx.getXCom().push(ctx.getTaskId(), "validated_order", orderJson);
        ctx.getXCom().push(ctx.getTaskId(), "customer_email", order.customerEmail());

        return "validated:" + order.orderId();
    }
}
