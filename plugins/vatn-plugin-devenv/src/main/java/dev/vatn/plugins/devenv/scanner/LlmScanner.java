package dev.vatn.plugins.devenv.scanner;

import dev.vatn.api.VJson;
import dev.vatn.plugins.devenv.model.EngineKind;
import dev.vatn.plugins.devenv.model.LlmApiType;
import dev.vatn.plugins.devenv.model.LlmEngineEntry;
import dev.vatn.plugins.devenv.model.LlmInventory;
import dev.vatn.plugins.devenv.model.LlmModelEntry;
import dev.vatn.plugins.devenv.model.ModelFormat;
import dev.vatn.plugins.devenv.model.RuntimeSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Local LLM inference engines + models. Engine detection is <em>data-driven</em> (a list of
 * {@link EngineSpec}) so a user config file can add engines without code changes; models are read
 * filesystem-first (Ollama manifests, Hugging Face cache, GGUF files) with cheap loopback
 * liveness probes. No model weights are loaded; the Hugging Face token is never read.
 */
public final class LlmScanner {

    /** How to detect one engine. Built-in below; user config contributes more. */
    public record EngineSpec(String name, EngineKind kind, List<String> binaries,
                             List<String> apps, String endpoint, LlmApiType apiType) {}

    /**
     * Named model-directory scan target. Built-in well-known apps use this; users can also
     * contribute via {@code llm.appScans[]} in {@code ~/.vatn/devenv.json}. All supported formats
     * (GGUF, SAFETENSORS, ONNX, CoreML bundles) are detected; found models are labelled with
     * {@code label} as their engine/source name.
     */
    public record AppScan(String label, List<String> modelDirs) {}

    /** Well-known AI apps with embedded models, auto-detected by directory presence. */
    private static List<AppScan> wellKnownApps() {
        var apps = new ArrayList<AppScan>(List.of(
                new AppScan("ComfyUI",             List.of("~/ComfyUI/models", "~/comfyui/models")),
                new AppScan("StableDiffusionWebUI", List.of("~/stable-diffusion-webui/models",
                                                            "~/automatic1111/models")),
                new AppScan("InvokeAI",            List.of("~/.invokeai/models", "~/invokeai/models")),
                new AppScan("Fooocus",             List.of("~/Fooocus/models")),
                new AppScan("SwarmUI",             List.of("~/SwarmUI/Models")),
                new AppScan("AnythingLLM",         List.of(
                        "~/Library/Application Support/anythingllm-desktop/models",  // macOS
                        "~/.config/anythingllm-desktop/models"))                     // Linux
        ));
        if (ScannerUtil.isMacOS()) {
            // Apple Intelligence on-device models (macOS 15+)
            apps.add(new AppScan("Apple Intelligence",
                    List.of("~/Library/Application Support/com.apple.Foundation.ModelFramework")));
            // WhisperKit — on-device speech recognition
            apps.add(new AppScan("WhisperKit",
                    List.of("~/Library/Application Support/argmax/WhisperKit")));
        }
        return List.copyOf(apps);
    }

    private static final List<EngineSpec> BUILTIN = List.of(
            new EngineSpec("ollama", EngineKind.SERVER, List.of("ollama"), List.of("Ollama"),
                    "http://127.0.0.1:11434", LlmApiType.OLLAMA),
            new EngineSpec("lmstudio", EngineKind.APP, List.of("lms"), List.of("LM Studio"),
                    "http://127.0.0.1:1234/v1", LlmApiType.OPENAI_COMPAT),
            new EngineSpec("llama.cpp", EngineKind.SERVER, List.of("llama-server", "llama-cli", "main"), List.of(),
                    "http://127.0.0.1:8080/v1", LlmApiType.OPENAI_COMPAT),
            new EngineSpec("vllm", EngineKind.SERVER, List.of("vllm"), List.of(),
                    "http://127.0.0.1:8000/v1", LlmApiType.OPENAI_COMPAT),
            new EngineSpec("jan", EngineKind.APP, List.of(), List.of("Jan"),
                    "http://127.0.0.1:1337/v1", LlmApiType.OPENAI_COMPAT),
            new EngineSpec("gpt4all", EngineKind.APP, List.of(), List.of("GPT4All"), "", LlmApiType.NONE),
            new EngineSpec("localai", EngineKind.SERVER, List.of("local-ai", "localai"), List.of(),
                    "http://127.0.0.1:8080/v1", LlmApiType.OPENAI_COMPAT),
            new EngineSpec("koboldcpp", EngineKind.SERVER, List.of("koboldcpp"), List.of(),
                    "http://127.0.0.1:5001/v1", LlmApiType.OPENAI_COMPAT),
            new EngineSpec("tabby", EngineKind.SERVER, List.of("tabby"), List.of(), "", LlmApiType.CUSTOM),
            new EngineSpec("whisper.cpp", EngineKind.CLI, List.of("whisper-cli", "whisper"), List.of(),
                    "", LlmApiType.NONE));

