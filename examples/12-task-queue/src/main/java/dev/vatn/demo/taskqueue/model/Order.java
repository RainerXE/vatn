package dev.vatn.demo.taskqueue.model;

import java.util.List;

/**
 * An e-commerce order submitted through the API.
 *
 * @param orderId       Unique order identifier.
 * @param customerId    Customer account identifier.
 * @param customerEmail Customer e-mail address for confirmation.
 * @param items         Line items in the order.
 * @param totalAmount   Pre-calculated order total.
 * @param currency      ISO 4217 currency code (e.g. "USD").
 */
public record Order(
    String orderId,
    String customerId,
    String customerEmail,
    List<OrderItem> items,
    double totalAmount,
    String currency
) {
}
