package dev.vatn.core.native_image;

import org.pf4j.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Native-image-safe PluginManager that replaces DefaultPluginManager when VATN runs
 * as a GraalVM native binary.
 *
 * <p>Plugin support in native image falls into two paths:
 *
 * <ol>
 *   <li><b>Compiled-in plugins (IN_PROCESS_JVM)</b> — Plugin classes placed on the
 *       classpath at {@code native-image} build time are compiled into the binary.
 *       Register them at startup via {@code VRegistry.registerPlugin()} or
 *       {@code VNodeRunner.addPlugin()}.  The {@code getExtensions()} path is
 *       intentionally empty here; compiled-in plugins bypass PF4J and live in
 *       {@code VRegistry.manualPlugins}.</li>
 *   <li><b>Out-of-process plugins (OUT_OF_PROCESS_BIN / FFI_NATIVE)</b> — Each
 *       plugin is compiled separately as its own native binary (or shared library)
 *       and communicates with the VATN node through the existing OIPC/JSON IPC
 *       channel via {@code OipcProcessPluginProxy}.  This path is unchanged in
 *       native mode and requires no special wiring here.</li>
 * </ol>
 *
 * <p>Dynamic JAR scanning and PF4J's custom ClassLoaders are disabled because
 * GraalVM's closed-world assumption prevents runtime class loading from arbitrary
 * JARs.
 */
public final class NativeImagePluginManager implements PluginManager {

    private static final Logger log = LoggerFactory.getLogger(NativeImagePluginManager.class);

    public NativeImagePluginManager() {
        log.info("[VATN-NATIVE] PF4J dynamic JAR loading disabled. "
                + "Use VRegistry.registerPlugin() for compiled-in plugins, "
                + "or OUT_OF_PROCESS_BIN / FFI_NATIVE for separately compiled plugin binaries.");
    }

    @Override public void loadPlugins()  { /* JAR scan disabled in native image */ }
    @Override public void startPlugins() { /* no-op — compiled-in plugins are started by VNodeRunner */ }
    @Override public void stopPlugins()  { /* no-op */ }
    @Override public void unloadPlugins(){ /* no-op */ }
    @Override public void addPluginStateListener(PluginStateListener listener)    { /* no-op */ }
    @Override public void removePluginStateListener(PluginStateListener listener) { /* no-op */ }
    @Override public void setSystemVersion(String version) { /* no-op */ }

    @Override public String          loadPlugin(Path pluginPath)   { return null; }
    @Override public boolean         unloadPlugin(String pluginId) { return false; }
    @Override public boolean         disablePlugin(String pluginId){ return false; }
    @Override public boolean         enablePlugin(String pluginId) { return false; }
    @Override public boolean         deletePlugin(String pluginId) { return false; }
    @Override public ClassLoader     getPluginClassLoader(String pluginId) { return null; }
    @Override public ExtensionFactory getExtensionFactory()        { return null; }
    @Override public VersionManager  getVersionManager()           { return null; }
    @Override public PluginWrapper   getPlugin(String pluginId)    { return null; }
    @Override public PluginWrapper   whichPlugin(Class<?> clazz)   { return null; }
    @Override public String          getSystemVersion()            { return "0.0.0"; }
    @Override public Path            getPluginsRoot()              { return Path.of(System.getProperty("user.home"), ".vatn", "plugins"); }

    @Override public PluginState startPlugin(String pluginId) { return PluginState.STOPPED; }
    @Override public PluginState stopPlugin(String pluginId)  { return PluginState.STOPPED; }

    @Override public RuntimeMode getRuntimeMode() { return RuntimeMode.DEPLOYMENT; }

    @Override public List<PluginWrapper> getPlugins()                        { return Collections.emptyList(); }
    @Override public List<PluginWrapper> getPlugins(PluginState state)       { return Collections.emptyList(); }
    @Override public List<PluginWrapper> getResolvedPlugins()                { return Collections.emptyList(); }
    @Override public List<PluginWrapper> getUnresolvedPlugins()              { return Collections.emptyList(); }
    @Override public List<PluginWrapper> getStartedPlugins()                 { return Collections.emptyList(); }
    @Override public List<Path>          getPluginsRoots()                   { return Collections.emptyList(); }
    @Override public List<Class<?>>      getExtensionClasses(String pluginId){ return Collections.emptyList(); }
    @Override public Set<String>         getExtensionClassNames(String pluginId) { return Collections.emptySet(); }

    @Override
    public <T> List<Class<? extends T>> getExtensionClasses(Class<T> type) {
        return Collections.emptyList();
    }

    @Override
    public <T> List<Class<? extends T>> getExtensionClasses(Class<T> type, String pluginId) {
        return Collections.emptyList();
    }

    @Override
    public <T> List<T> getExtensions(Class<T> type) {
        return Collections.emptyList();
    }

    @Override
    public <T> List<T> getExtensions(Class<T> type, String pluginId) {
        return Collections.emptyList();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List getExtensions(String pluginId) {
        return Collections.emptyList();
    }
}
