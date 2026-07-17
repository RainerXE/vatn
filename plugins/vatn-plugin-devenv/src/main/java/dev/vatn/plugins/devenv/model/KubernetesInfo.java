package dev.vatn.plugins.devenv.model;

import java.util.List;

/**
 * Kubernetes client configuration and local clusters.
 *
 * @param contexts       kubeconfig context names
 * @param currentContext active context ("" if none)
 * @param localClusters  detected local cluster tools (minikube, kind, k3d, k3s)
 */
public record KubernetesInfo(List<String> contexts, String currentContext, List<String> localClusters) {

    public static KubernetesInfo empty() {
        return new KubernetesInfo(List.of(), "", List.of());
    }
}
