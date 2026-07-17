package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.KubernetesInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads kubectl contexts and detects local cluster tools. All read-only.
 */
public final class KubernetesScanner {

    private final ScannerUtil util;

    public KubernetesScanner(ScannerUtil util) {
        this.util = util;
    }

    public KubernetesInfo scan() {
        if (util.which("kubectl").isEmpty()) {
            return new KubernetesInfo(List.of(), "", detectLocalClusters());
        }
        List<String> contexts = util.exec("kubectl", "config", "get-contexts", "-o", "name")
                .map(KubernetesScanner::parseContexts).orElse(List.of());
        String current = util.exec("kubectl", "config", "current-context").orElse("").strip();
        return new KubernetesInfo(contexts, current, detectLocalClusters());
    }

    static List<String> parseContexts(String output) {
        return output.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private List<String> detectLocalClusters() {
        var out = new ArrayList<String>();
        for (String tool : List.of("minikube", "kind", "k3d", "k3s", "k0s", "microk8s")) {
            if (util.which(tool).isPresent()) out.add(tool);
        }
        return List.copyOf(out);
    }
}
