package airbridge.sender;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SourceCollector {
    private SourceCollector() {
    }

    static List<Path> collectSourceFiles(Path rootDir,
                                         List<String> targetExtensions,
                                         List<String> skipDirs,
                                         List<String> excludePaths) throws IOException {
        List<Path> files = new ArrayList<>();
        Set<String> normalizedTargetExtensions = normalizeTargetExtensions(targetExtensions);
        Set<String> skipDirSet = normalizeSkipDirs(skipDirs);
        List<String> normalizedExcludePaths = normalizeExcludePaths(excludePaths);

        Files.walkFileTree(rootDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (skipDirSet.contains(dirName) || dirName.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String absPath = file.toAbsolutePath().toString();
                for (String excludePath : normalizedExcludePaths) {
                    if (!excludePath.isEmpty() && absPath.startsWith(excludePath)) {
                        return FileVisitResult.CONTINUE;
                    }
                }

                String fileName = file.getFileName().toString();
                int dotIdx = fileName.lastIndexOf('.');
                if (dotIdx > 0) {
                    String ext = fileName.substring(dotIdx).toLowerCase();
                    if (normalizedTargetExtensions.contains(ext)) {
                        files.add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        Collections.sort(files);
        return files;
    }

    private static Set<String> normalizeTargetExtensions(List<String> targetExtensions) {
        Set<String> normalizedTargetExtensions = new HashSet<>();
        for (String ext : targetExtensions) {
            if (ext != null && !ext.trim().isEmpty()) {
                String normalized = ext.trim().toLowerCase();
                normalizedTargetExtensions.add(normalized.startsWith(".") ? normalized : "." + normalized);
            }
        }
        return normalizedTargetExtensions;
    }

    private static Set<String> normalizeSkipDirs(List<String> skipDirs) {
        Set<String> skipDirSet = new HashSet<>();
        for (String dir : skipDirs) {
            if (dir != null && !dir.trim().isEmpty()) {
                skipDirSet.add(dir.trim());
            }
        }
        return skipDirSet;
    }

    private static List<String> normalizeExcludePaths(List<String> excludePaths) {
        List<String> normalizedExcludePaths = new ArrayList<>();
        for (String path : excludePaths) {
            if (path != null && !path.trim().isEmpty()) {
                normalizedExcludePaths.add(Paths.get(path.trim()).toAbsolutePath().toString());
            }
        }
        return normalizedExcludePaths;
    }
}
