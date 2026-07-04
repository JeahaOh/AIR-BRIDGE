package airbridge.packager;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.zip.ZipException;

/** Shared command-side validation and one-line error reporting. */
final class PackagerCli {
    private PackagerCli() {
    }

    /** Validates the input up front so predictable mistakes fail with one clear line. */
    static Path requireExistingPackage(Path input) throws IOException {
        Path abs = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) {
            throw new NoSuchFileException(abs.toString());
        }
        if (!PackagerInspector.isPackageName(abs.getFileName().toString())) {
            throw new IllegalArgumentException("Input must be a .jar or .zip: " + abs);
        }
        return abs;
    }

    static int fail(String command, Exception e) {
        System.err.printf("%s failed: %s%n", command, describe(e));
        return 1;
    }

    private static String describe(Exception e) {
        if (e instanceof NoSuchFileException notFound) {
            return "input not found: " + notFound.getFile();
        }
        // Only archive-read failures carry the entry-name-encoding hint. A decode error while
        // reading the user's own target-ext.txt is a different problem and reaches here as a
        // plain (already-described) IOException, so it must not borrow the archive hint.
        if ((e instanceof ZipException || e instanceof IllegalArgumentException) && isEntryNameEncodingError(e)) {
            return "archive entry names are not valid UTF-8; re-create the archive with UTF-8 entry names";
        }
        if (e instanceof ZipException zip) {
            String message = zip.getMessage();
            return "not a valid zip archive: " + (message == null ? zip.toString() : message);
        }
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /**
     * ZipFile reports a bad top-level entry name as {@code invalid CEN header (bad entry name)};
     * ZipInputStream (nested archives) surfaces it as an IllegalArgumentException wrapping a
     * {@link CharacterCodingException}. Other {@code invalid CEN header (...)} variants mean
     * plain corruption, not an encoding problem, so match only the name-decoding cases.
     */
    private static boolean isEntryNameEncodingError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CharacterCodingException) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.contains("bad entry name")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
