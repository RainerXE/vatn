package dev.vatn.cli;

import picocli.CommandLine.IVersionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class VatnVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        Properties props = new Properties();
        try (InputStream is = getClass().getResourceAsStream("/vatn/version.properties")) {
            if (is != null) {
                props.load(is);
                String v = props.getProperty("version", "0.0.0");
                return new String[]{"VATN Runtime " + v};
            }
        } catch (IOException e) {
            // fall through
        }
        return new String[]{"VATN Runtime (unknown version)"};
    }
}
