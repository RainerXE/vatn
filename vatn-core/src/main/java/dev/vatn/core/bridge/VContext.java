package dev.vatn.core.bridge;

import org.graalvm.nativeimage.c.CContext;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;

public class VContext implements CContext.Directives {

    @Override
    public List<String> getHeaderFiles() {
        // Try filesystem paths first (works when building from project root or vatn-core)
        List<Path> candidates = List.of(
            Paths.get("vatn-core/src/main/resources/cpp/vatn.h"),
            Paths.get("src/main/resources/cpp/vatn.h"),
            Paths.get("../vatn-core/src/main/resources/cpp/vatn.h"),
            Paths.get("../../vatn-core/src/main/resources/cpp/vatn.h")
        );
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Collections.singletonList("\"" + candidate.toAbsolutePath() + "\"");
            }
        }

        // Fall back: extract vatn.h from classpath resource to a temp file.
        // This handles native-image builds where the JAR is on the classpath
        // but the source tree is not reachable from the working directory.
        try (InputStream is = VContext.class.getResourceAsStream("/cpp/vatn.h")) {
            if (is != null) {
                Path tmp = Files.createTempFile("vatn", ".h");
                tmp.toFile().deleteOnExit();
                Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
                return Collections.singletonList("\"" + tmp.toAbsolutePath() + "\"");
            }
        } catch (Exception ignored) {
            // fall through to stdint.h fallback
        }

        return Collections.singletonList("<stdint.h>");
    }

    @Override
    public List<String> getMacroDefinitions() {
        return Collections.emptyList();
    }
}
