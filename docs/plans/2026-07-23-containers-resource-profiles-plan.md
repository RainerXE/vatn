# Container Resource Profiles — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add resource constraint profiles (CPU, memory, device mounts, GPU) to the containers plugin with structured fields and raw CLI override.

**Architecture:** New `ResourceProfile` record + `ResourceProfileService` SPI + `JsonResourceProfileStore` (same pattern as templates). `ContainerTemplate` gets a `resourceProfileId` field. `ContainerCreator.buildPodmanArgs()` translates structured fields to `--cpus`, `--memory`, `--device`, `--gpus` flags, or uses raw `extraCliArgs` if set.

**Tech Stack:** Java 25, vatn-api (zero deps), Jackson 2.16.1

---

### Task 1: ResourceProfile record + SPI

**Files:**
- Create: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ResourceProfile.java`
- Create: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ResourceProfileService.java`

**Step 1: Create ResourceProfile record**

```java
package dev.vatn.plugins.containers;

import java.util.List;

public record ResourceProfile(
    String id,
    String name,
    String description,
    String cpuMin,
    String cpuMax,
    String memoryMin,
    String memoryMax,
    List<String> deviceMounts,
    String gpuMode,
    String extraCliArgs,
    long createdAt
) {
    public ResourceProfile {
        if (id == null || id.isBlank()) id = java.util.UUID.randomUUID().toString();
        if (deviceMounts == null) deviceMounts = List.of();
        if (gpuMode == null) gpuMode = "none";
        if (extraCliArgs == null) extraCliArgs = "";
    }
}
```

**Step 2: Create ResourceProfileService SPI**

```java
package dev.vatn.plugins.containers;

import java.util.List;
import java.util.Optional;

public interface ResourceProfileService {
    List<ResourceProfile> list();
    Optional<ResourceProfile> get(String id);
    ResourceProfile save(ResourceProfile profile);
    void delete(String id);
}
```

**Step 3: Compile**

Run: `mvn compile -pl plugins/vatn-plugin-containers -am -DskipTests`
Expected: BUILD SUCCESS

---

### Task 2: JsonResourceProfileStore

