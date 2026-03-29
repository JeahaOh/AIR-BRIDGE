package airbridge.slide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlideImageCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void loadSortsSessionStartFirstSessionEndLastAndIgnoresUnsupportedFiles() throws Exception {
        Path inputDir = tempDir.resolve("slides");
        Files.createDirectories(inputDir.resolve("a"));
        Files.createDirectories(inputDir.resolve("b"));
        Files.createDirectories(inputDir.resolve("c"));

        Path start = Files.writeString(inputDir.resolve("b/002-session-start.JPG"), "start");
        Path normalA = Files.writeString(inputDir.resolve("a/010-normal.png"), "normal-a");
        Path normalB = Files.writeString(inputDir.resolve("b/001-normal.jpeg"), "normal-b");
        Path end = Files.writeString(inputDir.resolve("c/999-session-end.png"), "end");
        Files.writeString(inputDir.resolve("a/ignored.txt"), "ignored");
        Files.writeString(inputDir.resolve("a/ignored.gif"), "ignored");

        SlideImageCatalog.SlideImageCatalogResult result = SlideImageCatalog.load(inputDir);

        assertEquals(List.of(start, normalA, normalB, end), result.imageFiles());
        assertEquals(result.imageFiles(), new ArrayList<>(result.treeNodeIndex().keySet()));
    }

    @Test
    void loadBuildsTreeAndIndexesEachImageNode() throws Exception {
        Path inputDir = tempDir.resolve("slides");
        Path first = createImagePlaceholder(inputDir.resolve("alpha/session-start-001.png"));
        Path second = createImagePlaceholder(inputDir.resolve("alpha/notes/020-middle.jpg"));
        Path third = createImagePlaceholder(inputDir.resolve("zeta/session-end-999.jpeg"));

        SlideImageCatalog.SlideImageCatalogResult result = SlideImageCatalog.load(inputDir);

        DefaultMutableTreeNode root = result.treeRoot();
        Map<Path, DefaultMutableTreeNode> index = result.treeNodeIndex();

        assertEquals(inputDir, root.getUserObject());
        assertEquals(2, root.getChildCount());
        assertEquals(List.of("alpha", "zeta"), childLabels(root));

        assertEquals(first, index.get(first).getUserObject());
        assertEquals(second, index.get(second).getUserObject());
        assertEquals(third, index.get(third).getUserObject());

        DefaultMutableTreeNode secondNode = index.get(second);
        assertEquals(
                List.of(inputDir.toString(), "alpha", "notes", second.toString()),
                pathLabels(secondNode)
        );

        List<Path> indexedFiles = result.imageFiles().stream()
                .filter(index::containsKey)
                .toList();
        assertIterableEquals(result.imageFiles(), indexedFiles);
        assertTrue(index.values().stream().allMatch(node -> node.isLeaf()));
    }

    private Path createImagePlaceholder(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "image");
    }

    private static List<String> childLabels(DefaultMutableTreeNode node) {
        List<String> labels = new ArrayList<>();
        Enumeration<TreeNode> children = node.children();
        while (children.hasMoreElements()) {
            labels.add(String.valueOf(((DefaultMutableTreeNode) children.nextElement()).getUserObject()));
        }
        return labels;
    }

    private static List<String> pathLabels(DefaultMutableTreeNode node) {
        List<String> labels = new ArrayList<>();
        for (Object segment : node.getUserObjectPath()) {
            labels.add(String.valueOf(segment));
        }
        return labels;
    }
}
