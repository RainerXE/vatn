package dev.vatn.core.admin;

import dev.vatn.api.admin.VWorkload;
import dev.vatn.api.admin.VWorkloadProvider;
import dev.vatn.api.admin.VWorkloadRegistry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class VWorkloadRegistryImpl implements VWorkloadRegistry {
    
    private final List<VWorkloadProvider> providers = new CopyOnWriteArrayList<>();

    @Override
    public void registerProvider(VWorkloadProvider provider) {
        providers.add(provider);
    }

    @Override
    public List<VWorkload> getAllWorkloads() {
        return providers.stream()
            .flatMap(provider -> provider.getActiveWorkloads().stream())
            .collect(Collectors.toList());
    }
}
