package dev.vatn.bench;

import dev.vatn.core.VNodeRunner;
import dev.vatn.api.VNodeContext;
import org.openjdk.jmh.annotations.*;

/**
 * Shared JMH state that boots a VNodeRunner once per benchmark trial.
 * Extend this (or compose it) in benchmark classes that need a live node.
 */
@State(Scope.Benchmark)
public class VNodeState {

    protected VNodeRunner runner;
    protected VNodeContext context;

    @Setup(Level.Trial)
    public void startNode() throws Exception {
        runner = VNodeRunner.create(0);   // port 0 = random OS-assigned
        runner.start();
        context = runner.getContext();
    }

    @TearDown(Level.Trial)
    public void stopNode() throws Exception {
        if (runner != null) runner.stop();
    }
}
