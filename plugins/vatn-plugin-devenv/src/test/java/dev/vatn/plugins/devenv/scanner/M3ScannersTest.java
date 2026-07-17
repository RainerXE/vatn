package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.ContainerEntry;
import dev.vatn.plugins.devenv.model.ContainerImage;
import dev.vatn.plugins.devenv.model.VenvEntry;
import dev.vatn.plugins.devenv.model.VenvType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M3ScannersTest {

    // -- VenvScanner ---------------------------------------------------------------------

    @Test
    void parsesPyvenvCfgAndDetectsType() {
        Map<String, String> kv = VenvScanner.parsePyvenvCfg(List.of(
                "home = /opt/homebrew/opt/python@3.12/bin",
                "version = 3.12.2",
                "include-system-site-packages = false"));
        assertEquals("3.12.2", kv.get("version"));
        assertEquals(VenvType.VENV, VenvScanner.detectType(kv));
        assertEquals(VenvType.UV, VenvScanner.detectType(Map.of("uv", "0.1")));
    }

    @Test
    void walkFindsVenvByPyvenvCfg(@TempDir Path tmp) throws Exception {
        Path venv = tmp.resolve("proj/.venv");
        Files.createDirectories(venv);
        Files.writeString(venv.resolve("pyvenv.cfg"), "home = /usr/bin\nversion = 3.11.7\n");
        // a pruned dir that must be ignored
        Path noise = tmp.resolve("proj/node_modules/x/.venv");
        Files.createDirectories(noise);
        Files.writeString(noise.resolve("pyvenv.cfg"), "version = 9.9.9\n");

        List<VenvEntry> found = VenvScanner.walk(List.of(tmp), 6);
        assertEquals(1, found.size(), "node_modules venv must be pruned");
        assertEquals("3.11.7", found.get(0).pythonVersion());
        assertEquals(VenvType.VENV, found.get(0).type());
    }

    @Test
    void parsesCondaEnvList() {
        List<VenvEntry> envs = VenvScanner.parseCondaEnvList(
                "# conda environments:\nbase                  *  /opt/miniconda3\nml                       /opt/miniconda3/envs/ml\n");
        assertEquals(2, envs.size());
        assertEquals(VenvType.CONDA, envs.get(0).type());
        assertTrue(envs.get(1).path().endsWith("/ml"));
    }

    // -- ContainerScanner ----------------------------------------------------------------

    @Test
    void parsesContainerPsAndImages() {
        List<ContainerEntry> c = ContainerScanner.parseContainers(
                "abc123|web|nginx:latest|Up 2 hours|0.0.0.0:80->80/tcp\n"
                        + "def456|db|postgres:16|Exited (0) 1 day ago|\n");
        assertEquals(2, c.size());
        assertEquals("web", c.get(0).name());
        assertEquals("0.0.0.0:80->80/tcp", c.get(0).ports());
        assertEquals("", c.get(1).ports());

        List<ContainerImage> img = ContainerScanner.parseImages(
                "nginx|latest|187MB|3 weeks ago\nubuntu|22.04|77.8MB|2 months ago\n");
        assertEquals(2, img.size());
        assertEquals("nginx", img.get(0).name());
        assertEquals("187MB", img.get(0).size());
    }

    // -- KubernetesScanner ---------------------------------------------------------------

    @Test
    void parsesKubeContexts() {
        List<String> ctx = KubernetesScanner.parseContexts("docker-desktop\nminikube\n\nprod-cluster\n");
        assertEquals(List.of("docker-desktop", "minikube", "prod-cluster"), ctx);
    }
}
