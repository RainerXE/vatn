package dev.vatn.demo.taskqueue.operators;

import dev.vatn.api.workflow.VOperator;
import dev.vatn.api.workflow.VTaskContext;

/**
 * Sends an order confirmation e-mail to the customer.
 *
 * Pulls the customer e-mail address set by the validation step, then hands
 * the message to the (simulated) e-mail service.  Runs in parallel with
 * UpdateInventoryOperator — both depend only on the charge step.
 */
public class SendConfirmationOperator implements VOperator {

    @Override
    public String operatorType() {
        return "order.send-confirmation";
    }

    @Override
    public String execute(VTaskContext ctx) throws Exception {
        ctx.log("Sending order confirmation for run %s", ctx.getRunId());

        String customerEmail = ctx.getXCom().pull("validate-order", "customer_email")
            .orElseThrow(() -> new IllegalStateException("customer_email XCom not found"));

        ctx.log("Composing confirmation e-mail for %s", customerEmail);

        // Simulate SMTP / transactional e-mail API call
        Thread.sleep(200);

        String message = "Order confirmation sent to " + customerEmail + " (run=" + ctx.getRunId() + ")";
        ctx.log(message);

        return message;
    }
}
