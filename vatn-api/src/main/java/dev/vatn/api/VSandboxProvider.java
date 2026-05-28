package dev.vatn.api;

/**
 * Executes shell commands inside the node's security sandbox.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Evaluate the command against the active {@link VGuardService} before execution</li>
 *   <li>Honour the caller's {@link dev.vatn.api.security.VTrustLevel} to select the OS sandbox wrapper</li>
 *   <li>Record every execution via {@link VSubprocessAuditService}</li>
 * </ul>
 *
 * <p>Obtain via {@code context.getService(VSandboxProvider.class)}:
 * <pre>{@code
 * VSandboxProvider sandbox = ctx.getService(VSandboxProvider.class).orElseThrow();
 * String output = sandbox.exec("odin check .", 60);
 * }</pre>
 *
 * <p>The default implementation in {@code vatn-core} delegates to
 * {@link VProcessService} and {@link VGuardService}. Applications can
 * replace it by registering their own implementation before calling
 * {@link dev.vatn.core.VNodeRunner#start()}.
 */
@VatnApi(since = "1.0-alpha.10")
public interface VSandboxProvider extends VService {

    /**
     * Executes {@code command} in a sandboxed subprocess.
     *
     * @param command        the shell command to run (passed to {@code bash -c})
     * @param timeoutSeconds maximum wall-clock time; 0 means no timeout
     * @return stdout of the process, or an error string prefixed with {@code "Error:"}
     *         if the command was blocked, timed out, or threw an exception
     */
    String exec(String command, int timeoutSeconds);
}
