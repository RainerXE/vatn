package dev.vatn.verify;

import dev.vatn.core.VNodeRunner;
import dev.vatn.api.VJson;
import dev.vatn.api.VStream;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class PleasantriesTest {

    @Test
    public void testJsonService() {
        VNodeRunner runner = VNodeRunner.create(0);
        runner.start();
        VJson json = runner.getContext().getJson();

        // 1. Stringify
        TestObject obj = new TestObject("VATN", 2026);
        String jsonStr = json.stringify(obj);
        assertTrue(jsonStr.contains("\"name\":\"VATN\""));

        // 2. Parse
        TestObject parsed = json.parse(jsonStr, TestObject.class);
        assertEquals("VATN", parsed.name);
        assertEquals(2026, parsed.year);

        // 3. Path query
        String name = json.path(jsonStr, "name", String.class);
        assertEquals("VATN", name);

        runner.stop();
    }

    @Test
    public void testEnvLoading() throws IOException {
        // Create a temporary .env file
        Files.write(Paths.get(".env"), "TEST_KEY=VATN_ROX\n# Comment\nANOTHER_KEY=\"QUOTED_VALUE\"".getBytes());

        VNodeRunner runner = VNodeRunner.create(0);
        runner.start();
        
        String val1 = runner.getContext().getConfiguration().get("TEST_KEY").orElse("");
        String val2 = runner.getContext().getConfiguration().get("ANOTHER_KEY").orElse("");

        assertEquals("VATN_ROX", val1);
        assertEquals("QUOTED_VALUE", val2);

        runner.stop();
        Files.delete(Paths.get(".env"));
    }

    @Test
    public void testPiping() throws Exception {
        VNodeRunner runner = VNodeRunner.create(0);
        runner.start();
        VStream vStream = runner.getContext().getStream();

        String myData = "This is a high-volume stream of facts about VATN.";
        try (OutputStream out = vStream.createOutput("test-stream");
             InputStream in = vStream.openInput("test-stream")) {

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        
        // Start piping in a virtual thread
        vStream.pipe(in, result);

        // Feed data
        out.write(myData.getBytes());
        out.close();

        // Wait for result (piping is async)
        Thread.sleep(100); 

        }
    }

    public static class TestObject {
        public String name;
        public int year;
        public TestObject() {}
        public TestObject(String name, int year) {
            this.name = name;
            this.year = year;
        }
    }
}
