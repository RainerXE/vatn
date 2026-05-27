package dev.vatn.examples.restapi;

import dev.vatn.core.VNodeRunner;

public class Main {
    public static void main(String[] args) {
        VNodeRunner runner = VNodeRunner.create(8080);
        runner.addPlugin(new TaskApiPlugin());
        runner.start();
    }
}
