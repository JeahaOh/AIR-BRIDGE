package airbridge.slide;

import javax.swing.tree.DefaultMutableTreeNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

final class SlideImageCatalog {
    private SlideImageCatalog() {
    }

    static SlideImageCatalogResult load(Path inputDir) throws Exception {
        List<Path> imageFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(inputDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(SlideImageCatalog::isSupportedImageFile)
                    .sorted(Comparator
                            .comparingInt(SlideImageCatalog::slidePriority)
                            .thenComparing(path -> inputDir.relativize(path).toString().toLowerCase(Locale.ROOT)))
                    .forEach(imageFiles::add);
        }

        Map<Path, DefaultMutableTreeNode> treeNodeIndex = new LinkedHashMap<>();
        DefaultMutableTreeNode root = buildTreeRoot(inputDir, imageFiles, treeNodeIndex);
        return new SlideImageCatalogResult(imageFiles, root, treeNodeIndex);
    }

    private static boolean isSupportedImageFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".png")
                || fileName.endsWith(".jpg")
                || fileName.endsWith(".jpeg");
    }

    private static int slidePriority(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.contains("session-start")) {
            return 0;
        }
        if (fileName.contains("session-end")) {
            return 2;
        }
        return 1;
    }

    private static DefaultMutableTreeNode buildTreeRoot(Path inputDir,
                                                        List<Path> files,
                                                        Map<Path, DefaultMutableTreeNode> treeNodeIndex) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(inputDir);
        Map<Path, DefaultMutableTreeNode> directories = new LinkedHashMap<>();
        directories.put(inputDir, root);

        for (Path file : files) {
            Path relative = inputDir.relativize(file);
            Path currentDir = inputDir;
            DefaultMutableTreeNode parent = root;
            for (int i = 0; i < relative.getNameCount() - 1; i++) {
                currentDir = currentDir.resolve(relative.getName(i));
                DefaultMutableTreeNode dirNode = directories.get(currentDir);
                if (dirNode == null) {
                    dirNode = new DefaultMutableTreeNode(relative.getName(i));
                    parent.add(dirNode);
                    directories.put(currentDir, dirNode);
                }
                parent = dirNode;
            }
            DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(file);
            parent.add(fileNode);
            treeNodeIndex.put(file, fileNode);
        }
        return root;
    }

    static final class SlideImageCatalogResult {
        private final List<Path> imageFiles;
        private final DefaultMutableTreeNode treeRoot;
        private final Map<Path, DefaultMutableTreeNode> treeNodeIndex;

        private SlideImageCatalogResult(List<Path> imageFiles,
                                        DefaultMutableTreeNode treeRoot,
                                        Map<Path, DefaultMutableTreeNode> treeNodeIndex) {
            this.imageFiles = imageFiles;
            this.treeRoot = treeRoot;
            this.treeNodeIndex = treeNodeIndex;
        }

        List<Path> imageFiles() {
            return imageFiles;
        }

        DefaultMutableTreeNode treeRoot() {
            return treeRoot;
        }

        Map<Path, DefaultMutableTreeNode> treeNodeIndex() {
            return treeNodeIndex;
        }
    }
}