    private final ScannerUtil util;
    private final VJson json;
    private final List<EngineSpec> extraEngines;
    private final List<Path> extraModelDirs;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();

    public LlmScanner(ScannerUtil util, VJson json) {
        this(util, json, List.of(), List.of());
    }

    /** @param extraEngines/extraModelDirs user-config additions (see DevEnvConfig). */
    public LlmScanner(ScannerUtil util, VJson json, List<EngineSpec> extraEngines, List<Path> extraModelDirs) {
        this.util = util;
        this.json = json;
        this.extraEngines = extraEngines;
        this.extraModelDirs = extraModelDirs;
    }

    public LlmInventory scan() {
        boolean gpu = ScannerUtil.isMacOS()
                || util.which("nvidia-smi").isPresent() || util.which("rocminfo").isPresent();

        var engines = new ArrayList<LlmEngineEntry>();
        var running = new ArrayList<String>();
        var allSpecs = new ArrayList<>(BUILTIN);
        allSpecs.addAll(extraEngines);
        allSpecs.addAll(loadConfigEngines()); // user-declared engines (framework config file)
        for (EngineSpec spec : allSpecs) {
            detectEngine(spec, gpu).ifPresent(e -> {
                engines.add(e);
                if (e.running() && !e.endpoint().isBlank()) running.add(e.endpoint());
            });
        }

        var models = new ArrayList<LlmModelEntry>();
        models.addAll(scanOllama());
        models.addAll(scanHuggingFace());
        models.addAll(scanGguf());

        // Well-known embedded AI apps + user-configured app scans
        var appScans = new ArrayList<AppScan>(wellKnownApps());
        appScans.addAll(loadConfigAppScans());
        for (AppScan app : appScans) {
            for (String dirStr : app.modelDirs()) {
                Path dir = expandPath(dirStr);
                if (ScannerUtil.isDir(dir)) models.addAll(scanAppDir(dir, app.label(), 6));
            }
        }
        deduplicateByLocation(models);

        long total = models.stream().mapToLong(m -> Math.max(0, m.sizeBytes())).sum();
        return new LlmInventory(List.copyOf(engines), List.copyOf(models), List.copyOf(running), total);
    }

    // -- user config (framework extensibility) --------------------------------------------
    //
    // ~/.vatn/devenv.json (or $VATN_DEVENV_CONFIG) lets users scan for engines/models the
    // built-ins don't cover, e.g.:
    //   { "llm": {
    //       "engines": [ { "name":"my-svc", "kind":"SERVER", "binaries":["mysvc"],
    //                      "apps":[], "endpoint":"http://127.0.0.1:9000/v1", "apiType":"OPENAI_COMPAT" } ],
    //       "modelDirs": [ "/data/models" ] } }

    private List<EngineSpec> loadConfigEngines() {
        return configLlmNode().map(LlmScanner::parseEngineSpecs).orElse(List.of());
    }

    private List<Path> loadConfigModelDirs() {
        return configLlmNode().map(LlmScanner::parseModelDirs).orElse(List.of());
    }

    private java.util.Optional<Map<?, ?>> configLlmNode() {
        Path cfg = configPath();
        if (!ScannerUtil.isFile(cfg)) return java.util.Optional.empty();
        try {
            Map<?, ?> root = json.parse(Files.readString(cfg), Map.class);
            return root.get("llm") instanceof Map<?, ?> m ? java.util.Optional.of(m) : java.util.Optional.empty();
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    private static Path configPath() {
        String env = System.getenv("VATN_DEVENV_CONFIG");
        return (env != null && !env.isBlank()) ? Path.of(env) : ScannerUtil.homeDir(".vatn/devenv.json");
    }

    /** Parse {@code llm.engines[]} from a parsed config node. */
    static List<EngineSpec> parseEngineSpecs(Map<?, ?> llm) {
        if (!(llm.get("engines") instanceof List<?> list)) return List.of();
        var out = new ArrayList<EngineSpec>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> e && e.get("name") != null) {
                out.add(new EngineSpec(str(e, "name"), parseKind(str(e, "kind")),
                        strList(e.get("binaries")), strList(e.get("apps")),
                        str(e, "endpoint"), parseApi(str(e, "apiType"))));
            }
        }
        return out;
    }

