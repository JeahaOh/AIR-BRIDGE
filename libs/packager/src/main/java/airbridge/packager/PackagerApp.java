package airbridge.packager;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

@Command(
        name = "packager",
        mixinStandardHelpOptions = true,
        versionProvider = PackagerApp.ManifestVersionProvider.class,
        description = "Pack or unpack sender jar/zip payloads before deployment.",
        subcommands = {
                IdentifyCommand.class,
                PackCommand.class,
                UnpackCommand.class
        }
)
public class PackagerApp implements Runnable {
    public static int execute(String[] args) {
        return new CommandLine(new PackagerApp()).execute(args);
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    static class ManifestVersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            Package pkg = PackagerApp.class.getPackage();
            String version = (pkg != null) ? pkg.getImplementationVersion() : null;
            if (version == null) {
                version = "dev";
            }
            return new String[] { "air-bridge packager " + version };
        }
    }
}
