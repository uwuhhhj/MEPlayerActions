package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExplicitModelFilesTest {
    @TempDir Path temporary;

    @Test void filenameOnlyCatalogPreservesRootAndNestedFoldersWithoutOpeningModelBodies() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("models"));
        Files.writeString(root.resolve("uncategorized.bbmodel"), "not JSON");
        Path folder = Files.createDirectories(root.resolve("动画/第二组"));
        Files.writeString(folder.resolve("nested_model.bbmodel"), "not JSON");
        Files.writeString(folder.resolve("Bad.bbmodel"), "not JSON");
        Files.writeString(folder.resolve("other.txt"), "not JSON");
        var catalog = ExplicitModelFiles.scan(root);
        assertEquals("ready", catalog.state());
        assertEquals(List.of("uncategorized", "nested_model"), catalog.entries().stream().map(ExplicitModelFiles.Entry::modelId).toList());
        assertEquals(List.of("", "动画/第二组"), catalog.entries().stream().map(ExplicitModelFiles.Entry::folder).toList());
        assertEquals(root.resolve("uncategorized.bbmodel").toAbsolutePath(), catalog.select("uncategorized").path());
        assertEquals(folder.resolve("nested_model.bbmodel").toAbsolutePath(), catalog.select("nested_model").path());
    }

    @Test void exactRootSelectionWinsWhileDuplicateNestedMatchesRemainAmbiguous() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("models"));
        Files.writeString(Files.createDirectories(root.resolve("a")).resolve("demo.bbmodel"), "a");
        Files.writeString(Files.createDirectories(root.resolve("b")).resolve("demo.bbmodel"), "b");
        assertEquals("ambiguous", ExplicitModelFiles.select(root, "demo").state());
        assertEquals("missing", ExplicitModelFiles.select(root, "absent").state());
        Files.writeString(root.resolve("demo.bbmodel"), "root");
        assertEquals(root.resolve("demo.bbmodel").toAbsolutePath(), ExplicitModelFiles.select(root, "demo").path());
        assertEquals(root.resolve("demo.bbmodel").toAbsolutePath(), ExplicitModelFiles.scan(root).select("demo").path());
    }

    @Test void entryBudgetExhaustionCannotPublishPartialSelectionOrMissing() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("models"));
        Files.writeString(root.resolve("first.bbmodel"), "a");
        Files.writeString(root.resolve("second.bbmodel"), "b");
        var catalog = ExplicitModelFiles.scan(root, 16, 2, 10);
        assertEquals("model_complexity", catalog.state());
        assertTrue(catalog.entries().isEmpty());
        assertEquals("model_complexity", catalog.select("first").state());
        assertEquals("model_complexity", catalog.select("absent").state());
    }

    @Test void fileBudgetRejectsCompleteCatalogInsteadOfChoosingTruncatedMatch() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("models"));
        Files.writeString(root.resolve("first.bbmodel"), "a");
        Files.writeString(root.resolve("second.bbmodel"), "b");
        var catalog = ExplicitModelFiles.scan(root, 16, 10, 1);
        assertEquals("model_complexity", catalog.state());
        assertTrue(catalog.entries().isEmpty());
        assertTrue(catalog.reason().contains("扫描文件预算"));
    }

    @Test void depthBudgetCannotSilentlyHideDeeperDuplicateFiles() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("models"));
        Files.writeString(root.resolve("demo.bbmodel"), "a");
        Files.writeString(Files.createDirectories(root.resolve("one/two")).resolve("demo.bbmodel"), "b");
        var catalog = ExplicitModelFiles.scan(root, 2, 20, 20);
        assertEquals("model_complexity", catalog.state());
        assertTrue(catalog.entries().isEmpty());
        assertTrue(catalog.reason().contains("深度预算"));
    }

    @Test void directRootLookupDoesNotTraverseUnrelatedOverDepthDirectory() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("models"));
        Path deep = root;
        for (int i = 0; i < ExplicitModelFiles.MAX_DEPTH + 1; i++) deep = Files.createDirectory(deep.resolve("d"));
        Files.writeString(root.resolve("demo.bbmodel"), "selected root");
        assertEquals("model_complexity", ExplicitModelFiles.scan(root).state());
        assertTrue(ExplicitModelFiles.select(root, "demo").found());
    }

    @Test void retainedClassificationPathsHaveABoundedLength() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("models"));
        Path longFolder = Files.createDirectories(root.resolve("a".repeat(128)).resolve("b".repeat(128)));
        Files.writeString(longFolder.resolve("demo.bbmodel"), "asset");
        var catalog = ExplicitModelFiles.scan(root);
        assertEquals("model_complexity", catalog.state());
        assertTrue(catalog.entries().isEmpty());
        assertTrue(catalog.reason().contains("路径长度预算"));
    }

    @Test void missingDirectoryIsReadOnlyAndInvalidIdsCannotReachOtherPaths() throws Exception {
        Path root = temporary.resolve("absent");
        assertEquals("missing", ExplicitModelFiles.select(root, "demo").state());
        assertFalse(Files.exists(root));
        for (String id : new String[]{null, "../outside", "Demo", "", "x".repeat(65)}) {
            assertEquals("invalid", ExplicitModelFiles.select(root, id).state());
            assertEquals("invalid", ExplicitModelFiles.scan(root).select(id).state());
        }
    }

    @Test void selectedPathMustRemainInsideTheExplicitDirectory() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("models"));
        Path inside = Files.writeString(root.resolve("demo.bbmodel"), "asset");
        Path outside = Files.writeString(temporary.resolve("outside.bbmodel"), "asset");
        assertDoesNotThrow(() -> ExplicitModelFiles.validateSelected(root, inside));
        assertThrows(IOException.class, () -> ExplicitModelFiles.validateSelected(root, outside));
        assertThrows(IOException.class, () -> ExplicitModelFiles.validateSelected(root, root));
        assertThrows(IOException.class, () -> ExplicitModelFiles.validateSelected(root, root.resolve("missing.bbmodel")));
    }

    @Test void symbolicFilesAndFoldersCannotAuthorizeExternalAssets() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("models"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Path external = Files.writeString(outside.resolve("demo.bbmodel"), "asset");
        Path linkedFile = root.resolve("demo.bbmodel");
        Path linkedFolder = root.resolve("linked-folder");
        Path linkedRoot = temporary.resolve("linked-root");
        boolean fileSymlink = true;
        try {
            try {
                Files.createSymbolicLink(linkedFile, external);
                Files.createSymbolicLink(linkedFolder, outside);
                Files.createSymbolicLink(linkedRoot, outside);
            } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                // Windows commonly denies file symlinks but allows real directory junctions.
                assertTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"),
                        "This host must support a real symlink or Windows junction: " + unavailable);
                deleteLink(linkedFile);deleteLink(linkedFolder);deleteLink(linkedRoot);
                fileSymlink = false;
                createJunction(linkedFolder, outside);
                createJunction(linkedRoot, outside);
            }
            assertTrue(Files.exists(linkedFolder.resolve("demo.bbmodel")), "The test link must actually expose its target");
            assertEquals("missing", ExplicitModelFiles.select(root, "demo").state());
            assertTrue(ExplicitModelFiles.scan(root).entries().isEmpty());
            if (fileSymlink) assertThrows(IOException.class, () -> ExplicitModelFiles.validateSelected(root, linkedFile));
            assertThrows(IOException.class, () -> ExplicitModelFiles.validateSelected(root, linkedFolder.resolve("demo.bbmodel")));
            assertEquals("invalid", ExplicitModelFiles.scan(linkedRoot).state());
        } finally {
            // Delete only the link itself. Neither JUnit cleanup nor this test traverses its target.
            deleteLink(linkedFile);deleteLink(linkedFolder);deleteLink(linkedRoot);
        }
        assertEquals("asset", Files.readString(external), "Link cleanup must preserve the target file");
    }

    private void createJunction(Path link, Path target) throws Exception {
        Path boundary = temporary.toAbsolutePath().normalize();
        Path junction = link.toAbsolutePath().normalize();
        Path destination = target.toAbsolutePath().normalize();
        assertTrue(junction.startsWith(boundary) && !junction.equals(boundary));
        assertTrue(destination.startsWith(boundary) && !destination.equals(boundary));
        String script = "$ErrorActionPreference = 'Stop'; New-Item -ItemType Junction -Path '"
                + junction.toString().replace("'", "''") + "' -Target '"
                + destination.toString().replace("'", "''") + "' | Out-Null";
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Timed out creating the required Windows junction");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), "A real Windows junction is required: " + output);
        assertTrue(Files.exists(junction), "PowerShell did not create the required junction");
    }

    private void deleteLink(Path link) throws IOException {
        Path boundary = temporary.toAbsolutePath().normalize();
        Path candidate = link.toAbsolutePath().normalize();
        assertTrue(candidate.startsWith(boundary) && !candidate.equals(boundary));
        Files.deleteIfExists(candidate);
    }
}
