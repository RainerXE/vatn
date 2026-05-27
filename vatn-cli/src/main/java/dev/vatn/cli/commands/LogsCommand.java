package dev.vatn.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(name = "logs", description = "Tails the aggregated logs from all supervised plugins.")
public class LogsCommand implements Callable<Integer> {

    @Option(names = {"-f", "--follow"}, description = "Follow the log output (tail -f).", defaultValue = "false")
    private boolean follow;

    @Override
    public Integer call() throws Exception {
        Path logPath = Paths.get(System.getProperty("user.home"), ".vatn", "logs", "node.log");
        if (!java.nio.file.Files.exists(logPath)) {
            System.err.println("No logs found at " + logPath);
            return 1;
        }

        System.out.println("📄 Tailng VATN logs: " + logPath);
        
        if (!follow) {
            // Just cat the last 50 lines
            java.util.List<String> lines = java.nio.file.Files.readAllLines(logPath);
            int start = Math.max(0, lines.size() - 50);
            for (int i = start; i < lines.size(); i++) {
                System.out.println(lines.get(i));
            }
            return 0;
        }

        // Tail -f implementation
        try (RandomAccessFile reader = new RandomAccessFile(logPath.toFile(), "r")) {
            long lastPointer = Math.max(0, reader.length() - 1024); // Start near the end
            reader.seek(lastPointer);

            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    Thread.sleep(100);
                    continue;
                }
                System.out.println(line);
                lastPointer = reader.getFilePointer();
            }
        }
    }
}