    /** Parse {@code llm.modelDirs[]} from a parsed config node. */
    static List<Path> parseModelDirs(Map<?, ?> llm) {
        return strList(llm.get("modelDirs")).stream().map(LlmScanner::expandPath).toList();
    }

    /**
     * Parse {@code llm.appScans[]} — named model-directory scan targets.
     * <pre>
     *  "appScans": [ { "label": "OdinLLM", "modelDirs": ["~/ai-models"] } ]
     * </pre>
     */
    static List<AppScan> parseAppScans(Map<?, ?> llm) {
        if (!(llm.get("appScans") instanceof List<?> list)) return List.of();
        var out = new ArrayList<AppScan>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> e && e.get("label") != null)
                out.add(new AppScan(str(e, "label"), strList(e.get("modelDirs"))));
        }
        return List.copyOf(out);
    }

    private List<AppScan> loadConfigAppScans() {
        return configLlmNode().map(LlmScanner::parseAppScans).orElse(List.of());
    }

    /** Expand {@code ~/…} to the user's home directory; absolute paths are returned unchanged. */
    static Path expandPath(String s) {
        if (s.startsWith("~/")) return Path.of(System.getProperty("user.home")).resolve(s.substring(2));
        if ("~".equals(s)) return Path.of(System.getProperty("user.home"));
        return Path.of(s);
    }

    private static String str(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static List<String> strList(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        var out = new ArrayList<String>();
        for (Object v : list) if (v != null) out.add(v.toString());
        return List.copyOf(out);
    }

    private static EngineKind parseKind(String s) {
        try { return s.isBlank() ? EngineKind.SERVER : EngineKind.valueOf(s.toUpperCase()); }
        catch (Exception e) { return EngineKind.SERVER; }
    }

    private static LlmApiType parseApi(String s) {
        try { return s.isBlank() ? LlmApiType.OPENAI_COMPAT : LlmApiType.valueOf(s.toUpperCase()); }
        catch (Exception e) { return LlmApiType.OPENAI_COMPAT; }
    }

    // -- engines --------------------------------------------------------------------------

    private java.util.Optional<LlmEngineEntry> detectEngine(EngineSpec spec, boolean gpu) {
        String path = null;
        EngineKind kind = spec.kind();
        RuntimeSource source = RuntimeSource.PATH;
        String version = "";

        for (String bin : spec.binaries()) {
            var p = util.which(bin);
            if (p.isPresent()) {
                path = p.get();
                source = RuntimeScanner.detectSource(path);
                kind = spec.kind() == EngineKind.APP ? EngineKind.CLI : spec.kind();
                version = util.exec(bin, "--version").map(ScannerUtil::extractVersion).orElse("");
                break;
            }
        }
        if (path == null) {
            for (String app : spec.apps()) {
                Path bundle = Path.of("/Applications/" + app + ".app");
                if (ScannerUtil.isDir(bundle)) { path = bundle.toString(); source = RuntimeSource.SYSTEM; kind = EngineKind.APP; break; }
            }
        }
        if (path == null) return java.util.Optional.empty();

        boolean up = probeRunning(spec);
        return java.util.Optional.of(new LlmEngineEntry(spec.name(), kind, version, path, source,
                spec.endpoint(), up, spec.apiType(), gpu));
    }

    /** Short loopback liveness probe; true if the endpoint responds at all. */
    private boolean probeRunning(EngineSpec spec) {
        if (spec.endpoint() == null || spec.endpoint().isBlank()) return false;
        String url = switch (spec.apiType()) {
            case OLLAMA -> spec.endpoint() + "/api/tags";
            case OPENAI_COMPAT -> spec.endpoint() + "/models";
            default -> spec.endpoint();
        };
        try {
            HttpResponse<Void> r = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(1200))
                            .GET().build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode() > 0 && r.statusCode() < 500; // any response = something is serving
        } catch (Exception e) {
            return false;
        }
    }

    // -- models: Ollama -------------------------------------------------------------------

    private List<LlmModelEntry> scanOllama() {
        Path manifests = ScannerUtil.homeDir(".ollama/models/manifests");
        if (!ScannerUtil.isDir(manifests)) return List.of();
        var out = new ArrayList<LlmModelEntry>();
        try (Stream<Path> s = Files.walk(manifests)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(tag -> out.add(ollamaModel(manifests, tag)));
        } catch (IOException ignored) { }
        return out;
    }

    private LlmModelEntry ollamaModel(Path manifestsRoot, Path tagFile) {
        // .../manifests/<registry>/<namespace>/<model>/<tag>
        Path rel = manifestsRoot.relativize(tagFile);
        int n = rel.getNameCount();
        String model = n >= 2 ? rel.getName(n - 2).toString() : tagFile.getFileName().toString();
        String tag = rel.getName(n - 1).toString();
        String ns = n >= 3 ? rel.getName(n - 3).toString() : "library";
        String id = ("library".equals(ns) ? model : ns + "/" + model) + ":" + tag;

        long size = ollamaManifestSize(tagFile);
        String hint = model + " " + tag;
        return new LlmModelEntry(id, deriveFamily(hint), deriveParams(hint), deriveQuant(hint),
                ModelFormat.OLLAMA, size, tagFile.toString(), "ollama");
    }

    /** Sum config + layer sizes from an Ollama manifest JSON (no blob reads). */
    private long ollamaManifestSize(Path tagFile) {
        try {
            Map<?, ?> m = json.parse(Files.readString(tagFile), Map.class);
            long total = sizeOf(m.get("config"));
            if (m.get("layers") instanceof List<?> layers) {
                for (Object l : layers) total += sizeOf(l);
            }
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long sizeOf(Object node) {
        return node instanceof Map<?, ?> mm && mm.get("size") instanceof Number num ? num.longValue() : 0L;
    }

    // -- models: Hugging Face cache -------------------------------------------------------

    private List<LlmModelEntry> scanHuggingFace() {
        String hfHome = System.getenv("HF_HOME");
        Path hub = (hfHome != null && !hfHome.isBlank() ? Path.of(hfHome) : ScannerUtil.homeDir(".cache/huggingface"))
                .resolve("hub");
        if (!ScannerUtil.isDir(hub)) return List.of();
        var out = new ArrayList<LlmModelEntry>();
        for (Path dir : ScannerUtil.subdirs(hub)) {
            String name = dir.getFileName().toString();
            if (!name.startsWith("models--")) continue;
            String repo = name.substring("models--".length()).replace("--", "/");
            ModelFormat fmt = hfFormat(repo, dir);
            out.add(new LlmModelEntry(repo, deriveFamily(repo), deriveParams(repo), deriveQuant(repo),
                    fmt, hfSize(dir), repo, "huggingface"));
        }
        return out;
    }

    private static ModelFormat hfFormat(String repo, Path dir) {
        String r = repo.toLowerCase();
        if (r.startsWith("mlx-community/") || r.contains("-mlx") || r.contains("mlx-")) return ModelFormat.MLX;
        if (hasExt(dir, ".gguf")) return ModelFormat.GGUF;
        if (hasExt(dir, ".safetensors")) return ModelFormat.SAFETENSORS;
        return ModelFormat.UNKNOWN;
    }

    private static boolean hasExt(Path dir, String ext) {
        try (Stream<Path> s = Files.walk(dir, 4)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(ext));
        } catch (IOException e) {
            return false;
        }
    }

    // -- models: loose GGUF files ---------------------------------------------------------

    private List<LlmModelEntry> scanGguf() {
        var roots = new ArrayList<Path>(List.of(
                ScannerUtil.homeDir(".lmstudio/models"),
                ScannerUtil.homeDir(".cache/lm-studio/models"),
                ScannerUtil.homeDir("models")));
        roots.addAll(extraModelDirs);
        roots.addAll(loadConfigModelDirs()); // user-declared model dirs (framework config file)
        var out = new ArrayList<LlmModelEntry>();
        for (Path root : roots) {
            if (!ScannerUtil.isDir(root)) continue;
            try (Stream<Path> s = Files.walk(root, 6)) {
                s.filter(p -> p.getFileName().toString().endsWith(".gguf"))
                        .filter(LlmScanner::isGguf)
                        .forEach(g -> {
                            String fn = g.getFileName().toString();
                            out.add(new LlmModelEntry(fn, deriveFamily(fn), deriveParams(fn), deriveQuant(fn),
                                    ModelFormat.GGUF, fileSize(g), g.toString(), "gguf"));
                        });
            } catch (IOException ignored) { }
        }
        return out;
    }

    /** Confirm a file really is GGUF by its 4-byte magic. */
    static boolean isGguf(Path p) {
        try {
            byte[] b = new byte[4];
            try (var in = Files.newInputStream(p)) {
                return in.read(b) == 4 && b[0] == 'G' && b[1] == 'G' && b[2] == 'U' && b[3] == 'F';
            }
        } catch (IOException e) {
            return false;
        }
    }

    // -- models: embedded AI-app directories (ComfyUI, InvokeAI, etc.) -------------------

    /**
     * Walk {@code dir} up to {@code maxDepth} levels, collect GGUF / SafeTensors / ONNX / MLX
     * bundle entries, and label each with the given {@code label} as the source engine name.
     */
    private List<LlmModelEntry> scanAppDir(Path dir, String label, int maxDepth) {
        var out = new ArrayList<LlmModelEntry>();
        try (Stream<Path> s = Files.walk(dir, maxDepth)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String fn = p.getFileName().toString().toLowerCase();
                ModelFormat fmt = null;
                if (fn.endsWith(".gguf") && isGguf(p)) fmt = ModelFormat.GGUF;
                else if (fn.endsWith(".safetensors"))              fmt = ModelFormat.SAFETENSORS;
                else if (fn.endsWith(".onnx"))                     fmt = ModelFormat.ONNX;
                if (fmt != null) {
                    String name = p.getFileName().toString();
                    out.add(new LlmModelEntry(name, deriveFamily(name), deriveParams(name),
                            deriveQuant(name), fmt, fileSize(p), p.toString(), label));
                }
            });
        } catch (IOException ignored) { }
        // MLX model bundles are directories — check immediate subdirs for config.json
        try (Stream<Path> s = Files.walk(dir, maxDepth)) {
            s.filter(Files::isDirectory)
             .filter(d -> Files.exists(d.resolve("config.json")))
             .filter(d -> !d.equals(dir))
             .forEach(d -> {
                 String name = d.getFileName().toString();
                 out.add(new LlmModelEntry(name, deriveFamily(name), deriveParams(name),
                         deriveQuant(name), ModelFormat.MLX, sumRegularFiles(d), d.toString(), label));
             });
        } catch (IOException ignored) { }
        return out;
    }

    /**
     * Remove duplicate {@link LlmModelEntry} records that share the same {@code location} path,
     * keeping only the first occurrence. Mutates {@code models} in-place.
     */
    private static void deduplicateByLocation(ArrayList<LlmModelEntry> models) {
        var seen = new LinkedHashSet<String>();
        models.removeIf(m -> !seen.add(m.location()));
    }

    // -- filename/name heuristics (static, testable) --------------------------------------


    private static final List<String> FAMILIES = List.of(
            "llama", "qwen", "gemma", "mistral", "mixtral", "phi", "deepseek", "codellama",
            "starcoder", "falcon", "vicuna", "yi", "command-r", "granite", "smollm", "tinyllama",
            "stablelm", "orca", "nemotron", "moshi", "flux", "clip", "snac", "opt",
            "sdxl", "stable-diffusion", "dreamshaper", "whisper");
    private static final Pattern PARAMS = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*[xX]?\\s*(\\d+(?:\\.\\d+)?)?\\s*([bBmMkK])\\b");
    private static final Pattern QUANT = Pattern.compile("(?i)(Q\\d+(?:_[A-Z0-9]+)*|IQ\\d+[A-Z0-9_]*|f16|fp16|bf16|f32|fp32|\\d+bit|int\\d+)");

    static String deriveFamily(String name) {
        String h = name.toLowerCase();
        for (String f : FAMILIES) if (h.contains(f)) return f;
        return "";
    }

    static String deriveParams(String name) {
        Matcher m = PARAMS.matcher(name);
        while (m.find()) {
            String unit = m.group(3).toUpperCase();
            if (unit.equals("B") || unit.equals("M")) { // ignore K (e.g. 260K toy) less useful, but keep B/M
                String moe = m.group(2) != null ? m.group(1) + "x" + m.group(2) : m.group(1);
                return moe + unit;
            }
        }
        return "";
    }

    static String deriveQuant(String name) {
        Matcher m = QUANT.matcher(name);
        return m.find() ? m.group(1) : "";
    }

    // -- fs sizes -------------------------------------------------------------------------

    /**
     * On-disk size of a Hugging Face model. Sums real files across the whole model dir with
     * NOFOLLOW — counting blobs/ (the usual layout) and any real files in snapshots/ (symlink-free
     * downloads) exactly once, while skipping the snapshot symlinks. Resilient to broken symlinks.
     */
    private static long hfSize(Path modelDir) {
        return sumRegularFiles(modelDir);
    }

    /** Sum regular files under a dir, resilient to broken symlinks / unreadable entries. */
    private static long sumRegularFiles(Path dir) {
        long[] total = {0};
        try (Stream<Path> s = Files.walk(dir)) {
            s.forEach(p -> {
                try {
                    if (Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) total[0] += Files.size(p);
                } catch (Exception ignored) { }
            });
        } catch (Exception e) {
            return total[0] > 0 ? total[0] : -1;
        }
        return total[0];
    }

    private static long fileSize(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0L; }
    }
}
