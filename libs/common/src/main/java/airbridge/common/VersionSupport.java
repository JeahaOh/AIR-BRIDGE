package airbridge.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

public final class VersionSupport {
    private static final String DEFAULT_VERSION = "0.0.0";
    private static final String VERSION = loadVersion();

    private VersionSupport() {
    }

    public static String version() {
        return VERSION;
    }

    public static String displayVersion(String appName) {
        return Objects.requireNonNull(appName, "appName") + " " + VERSION;
    }

    private static String loadVersion() {
        Properties properties = new Properties();
        try (InputStream input = VersionSupport.class.getClassLoader().getResourceAsStream("qe-version.properties")) {
            if (input == null) {
                return DEFAULT_VERSION;
            }
            properties.load(input);
            return properties.getProperty("version", DEFAULT_VERSION).trim();
        } catch (IOException e) {
            return DEFAULT_VERSION;
        }
    }
}
