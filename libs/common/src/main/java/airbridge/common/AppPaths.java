package airbridge.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Resolves default working directories relative to the running jar, so commands and the GUI
 * can be used without spelling out --in/--out every time. The default pipeline is:
 *
 * <pre>
 *   encode( source -&gt; encoded ) -&gt; slide( encoded ) -&gt; capture( -&gt; captured ) -&gt; decode( captured -&gt; decoded )
 * </pre>
 *
 * The base directory is the folder containing the application jar (falling back to the process
 * working directory in IDE/test runs). The directory <em>names</em> are configurable via
 * {@code airbridge-paths.properties}: bundled defaults are loaded from the classpath, and an
 * optional file of the same name placed next to the jar overrides them at runtime — so an
 * operator can rename the folders without rebuilding.
 */
public final class AppPaths {
    public static final String CONFIG_FILE = "airbridge-paths.properties";

    private static final String KEY_SOURCE = "dir.source";
    private static final String KEY_ENCODED = "dir.encoded";
    private static final String KEY_CAPTURED = "dir.captured";
    private static final String KEY_DECODED = "dir.decoded";

    private static final Properties NAMES = loadNames();

    private AppPaths() {
    }

    public static Path baseDirectory() {
        try {
            var codeSource = AppPaths.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                Path location = Paths.get(codeSource.getLocation().toURI());
                if (Files.isRegularFile(location)) {
                    // Running from a jar: use the directory that contains the jar.
                    Path parent = location.getParent();
                    if (parent != null) {
                        return parent.toAbsolutePath().normalize();
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back to the working directory below.
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    public static String sourceDirName() {
        return NAMES.getProperty(KEY_SOURCE);
    }

    public static String encodedDirName() {
        return NAMES.getProperty(KEY_ENCODED);
    }

    public static String capturedDirName() {
        return NAMES.getProperty(KEY_CAPTURED);
    }

    public static String decodedDirName() {
        return NAMES.getProperty(KEY_DECODED);
    }

    public static Path sourceDir() {
        return resolve(sourceDirName());
    }

    public static Path encodedDir() {
        return resolve(encodedDirName());
    }

    public static Path capturedDir() {
        return resolve(capturedDirName());
    }

    public static Path decodedDir() {
        return resolve(decodedDirName());
    }

    /** Resolves {@code name} under {@link #baseDirectory()}. */
    public static Path resolve(String name) {
        return baseDirectory().resolve(name).toAbsolutePath().normalize();
    }

    private static Properties loadNames() {
        Properties properties = new Properties();
        // 1) hardcoded fallbacks
        properties.setProperty(KEY_SOURCE, "source");
        properties.setProperty(KEY_ENCODED, "encoded");
        properties.setProperty(KEY_CAPTURED, "captured");
        properties.setProperty(KEY_DECODED, "decoded");
        // 2) bundled defaults from the classpath
        try (InputStream input = AppPaths.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input != null) {
                Properties bundled = new Properties();
                bundled.load(input);
                merge(properties, bundled);
            }
        } catch (IOException ignored) {
            // keep fallbacks
        }
        // 3) optional external override next to the jar
        try {
            Path external = baseDirectory().resolve(CONFIG_FILE);
            if (Files.isRegularFile(external)) {
                Properties override = new Properties();
                try (InputStream input = Files.newInputStream(external)) {
                    override.load(input);
                }
                merge(properties, override);
            }
        } catch (IOException ignored) {
            // keep whatever loaded so far
        }
        return properties;
    }

    private static void merge(Properties target, Properties source) {
        for (String key : new String[]{KEY_SOURCE, KEY_ENCODED, KEY_CAPTURED, KEY_DECODED}) {
            String value = source.getProperty(key);
            if (value != null && !value.isBlank()) {
                target.setProperty(key, value.trim());
            }
        }
    }
}
