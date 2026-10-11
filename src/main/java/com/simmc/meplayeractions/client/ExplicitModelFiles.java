package com.simmc.meplayeractions.client;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded filename-only catalog. Directory membership never grants an alias from model JSON. */
final class ExplicitModelFiles {
    static final int MAX_DEPTH = 16;
    static final int MAX_ENTRIES = 16_384;
    static final int MAX_FILES = 4_096;
    static final int MAX_FOLDER_LENGTH = 256;
    private static final Pattern FILE_NAME = Pattern.compile("([a-z0-9_-]{1,64})\\.bbmodel");

    private ExplicitModelFiles() { }

    record Entry(String modelId, Path path, String folder) { }
    record Selection(Path path, String state, String reason) {
        boolean found() { return path != null && state.equals("ready"); }
    }
    record Catalog(List<Entry> entries, String state, String reason) {
        Catalog { entries = List.copyOf(entries); }
        Selection select(String id) {
            if (id == null || !FILE_NAME.matcher(id + ".bbmodel").matches()) return new Selection(null, "invalid", "模型 ID 格式无效");
            if (!state.equals("ready")) return new Selection(null, state, reason);
            Entry nested = null;
            int matches = 0;
            for (Entry entry : entries) {
                if (!entry.modelId.equals(id)) continue;
                if (entry.folder.isEmpty()) return new Selection(entry.path, "ready", "模型文件已明确提供");
                nested = entry;
                matches++;
            }
            if (matches > 1) return new Selection(null, "ambiguous", "多个子文件夹存在同名模型，请保留唯一文件或在 models/ 根目录明确指定");
            return nested == null ? missing() : new Selection(nested.path, "ready", "模型文件已明确提供");
        }
    }

    static Selection select(Path root, String id) throws IOException {
        if (id == null || !FILE_NAME.matcher(id + ".bbmodel").matches()) return new Selection(null, "invalid", "模型 ID 格式无效");
        Path normalized = normalizedRoot(root);
        String rootProblem = rootProblem(normalized);
        if (rootProblem != null) return new Selection(null, "invalid", rootProblem);
        Path direct = normalized.resolve(id + ".bbmodel");
        // An explicit root file wins without reading unrelated directories or their model bodies.
        if (Files.isRegularFile(direct, LinkOption.NOFOLLOW_LINKS)) {
            validateSelected(normalized, direct);
            return new Selection(direct, "ready", "模型文件已明确提供");
        }
        return scan(normalized).select(id);
    }

    static Catalog scan(Path root) throws IOException {
        return scan(root, MAX_DEPTH, MAX_ENTRIES, MAX_FILES);
    }

    /** Smaller budgets keep the boundary checks testable without constructing huge directories. */
    static Catalog scan(Path root, int maxDepth, int maxEntries, int maxFiles) throws IOException {
        if (maxDepth < 1 || maxEntries < 1 || maxFiles < 1) throw new IllegalArgumentException("Invalid model directory budget");
        Path normalized = normalizedRoot(root);
        String rootProblem = rootProblem(normalized);
        if (rootProblem != null) return new Catalog(List.of(), "invalid", rootProblem);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return new Catalog(List.of(), "ready", "模型目录尚未创建");
        var entries = new ArrayList<Entry>();
        int[] visited = {0};
        String[] exhausted = {null};
        Files.walkFileTree(normalized, java.util.Set.of(), maxDepth, new SimpleFileVisitor<>() {
            private FileVisitResult count() throws IOException {
                interrupted();
                if (++visited[0] > maxEntries) {
                    exhausted[0] = "模型目录超过遍历条目预算";
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (count() == FileVisitResult.TERMINATE) return FileVisitResult.TERMINATE;
                // Windows junctions can be directories without reporting isSymbolicLink().
                return redirected(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (count() == FileVisitResult.TERMINATE) return FileVisitResult.TERMINATE;
                // walkFileTree does not follow links. A directory visited as a file reached maxDepth.
                if (attrs.isDirectory()) {
                    if (redirected(file)) return FileVisitResult.CONTINUE;
                    exhausted[0] = "模型目录超过子文件夹深度预算";
                    return FileVisitResult.TERMINATE;
                }
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                var filename = FILE_NAME.matcher(file.getFileName().toString());
                if (!filename.matches()) return FileVisitResult.CONTINUE;
                if (entries.size() >= maxFiles) {
                    exhausted[0] = "模型目录超过扫描文件预算";
                    return FileVisitResult.TERMINATE;
                }
                Path relative = normalized.relativize(file);
                String folder = relative.getParent() == null ? "" : relative.getParent().toString().replace('\\', '/');
                if (folder.length() > MAX_FOLDER_LENGTH) {
                    exhausted[0] = "模型目录超过分类路径长度预算";
                    return FileVisitResult.TERMINATE;
                }
                entries.add(new Entry(filename.group(1), file, folder));
                return FileVisitResult.CONTINUE;
            }
        });
        if (exhausted[0] != null) return new Catalog(List.of(), "model_complexity", exhausted[0]);
        entries.sort(Comparator.comparing(Entry::folder).thenComparing(Entry::modelId));
        return new Catalog(entries, "ready", "模型目录已读取");
    }

    /** Recheck containment and links immediately before opening a selected asset. */
    static void validateSelected(Path root, Path file) throws IOException {
        Path normalized = normalizedRoot(root);
        Path selected = file.toAbsolutePath().normalize();
        if (!selected.startsWith(normalized) || selected.equals(normalized)) throw new IOException("Unsafe model file path");
        if (rootProblem(normalized) != null) throw new IOException("Unsafe model directory");
        Path cursor = normalized;
        for (Path part : normalized.relativize(selected)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor) || redirected(cursor)) throw new IOException("Symbolic model path is not allowed");
        }
        if (!selected.toRealPath().startsWith(normalized.toRealPath())) throw new IOException("Unsafe model file path");
        if (!Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Model file is not regular");
    }

    private static Path normalizedRoot(Path root) { return Objects.requireNonNull(root).toAbsolutePath().normalize(); }
    private static String rootProblem(Path root) throws IOException {
        interrupted();
        try {
            var attrs = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || redirected(root)) return "模型目录不能是符号链接或目录联接";
            if (!attrs.isDirectory()) return "模型目录不是文件夹";
            return null;
        } catch (NoSuchFileException absent) { return null; }
    }
    private static Selection missing() { return new Selection(null, "missing", "MEPlayerActions models/ 未提供此模型文件；客户端接管不可用，服务器伪装仍由 ModelEngine 渲染"); }
    private static boolean redirected(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        // Windows TEMP can contain a legitimate short-name alias in an ancestor. Resolve that
        // ancestor first, then compare only this node so an actual junction still changes its target.
        Path expected = parent == null ? absolute : parent.toRealPath().resolve(absolute.getFileName()).normalize();
        return !absolute.toRealPath().equals(expected);
    }
    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("task_cancelled");
    }
}
