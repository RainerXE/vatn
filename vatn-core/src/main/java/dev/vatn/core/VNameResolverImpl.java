package dev.vatn.core;

import dev.vatn.api.VNameResolver;


import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import java.util.stream.Collectors;

/**
 * Implementation of the VNameResolver for the VATN lattice.
 * Resolves logical vatn:// URIs to physical socket addresses using cached lattice metadata.
 */
public class VNameResolverImpl implements VNameResolver {
    private final VDiscoveryImpl discovery;

    public VNameResolverImpl(VDiscoveryImpl discovery) {
        this.discovery = discovery;
    }

    @Override
    public CompletableFuture<URI> resolve(String vatnUri) {
        return resolveAll(vatnUri).thenApply(list -> {
            if (list.isEmpty()) {
                throw new RuntimeException("Could not resolve lattice service: " + vatnUri);
            }
            // Logic: Trustworthy first, then round-robin/random
            return list.getFirst(); 
        });
    }

    @Override
    public CompletableFuture<List<URI>> resolveAll(String vatnUri) {
        String serviceId = extractServiceId(vatnUri);
        String path = extractPath(vatnUri);

        // Scan peers for this service
        List<VDiscoveryImpl.LatticePeerInfo> hosts = discovery.getPeerCache().values().stream()
                .filter(p -> p.getServices().contains(serviceId))
                .sorted(Comparator.comparingDouble(VDiscoveryImpl.LatticePeerInfo::getTrustworthiness).reversed())
                .collect(Collectors.toList());

        List<URI> resolvedUris = hosts.stream()
                .map(p -> buildPhysicalUri(p.getUri(), path))
                .collect(Collectors.toList());

        return CompletableFuture.completedFuture(resolvedUris);
    }

    @Override
    public Map<String, List<String>> getLatticeCatalog() {
        Map<String, List<String>> catalog = new HashMap<>();
        for (VDiscoveryImpl.LatticePeerInfo peer : discovery.getPeerCache().values()) {
            for (String serviceId : peer.getServices()) {
                catalog.computeIfAbsent(serviceId, k -> new ArrayList<>()).add(peer.getNodeId());
            }
        }
        return catalog;
    }

    private String extractServiceId(String vatnUri) {
        // vatn://service-id/path -> service-id
        String stripped = vatnUri.replace("vatn://", "");
        int slashIdx = stripped.indexOf('/');
        return slashIdx == -1 ? stripped : stripped.substring(0, slashIdx);
    }

    private String extractPath(String vatnUri) {
        // vatn://service-id/path -> /path
        String stripped = vatnUri.replace("vatn://", "");
        int slashIdx = stripped.indexOf('/');
        return slashIdx == -1 ? "" : stripped.substring(slashIdx);
    }

    private URI buildPhysicalUri(String baseUri, String path) {
        try {
            // Ensure join is clean (avoid //)
            String separator = baseUri.endsWith("/") || path.startsWith("/") ? "" : "/";
            return new URI(baseUri + separator + path);
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException("Failed to construct resolved URI", e);
        }
    }
}
