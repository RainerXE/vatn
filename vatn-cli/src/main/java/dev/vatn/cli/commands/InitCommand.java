package dev.vatn.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "init", description = "Scaffolds a new VATN plugin project.")
public class InitCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Project directory name.")
    private String projectName;

    @Option(names = {"-l", "--lang"}, description = "Target language (python, java, rust).", defaultValue = "python")
    private String language;

    @Override
    public Integer call() throws Exception {
        Path projectPath = Path.of(projectName);
        if (Files.exists(projectPath)) {
            System.err.println("Error: Directory " + projectName + " already exists.");
            return 1;
        }

        System.out.println("✨ Scaffolding VATN [" + language + "] project in ./" + projectName + "...");
        Files.createDirectories(projectPath);
        Files.createDirectories(projectPath.resolve("tests"));

        // 1. Create vatn-plugin.json
        String entrypoint = switch (language.toLowerCase()) {
            case "python" -> "python3 main.py";
            case "java" -> "java -jar target/%s.jar".formatted(projectName);
            case "c" -> "./%s".formatted(projectName);
            case "rust" -> "target/debug/%s".formatted(projectName);
            default -> "python3 main.py";
        };

        String manifest = """
        {
          "id": "dev.example.%s",
          "name": "%s",
          "version": "1.0.0",
          "vatnVersion": ">=1.0.0",
          "execution": {
            "mode": "OUT_OF_PROCESS_BIN",
            "entrypoint": "%s"
          },
          "capabilities": [
            { "type": "FILE_READ", "channel": "/" },
            { "type": "NET_OUT", "channel": "*" }
          ]
        }
        """.formatted(projectName, projectName, entrypoint);
        Files.writeString(projectPath.resolve("vatn-plugin.json"), manifest);

        // 2. Language Specific Scaffolding
        switch (language.toLowerCase()) {
            case "python" -> scaffoldPython(projectPath);
            case "java" -> scaffoldJava(projectPath);
            case "c" -> scaffoldC(projectPath);
            case "rust" -> scaffoldRust(projectPath);
            default -> scaffoldPython(projectPath);
        }

        System.out.println("🎉 Project initialized!");
        System.out.println("👉 Next: cd " + projectName + " && vatn test vatn-plugin.json");
        return 0;
    }

    private void scaffoldPython(Path projectPath) throws Exception {
        String pythonCode = """
        import os, json, socket
        def main():
            port = int(os.environ.get("VATN_IPC_PORT", 8080))
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.bind(("127.0.0.1", port))
                s.listen()
                while True:
                    conn, _ = s.accept()
                    with conn:
                        data = conn.recv(1024)
                        if not data: break
                        req = json.loads(data.decode("utf-8"))
                        if req.get("type") == "STATUS_CHECK":
                            res = {"status": "HEALTHY", "id": "%s"}
                            conn.sendall(json.dumps(res).encode("utf-8"))
        if __name__ == "__main__": main()
        """.formatted(projectName);
        Files.writeString(projectPath.resolve("main.py"), pythonCode);

        String testCode = """
        import pytest
        def test_placeholder():
            assert True
        """;
        Files.writeString(projectPath.resolve("tests/test_plugin.py"), testCode);
    }

    private void scaffoldJava(Path projectPath) throws Exception {
        Path srcPath = projectPath.resolve("src/main/java/dev/example");
        Path testPath = projectPath.resolve("src/test/java/dev/example");
        Files.createDirectories(srcPath);
        Files.createDirectories(testPath);

        Files.writeString(projectPath.resolve("pom.xml"), """
        <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>dev.example</groupId>
            <artifactId>%s</artifactId>
            <version>1.0.0</version>
            <dependencies>
                <dependency>
                    <groupId>dev.vatn</groupId>
                    <artifactId>vatn-test</artifactId>
                    <version>1.0.0</version>
                    <scope>test</scope>
                </dependency>
            </dependencies>
        </project>
        """.formatted(projectName));

        Files.writeString(testPath.resolve("PluginTest.java"), """
        package dev.example;
        import org.junit.jupiter.api.Test;
        import dev.vatn.junit.VatnTest;
        import dev.vatn.api.VNodeContext;
        @VatnTest
        class PluginTest {
            @Test void testPlugin(VNodeContext ctx) { assert ctx != null; }
        }
        """);
    }

    private void scaffoldC(Path projectPath) throws Exception {
        Files.writeString(projectPath.resolve("main.c"), """
        #include <stdio.h>
        #include <stdlib.h>
        #include <string.h>
        #include <unistd.h>
        #include <arpa/inet.h>
        int main() {
            int port = atoi(getenv("VATN_IPC_PORT") ? getenv("VATN_IPC_PORT") : "8080");
            int s = socket(AF_INET, SOCK_STREAM, 0);
            struct sockaddr_in addr = { .sin_family = AF_INET, .sin_port = htons(port), .sin_addr.s_addr = INADDR_ANY };
            bind(s, (struct sockaddr *)&addr, sizeof(addr));
            listen(s, 1);
            while(1) {
                int c = accept(s, NULL, NULL);
                char buf[1024] = {0};
                read(c, buf, 1024);
                if (strstr(buf, "STATUS_CHECK")) {
                    char *res = "{\\"status\\": \\"HEALTHY\\"}";
                    send(c, res, strlen(res), 0);
                }
                close(c);
            }
            return 0;
        }
        """);
        Files.writeString(projectPath.resolve("Makefile"), "all:\n\tgcc main.c -o %s\n".formatted(projectName));
    }

    private void scaffoldRust(Path projectPath) throws Exception {
        Files.writeString(projectPath.resolve("Cargo.toml"), """
        [package]
        name = "%s"
        version = "0.1.0"
        edition = "2021"
        [dependencies]
        serde_json = "1.0"
        """.formatted(projectName));
        Path src = projectPath.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("main.rs"), """
        use std::net::{TcpListener, TcpStream};
        use std::io::{Read, Write};
        fn main() {
            let port = std::env::var("VATN_IPC_PORT").unwrap_or("8080".to_string());
            let listener = TcpListener::bind(format!("127.0.0.1:{}", port)).unwrap();
            for stream in listener.incoming() {
                let mut stream = stream.unwrap();
                let mut buf = [0; 1024];
                stream.read(&mut buf).unwrap();
                let msg = String::from_utf8_lossy(&buf);
                if msg.contains("STATUS_CHECK") {
                    stream.write_all(b"{\\"status\\": \\"HEALTHY\\"}").unwrap();
                }
            }
        }
        """);
    }
}
