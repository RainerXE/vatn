# Phase 2: Container Resource Profiles

## Goal
Add resource constraint profiles (CPU, memory, device mounts, GPU) to the containers plugin, with structured fields and raw CLI override.

## Design

### ResourceProfile record (`vatn-api`-compatible, zero deps)
```
id: String            (auto-UUID)
name: String
description: String
cpuMin: String        e.g. "0.5", "2"
cpuMax: String        e.g. "4"
memoryMin: String     e.g. "256m", "1g"
memoryMax: String     e.g. "2g"
deviceMounts: List<String>   structured "host:container:perms"
gpuMode: String       "none" | "all" | "count:N" | device-IDs
extraCliArgs: String  raw text override — supersedes structured when set
createdAt: long
```

### ResourceProfileService SPI
- `List<ResourceProfile> list()`
- `Optional<ResourceProfile> get(String id)`
- `ResourceProfile save(ResourceProfile)`
- `void delete(String id)`

### JsonResourceProfileStore
- Persists to `~/.vatn/containers/profiles.json`
- Same pattern as `JsonTemplateStore`

### Integration
- `ContainerTemplate` gets a `resourceProfileId` field (nullable)
- `ContainerCreator.buildPodmanArgs()` reads the profile and appends:
  - `--cpus=X` from cpuMax
  - `--memory=X` from memoryMax
  - `--device=host:container:perms` for each device mount
  - `--gpus all` or `--gpus device=X` from gpuMode
  - If `extraCliArgs` is set, use raw string instead (split on spaces)
- Distrobox/Toolbox ignore resource flags (not supported by those tools)

### UI
- Resource Profiles tab with list/create/edit/delete
- Template editor gets a profile selector dropdown
- Dashboard shows "Show CLI" toggle to view/edit raw flags

### Files
- `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ResourceProfile.java` (new)
- `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ResourceProfileService.java` (new)
- `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/JsonResourceProfileStore.java` (new)
- Modify: `ContainerTemplate.java`, `ContainerCreator.java`, `ContainersPlugin.java`, `ContainersHtml.java`, `ContainersPluginTest.java`, `reflect-config.json`