**Files:**
- Create: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/JsonResourceProfileStore.java`

**Step 1: Create store**

Same pattern as `JsonTemplateStore`: persists to `<workspace>/profiles.json`, thread-safe `ConcurrentHashMap` + Jackson read/write.

```java
package dev.vatn.plugins.containers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class JsonResourceProfileStore implements ResourceProfileService {
    private static final Logger log = LoggerFactory.getLogger(JsonResourceProfileStore.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();
    private static final TypeReference<Map<String, ResourceProfile>> TYPE_REF = new TypeReference<>() {};

    private final Path filePath;
    private final Map<String, ResourceProfile> profiles = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public JsonResourceProfileStore(Path workspacePath) {
        this.filePath = workspacePath.resolve("profiles.json");
        load();
    }

    private void load() {
        lock.writeLock().lock();
        try {
            if (Files.exists(filePath)) {
                var map = MAPPER.readValue(filePath.toFile(), TYPE_REF);
                profiles.putAll(map);
            }
        } catch (IOException e) {
            log.warn("Failed to load profiles from {}", filePath, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void save() {
        lock.readLock().lock();
        try {
            var dir = filePath.getParent();
            if (dir != null) Files.createDirectories(dir);
            MAPPER.writeValue(filePath.toFile(), new HashMap<>(profiles));
        } catch (IOException e) {
            log.error("Failed to save profiles to {}", filePath, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override public List<ResourceProfile> list() {
        lock.readLock().lock();
        try {
            var list = new ArrayList<>(profiles.values());
            list.sort(Comparator.comparingLong(ResourceProfile::createdAt).reversed());
            return List.copyOf(list);
        } finally { lock.readLock().unlock(); }
    }

    @Override public Optional<ResourceProfile> get(String id) {
        lock.readLock().lock();
        try { return Optional.ofNullable(profiles.get(id)); }
        finally { lock.readLock().unlock(); }
    }

    @Override public ResourceProfile save(ResourceProfile profile) {
        lock.writeLock().lock();
        try {
            var saved = new ResourceProfile(
                profile.id(), profile.name(), profile.description(),
                profile.cpuMin(), profile.cpuMax(),
                profile.memoryMin(), profile.memoryMax(),
                profile.deviceMounts(), profile.gpuMode(), profile.extraCliArgs(),
                profile.createdAt() > 0 ? profile.createdAt() : System.currentTimeMillis()
            );
            profiles.put(saved.id(), saved);
            save();
            return saved;
        } finally { lock.writeLock().unlock(); }
    }

    @Override public void delete(String id) {
        lock.writeLock().lock();
        try {
            profiles.remove(id);
            save();
        } finally { lock.writeLock().unlock(); }
    }
}
```

**Step 2: Compile**

Run: `mvn compile -pl plugins/vatn-plugin-containers -am -DskipTests`
Expected: BUILD SUCCESS

---

### Task 3: Add resourceProfileId to ContainerTemplate

**Files:**
- Modify: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ContainerTemplate.java`

**Step 1: Add field**

Add `String resourceProfileId` before `postStartCommands`, with null default in the compact constructor.

```java
public record ContainerTemplate(
    // ... existing fields ...
    String resourceProfileId,
    List<String> postStartCommands,
    // ...
) {
    public ContainerTemplate {
        // ... existing defaults ...
        if (resourceProfileId == null) resourceProfileId = "";
    }
}
```

**Step 2: Compile**

Run: `mvn compile -pl plugins/vatn-plugin-containers -am -DskipTests`
Expected: BUILD SUCCESS

Note: This will create compile errors in test code that constructs `ContainerTemplate` without the new field. Fix those in Task 6 (tests).

---

### Task 4: Integrate resource profiles into ContainerCreator

**Files:**
- Modify: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ContainerCreator.java`

**Step 1: Inject ResourceProfileService**

Add constructor parameter:
```java
private final ResourceProfileService profileService;

public ContainerCreator(VProcessService processService, List<ContainerManager> managers, ResourceProfileService profileService) {
    this.processService = processService;
    this.managers = managers;
    this.profileService = profileService;
}
```

**Step 2: Add resource flag builder method**

```java
private void applyResourceArgs(List<String> args, ContainerTemplate t) {
    String profileId = t.resourceProfileId();
    if (profileId == null || profileId.isBlank()) return;

    var profile = profileService.get(profileId);
    if (profile.isEmpty()) {
        log.warn("Resource profile {} not found; skipping resource args", profileId);
        return;
    }
    var p = profile.get();

    if (p.extraCliArgs() != null && !p.extraCliArgs().isBlank()) {
        args.addAll(splitCommand(p.extraCliArgs()));
        return;
    }

    if (p.cpuMax() != null && !p.cpuMax().isBlank()) {
        args.add("--cpus"); args.add(p.cpuMax());
    }
    if (p.memoryMax() != null && !p.memoryMax().isBlank()) {
        args.add("--memory"); args.add(p.memoryMax());
    }
    for (var d : p.deviceMounts()) {
        if (d != null && !d.isBlank()) {
            args.add("--device"); args.add(d);
        }
    }
    if (p.gpuMode() != null && !p.gpuMode().isBlank() && !"none".equals(p.gpuMode())) {
        if ("all".equals(p.gpuMode())) {
            args.add("--gpus"); args.add("all");
        } else if (p.gpuMode().startsWith("count:")) {
            args.add("--gpus"); args.add(p.gpuMode());
        } else {
            args.add("--gpus"); args.add("device=" + p.gpuMode());
        }
    }
}
```

**Step 3: Call from buildPodmanArgs**

Add `applyResourceArgs(args, t);` before `args.add(t.image());` in `buildPodmanArgs`.

**Step 4: Compile**

Run: `mvn compile -pl plugins/vatn-plugin-containers -am -DskipTests`
Expected: BUILD SUCCESS

---

### Task 5: Wire profiles into ContainersPlugin

**Files:**
- Modify: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ContainersPlugin.java`

**Step 1: Init profile service and update ContainerCreator**

After template service init, add:
```java
ResourceProfileService profileService = new JsonResourceProfileStore(context.getWorkspacePath());
context.registerService(ResourceProfileService.class, profileService);
```

Update ContainerCreator constructor to pass profileService:
```java
ContainerCreator creator = new ContainerCreator(processService, managers, profileService);
```

**Step 2: Add profile CRUD endpoints**

Add before the template endpoints:
```java
routes.get("/api/profiles", (req, res) -> res.send(json.stringify(profileService.list())));
routes.get("/api/profiles/{id}", (req, res) -> {
    var p = profileService.get(req.getPathParam("id"));
    if (p.isEmpty()) { res.status(404).send("{\"error\":\"Not found\"}"); return; }
    res.send(json.stringify(p.get()));
});
routes.post("/api/profiles", (req, res) -> {
    var p = json.parse(req.getBody(), ResourceProfile.class);
    res.send(json.stringify(profileService.save(p)));
});
routes.delete("/api/profiles/{id}", (req, res) -> {
    profileService.delete(req.getPathParam("id"));
    res.sendEmpty();
});
```

**Step 3: Compile**

Run: `mvn compile -pl plugins/vatn-plugin-containers -am -DskipTests`
Expected: BUILD SUCCESS

---

### Task 6: Update reflect-config.json

**Files:**
- Modify: `plugins/vatn-plugin-containers/src/main/resources/META-INF/native-image/dev.vatn.plugins.containers/reflect-config.json`

Add entries:
```json
{"name":"dev.vatn.plugins.containers.ResourceProfile","allDeclaredConstructors":true,"allDeclaredMethods":true},
{"name":"dev.vatn.plugins.containers.JsonResourceProfileStore","allDeclaredConstructors":true,"allDeclaredMethods":true}
```

---

### Task 7: Add Dashboard UI — Profiles tab

**Files:**
- Modify: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ContainersHtml.java`

**Step 1: Add nav item**

Add after Network nav item:
```html
<li class="nav-item" :class="{ 'active': currentTab === 'profiles' }"
    @click="currentTab = 'profiles'; showProfileForm = false; editingProfile = null">
  <span>&#9881;</span> Resource Profiles
</li>
```

**Step 2: Add profiles tab content**

Profiles tab with:
- List of profiles with CPU/Memory/Devices/GPU columns
- Edit/Delete/Use buttons
- Inline form for structured fields + "Show CLI" toggle revealing raw extraCliArgs

**Step 3: Add state and methods to Alpine component**

Add to `dashboard()` return object:
- `showProfileForm`, `editingProfile`, `profileUseTemplate` states
- `editProfile()`, `saveProfile()`, `deleteProfile()` methods
- Profile method similar to template methods

---

### Task 8: Add template profile selector

**Files:**
- Modify: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ContainersHtml.java`

In the template editor form, add:
```html
<div>
  <label>Resource Profile</label>
  <select x-model="editingTemplate ? editingTemplate.resourceProfileId : templateProfileId">
    <option value="">None</option>
    <template x-for="p in profiles" :key="p.id">
      <option :value="p.id" x-text="p.name"></option>
    </template>
  </select>
</div>
```

Also fetch profiles list into Alpine data when page loads.

---

### Task 9: Write tests

**Files:**
- Modify: `plugins/vatn-plugin-containers/src/test/java/dev/vatn/plugins/containers/ContainersPluginTest.java`

**Tests to add:**

1. `resourceProfileDefaults()` — verifies null/empty fields get defaults
2. `profileStoreSaveAndGet()` — save profile, load by ID, verify fields
3. `profileStoreListAndDelete()` — save 2, list size=2, delete one, list size=1
4. `profileStorePersistence()` — save then reload store, assert exists
5. `containerCreatorAppliesResourceArgs()` — create podman with profile, verify `--cpus --memory --device --gpus` in executed command
6. `containerCreatorUsesExtraCliArgs()` — profile with extraCliArgs, verify raw args appended
7. `containerCreatorSkipsMissingProfile()` — template references nonexistent profile, verify no crash and no resource args

---

### Task 10: Full build verification

Run: `mvn test -pl plugins/vatn-plugin-containers -am`
Expected: BUILD SUCCESS, all tests pass

Run: `mvn install -DskipTests`
Expected: BUILD SUCCESS
