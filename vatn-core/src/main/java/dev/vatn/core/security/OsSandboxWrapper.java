package dev.vatn.core.security;

import dev.vatn.api.security.VTrustLevel;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps process commands with OS-native sandboxing binaries
 * based on the host OS and the requested VTrustLevel.
 */
public class OsSandboxWrapper {

    public static List<String> wrapCommand(List<String> command, VTrustLevel trustLevel) {
        if (trustLevel == null || trustLevel == VTrustLevel.FULL || trustLevel == VTrustLevel.VERIFIED_FEDERATED) {
            return command; // No sandboxing required
        }

        String os = System.getProperty("os.name").toLowerCase();
        List<String> wrapped = new ArrayList<>();

        if (os.contains("mac")) {
            // macOS: use sandbox-exec
            wrapped.add("/usr/bin/sandbox-exec");
            wrapped.add("-p");
            
            if (trustLevel == VTrustLevel.SANDBOXED) {
                // Extremely strict: deny all file writes and network
                wrapped.add("(version 1)(allow default)(deny file-write*)(deny network*)");
            } else { // RESTRICTED or NONE
                // Restricted: deny file writes but allow network, or other constraints
                wrapped.add("(version 1)(allow default)(deny file-write*)");
            }
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            // Linux: use bwrap (bubblewrap)
            wrapped.add("bwrap");
            wrapped.add("--ro-bind"); wrapped.add("/"); wrapped.add("/");
            wrapped.add("--dev"); wrapped.add("/dev");
            wrapped.add("--proc"); wrapped.add("/proc");
            wrapped.add("--tmpfs"); wrapped.add("/tmp");
            
            if (trustLevel == VTrustLevel.SANDBOXED) {
                wrapped.add("--unshare-net");
                wrapped.add("--unshare-all");
            } else {
                // Allow network for RESTRICTED
                wrapped.add("--share-net");
            }
        } else if (os.contains("win")) {
            // Windows: No native CLI wrapper standard yet (prep for Restricted Tokens or Windows Sandbox)
            // Fallback: just return the original for now until implemented
            return command;
        } else {
            // Unknown OS: Fallback
            return command;
        }

        wrapped.addAll(command);
        return wrapped;
    }
}
