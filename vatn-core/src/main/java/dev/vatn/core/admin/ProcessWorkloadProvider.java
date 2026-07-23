package dev.vatn.core.admin;

import dev.vatn.api.admin.VWorkload;
import dev.vatn.api.admin.VWorkloadProvider;
import dev.vatn.core.LocalProcessService;

import java.util.List;

public class ProcessWorkloadProvider implements VWorkloadProvider {
    
    private final LocalProcessService processService;

    public ProcessWorkloadProvider(LocalProcessService processService) {
        this.processService = processService;
    }

    @Override
    public List<VWorkload> getActiveWorkloads() {
        return processService.getActiveWorkloads();
    }
}
