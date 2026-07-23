package dev.vatn.demo.taskqueue.model;

/**
 * A single line item in an order.
 *
 * @param sku      Stock-keeping unit identifier.
 * @param quantity Number of units ordered.
 * @param price    Unit price.
 */
public record OrderItem(String sku, int quantity, double price) {
}
