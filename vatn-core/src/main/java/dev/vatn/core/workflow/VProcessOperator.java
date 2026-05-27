package dev.vatn.core.workflow;

import dev.vatn.api.VProcessService;
import dev.vatn.api.workflow.VOperator;
import dev.vatn.api.workflow.VTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Runs a shell command as a subprocess. Reads the command from task metadata key {@code "command"}.
 *
 * <p>Example VDagTask metadata: {@code {"command": "echo hello"}}.
 * stdout is captured and returned as the operator result.
 */
public class VProcessOperator implements VOperator {
    private static final Logger logger = LoggerFactory.getLogger(VProcessOperator.class);
    private static final String COMMAND_KEY = "command";
    private static final String SHELL_KEY = "shell";

    @Override
    public String operatorType() {
        return "process";
    }

    @Override
    public String execute(VTaskContext ctx) throws Exception {
        String command = (String) ctx.getMetadata().get(COMMAND_KEY);
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("[process] metadata key 'command' is required");
        }

        String shell = (String) ctx.getMetadata().getOrDefault(SHELL_KEY, "/bin/sh");
        ctx.log("process: running: %s", command);

        VProcessService processService = ctx.getNodeContext()
                .getService(VProcessService.class)
                .orElseThrow(() -> new IllegalStateException("VProcessService not available"));

        VProcessService.VProcessResult result = processService.execute(
                List.of(shell, "-c", command), Map.of(), null);
        ctx.log("process: exit=%d stdout-length=%d", result.exitCode(), result.stdout().length());
        if (result.exitCode() != 0) {
            throw new RuntimeException("[process] command exited " + result.exitCode() + ": " + result.stderr());
        }
        return result.stdout();
    }
}
