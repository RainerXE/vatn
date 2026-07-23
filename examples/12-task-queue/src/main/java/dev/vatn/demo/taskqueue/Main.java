package dev.vatn.demo.taskqueue;

import dev.vatn.core.VNodeRunner;

/**
 * Entry point for the order-pipeline demo.
 *
 * Starts a VATN node on port 8080 with the OrderPipelinePlugin installed.
 * The plugin registers the DAG and exposes the REST API at /orders.
 *
 * Build:
 *   mvn package
 *
 * Run:
 *   java -jar target/12-task-queue-1.0-alpha.14.jar
 */
public class Main {
    public static void main(String[] args) {
        VNodeRunner runner = VNodeRunner.create(8080);
        runner.addPlugin(new OrderPipelinePlugin());
        runner.start();
    }
}
