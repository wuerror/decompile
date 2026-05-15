import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.helper.HelpScreenException;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class Main {
    private static final int MAX_VF_THREADS = 4;
    private static final int MAX_ARCHIVE_CONCURRENCY = 8;
    private static final int PER_ARCHIVE_HEAP_MB = 512;
    private static final String HELP_EPILOG = String.join(System.lineSeparator(),
            "",
            "Output directory:",
            "  Directory input:  D:\\libs       -> D:\\libs_src\\",
            "  Single JAR input: D:\\libs\\a.jar -> D:\\libs\\a_src\\",
            "",
            "Examples:",
            "  java -jar decompile.jar D:\\libs",
            "  java -jar decompile.jar D:\\libs -tp com.example.app",
            "  java -jar decompile.jar D:\\libs -j 2 --vf-threads 1",
            "  java -jar decompile.jar D:\\classes\\Foo.class",
            "  java -jar decompile.jar D:\\classes"
    );

    private static String targetPackage = null;
    private static final AtomicInteger processedCount = new AtomicInteger(0);
    private static final AtomicInteger skippedCount = new AtomicInteger(0);
    private static final AtomicInteger failedCount = new AtomicInteger(0);
    private static final AtomicInteger activeArchiveTasks = new AtomicInteger(0);
    private static final AtomicInteger dedupSymlinkCount = new AtomicInteger(0);
    private static final AtomicInteger dedupCopyCount = new AtomicInteger(0);
    private static final ConcurrentHashMap<String, CompletableFuture<Path>> hashToOutputDir = new ConcurrentHashMap<>();

    private static ThreadPoolExecutor executor;
    private static int archiveConcurrency = 1;
    private static int vineflowerThreads = 1;

    public static void main(String[] args) throws IOException {
        ArgumentParser parser = ArgumentParsers.newArgumentParser("decompile")
                .defaultHelp(true)
                .description("Decompile JAR/WAR files to Java source code with Vineflower")
                .epilog(HELP_EPILOG);
        parser.addArgument("path")
                .required(true)
                .help("Directory containing JAR/WAR files to decompile");
        parser.addArgument("-tp", "--target-package")
                .required(false)
                .help("Target package name - only decompile JARs containing this package (skip third-party JARs)");
        parser.addArgument("-j", "--jobs")
                .dest("jobs")
                .type(Integer.class)
                .required(false)
                .help("Maximum number of archives to decompile concurrently");
        parser.addArgument("--vf-threads")
                .dest("vineflower_threads")
                .type(Integer.class)
                .required(false)
                .help("Worker threads used inside each Vineflower decompilation");

        Namespace ns;
        try {
            ns = parser.parseArgs(args);
        } catch (HelpScreenException e) {
            System.exit(0);
            return;
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
            return;
        }

        String path = (String) ns.get("path");
        targetPackage = (String) ns.get("target_package");

        Integer requestedJobs = ns.getInt("jobs");
        Integer requestedVineflowerThreads = ns.getInt("vineflower_threads");

        try {
            validatePositive(requestedJobs, "--jobs");
            validatePositive(requestedVineflowerThreads, "--vf-threads");
        } catch (IllegalArgumentException e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
            return;
        }

        File inputFile = new File(path);
        boolean singlePathInput = inputFile.isFile();

        // For class input, keep one CPU core available for the OS/user.
        int cpus = Runtime.getRuntime().availableProcessors();
        vineflowerThreads = requestedVineflowerThreads != null
                ? requestedVineflowerThreads
                : Math.max(1, Math.min(MAX_VF_THREADS, Math.max(1, cpus - 1)));

        // Handle single .class file directly
        if (inputFile.isFile() && inputFile.getName().endsWith(".class")) {
            processClassFile(inputFile);
            shutdownExecutor();
            printSummary();
            System.exit(0);
            return;
        }

        ScanResult scanResult = null;
        boolean isClassDir = false;

        if (inputFile.isDirectory()) {
            scanResult = scanDirectory(inputFile.toPath());
            isClassDir = !scanResult.hasArchives && scanResult.classCount > 0;
        }

        if (isClassDir) {
            processClassDirectory(inputFile, scanResult.classCount);
        } else {
            resolveThreading(singlePathInput, requestedJobs, requestedVineflowerThreads);
            executor = createExecutor(archiveConcurrency);

            File decompileDir;
            if (inputFile.isFile()) {
                File parentDir = inputFile.getAbsoluteFile().getParentFile();
                String jarBaseName = getArchiveBaseName(inputFile.getName());
                decompileDir = new File(parentDir == null ? new File(".") : parentDir, jarBaseName + "_src");
            } else {
                File absoluteBaseDir = inputFile.getAbsoluteFile();
                File parentDir = absoluteBaseDir.getParentFile();
                decompileDir = new File(parentDir == null ? new File(".") : parentDir, absoluteBaseDir.getName() + "_src");
            }
            if (!decompileDir.exists()) {
                decompileDir.mkdirs();
            }

            System.out.println("Starting decompilation...");
            System.out.println("Source: " + inputFile.getAbsolutePath());
            System.out.println("Output directory: " + decompileDir.getAbsolutePath());
            if (targetPackage != null) {
                System.out.println("Target package filter: " + targetPackage);
            }
            System.out.println("Archive concurrency: " + archiveConcurrency);
            System.out.println("Vineflower threads per archive: " + vineflowerThreads);
            System.out.println();

            if (inputFile.isFile()) {
                processSingleFile(inputFile.getAbsolutePath(), decompileDir.getAbsolutePath());
            } else {
                processDirectory(decompileDir.getAbsolutePath(), scanResult);
            }

            // Handle mixed directory: decompile loose .class files into decompileDir/classes/
            if (scanResult != null && scanResult.hasArchives && scanResult.classCount > 0) {
                System.out.println("Processing " + scanResult.classCount + " loose .class files...");
                decompileClassesToOutputDir(inputFile, decompileDir, scanResult.classCount);
            }
        }

        shutdownExecutor();

        printSummary();

        System.exit(0);
    }

    private static void printSummary() {
        System.out.println();
        System.out.println("Decompilation completed!");
        System.out.println("Processed: " + processedCount.get() + " files");
        System.out.println("Skipped: " + skippedCount.get() + " files");
        int symlinks = dedupSymlinkCount.get();
        int copies = dedupCopyCount.get();
        if (symlinks + copies > 0) {
            System.out.println("Dedup: " + (symlinks + copies) + " files (symlink: " + symlinks + ", copy: " + copies + ")");
        }
        if (failedCount.get() > 0) {
            System.out.println("Failed: " + failedCount.get() + " files");
        }
    }

    private static void processSingleFile(String jarPath, String decompilePath) {
        if (jarPath.endsWith(".jar") || jarPath.endsWith(".war")) {
            File jarFile = new File(jarPath);

            DecompileTask task = new DecompileTask(jarPath, decompilePath, jarFile.length());
            try {
                task.call();
            } catch (Exception e) {
                System.err.println("[ERROR] Failed to process file: " + e.getMessage());
            }
        } else {
            System.err.println("[ERROR] File must be a .jar or .war file");
        }
    }

    private static void processDirectory(String decompilePath, ScanResult scanResult) throws IOException {
        if (scanResult == null || scanResult.archives.isEmpty()) {
            return;
        }

        List<Future<?>> futures = new ArrayList<>();

        scanResult.archives.sort(Comparator.comparingLong(ArchiveTaskMetadata::sizeBytes).reversed());

        for (ArchiveTaskMetadata archive : scanResult.archives) {
            String subDecompilePath = archive.relativeDirPath().isEmpty()
                    ? decompilePath
                    : new File(decompilePath, archive.relativeDirPath()).getAbsolutePath();
            futures.add(executor.submit(new DecompileTask(
                    archive.jarPath(),
                    subDecompilePath,
                    archive.sizeBytes()
            )));
        }

        for (Future<?> future : futures) {
            try {
                future.get(60, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[ERROR] Task execution interrupted: " + e.getMessage());
            } catch (ExecutionException e) {
                System.err.println("[ERROR] Task execution failed: " + e.getMessage());
            } catch (TimeoutException e) {
                System.err.println("[ERROR] Task timed out after 60 minutes");
                future.cancel(true);
            }
        }
    }

    private static void processClassFile(File classFile) {
        File parent = classFile.getParentFile();
        String output = parent == null ? new File(".").getAbsolutePath() : parent.getAbsolutePath();

        System.out.println("Starting class decompilation...");
        System.out.println("Source: " + classFile.getAbsolutePath());
        System.out.println("Output directory: " + output);
        System.out.println("Vineflower threads: " + vineflowerThreads);
        if (targetPackage != null) {
            System.out.println("Target package filter: " + targetPackage);
        }
        System.out.println();

        try {
            DecompileUtil.decompileClassFileInPlace(classFile.getAbsolutePath(), vineflowerThreads);
            processedCount.incrementAndGet();
            System.out.println("[DECOMPILED CLASS] " + classFile.getAbsolutePath());
        } catch (Exception e) {
            failedCount.incrementAndGet();
            System.err.println("[ERROR] Failed to decompile class: " + classFile.getAbsolutePath() + " - " + e.getMessage());
        }
    }

    private static void processClassDirectory(File directory, int classCount) {
        System.out.println("Starting class directory decompilation...");
        System.out.println("Source: " + directory.getAbsolutePath());
        System.out.println("Output directory: " + directory.getAbsolutePath());
        System.out.println("Vineflower threads: " + vineflowerThreads);
        if (targetPackage != null) {
            System.out.println("Target package filter: " + targetPackage);
        }
        System.out.println();

        try {
            DecompileUtil.decompileClassDirectoryInPlace(directory.getAbsolutePath(), vineflowerThreads);
            processedCount.addAndGet(classCount);
            System.out.println("[DECOMPILED CLASS DIR] " + directory.getAbsolutePath() + " (" + classCount + " classes)");
        } catch (Exception e) {
            failedCount.incrementAndGet();
            System.err.println("[ERROR] Failed to decompile directory: " + directory.getAbsolutePath() + " - " + e.getMessage());
        }
    }

    private static void decompileClassesToOutputDir(File sourceDir, File outputBaseDir, int classCount) {
        File classesOutputDir = new File(outputBaseDir, "classes");
        classesOutputDir.mkdirs();
        System.out.println("Output directory: " + classesOutputDir.getAbsolutePath());

        try {
            DecompileUtil.decompileClassDirectory(sourceDir.getAbsolutePath(), classesOutputDir.getAbsolutePath(), vineflowerThreads);
            processedCount.addAndGet(classCount);
            System.out.println("[DECOMPILED CLASS DIR] " + sourceDir.getAbsolutePath() + " -> " + classesOutputDir.getAbsolutePath() + " (" + classCount + " classes)");
        } catch (Exception e) {
            failedCount.incrementAndGet();
            System.err.println("[ERROR] Failed to decompile directory: " + sourceDir.getAbsolutePath() + " - " + e.getMessage());
        }
    }

    private static ScanResult scanDirectory(Path directoryPath) throws IOException {
        ScanResult result = new ScanResult();

        try (Stream<Path> stream = Files.walk(directoryPath)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> {
                        String strPath = path.toString();
                        if (hasPathSegmentEndingWith(strPath, "_src")
                                || strPath.contains(".woodpecker")
                                || strPath.contains("target" + File.separator + "classes")) {
                            return;
                        }
                        if (strPath.endsWith(".jar") || strPath.endsWith(".war")) {
                            result.hasArchives = true;
                            String relDir = directoryPath.relativize(path.getParent()).toString();
                            result.archives.add(new ArchiveTaskMetadata(strPath, path.toFile().length(), relDir));
                        } else if (strPath.endsWith(".class")) {
                            result.classCount++;
                        }
                    });
        }

        return result;
    }

    private static ThreadPoolExecutor createExecutor(int poolSize) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(poolSize * 2),
                Executors.defaultThreadFactory(),
                (task, executor) -> {
                    if (executor.isShutdown()) {
                        throw new RejectedExecutionException("Executor has been shut down");
                    }
                    try {
                        executor.getQueue().put(task);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RejectedExecutionException("Interrupted while waiting to submit task", e);
                    }
                }
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static void shutdownExecutor() {
        if (executor == null) {
            return;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static void validatePositive(Integer value, String optionName) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(optionName + " must be greater than 0");
        }
    }

    private static void resolveThreading(boolean singlePathInput, Integer requestedJobs, Integer requestedVfThreads) {
        int cpus = Runtime.getRuntime().availableProcessors();
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        if (singlePathInput) {
            archiveConcurrency = 1;
            vineflowerThreads = requestedVfThreads != null
                    ? requestedVfThreads
                    : Math.max(1, Math.min(MAX_VF_THREADS, Math.max(1, cpus - 1)));
            return;
        }

        // Both specified: trust the user
        if (requestedJobs != null && requestedVfThreads != null) {
            archiveConcurrency = requestedJobs;
            vineflowerThreads = requestedVfThreads;
            return;
        }

        int maxByHeap = Math.max(1, (int) (heapMb / PER_ARCHIVE_HEAP_MB));

        if (requestedJobs != null) {
            archiveConcurrency = requestedJobs;
            vineflowerThreads = 1;
            return;
        }

        if (requestedVfThreads != null) {
            vineflowerThreads = requestedVfThreads;
            archiveConcurrency = Math.max(1, Math.min(maxByHeap, Math.min(cpus / vineflowerThreads, MAX_ARCHIVE_CONCURRENCY)));
            return;
        }

        int budget = Math.max(1, Math.min(maxByHeap, Math.min(Math.max(1, cpus - 2), MAX_ARCHIVE_CONCURRENCY)));
        archiveConcurrency = budget;
        vineflowerThreads = 1;
    }

    private static class ScanResult {
        private final List<ArchiveTaskMetadata> archives = new ArrayList<>();
        private boolean hasArchives;
        private int classCount;
    }

    private record ArchiveTaskMetadata(String jarPath, long sizeBytes, String relativeDirPath) {
    }

    private static class DecompileTask implements Callable<Void> {
        private final String jarPath;
        private final String decompilePath;
        private final long sizeBytes;

        private DecompileTask(String jarPath, String decompilePath, long sizeBytes) {
            this.jarPath = jarPath;
            this.decompilePath = decompilePath;
            this.sizeBytes = sizeBytes;
        }

        @Override
        public Void call() {
            boolean isTargetJar = targetPackage == null || containsTargetPackage(jarPath);
            if (!isTargetJar) {
                skippedCount.incrementAndGet();
                System.out.println("[SKIPPED] " + jarPath);
                return null;
            }

            if (isAlreadyDecompiled(jarPath, decompilePath)) {
                skippedCount.incrementAndGet();
                System.out.println("[ALREADY DECOMPILED] " + jarPath);
                return null;
            }

            Path outputDir = resolveOutputDir(jarPath, decompilePath);
            String hash;
            try {
                hash = sha256(Path.of(jarPath));
            } catch (Exception e) {
                hash = null;
            }

            if (hash != null) {
                while (true) {
                    CompletableFuture<Path> ownedFuture = new CompletableFuture<>();
                    CompletableFuture<Path> existingFuture = hashToOutputDir.putIfAbsent(hash, ownedFuture);
                    if (existingFuture == null) {
                        return decompileArchive(isTargetJar, hash, outputDir, ownedFuture);
                    }

                    try {
                        Path existingOutput = existingFuture.get();
                        if (existingOutput.equals(outputDir)) {
                            skippedCount.incrementAndGet();
                            System.out.println("[DEDUP] " + outputDir.getFileName() + " -> reused existing output");
                            return null;
                        }

                        linkOrCopy(existingOutput, outputDir, hash);
                        skippedCount.incrementAndGet();
                        return null;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failedCount.incrementAndGet();
                        System.err.println("[ERROR] Dedup wait interrupted: " + jarPath + " - " + e.getMessage());
                        return null;
                    } catch (ExecutionException e) {
                        hashToOutputDir.remove(hash, existingFuture);
                    } catch (IOException e) {
                        System.err.println("[DEDUP FAILED] " + jarPath + " - " + e.getMessage() + ", falling back to decompile");
                        return decompileArchive(isTargetJar, null, outputDir, null);
                    }
                }
            }

            return decompileArchive(isTargetJar, null, outputDir, null);
        }

        private Void decompileArchive(
                boolean isTargetJar,
                String hash,
                Path outputDir,
                CompletableFuture<Path> ownedFuture
        ) {
            int active = activeArchiveTasks.incrementAndGet();
            try {
                System.out.println("[START] " + jarPath
                        + " (size " + formatSize(sizeBytes)
                        + ", active " + active + "/" + archiveConcurrency + ")");

                DecompileUtil.decompileJar(jarPath, decompilePath, isTargetJar, targetPackage, vineflowerThreads);
                processedCount.incrementAndGet();
                System.out.println("[DECOMPILED] " + jarPath);

                if (ownedFuture != null) {
                    ownedFuture.complete(outputDir);
                }
            } catch (OutOfMemoryError e) {
                failOwnedFuture(hash, ownedFuture, e);
                failedCount.incrementAndGet();
                System.err.println("[ERROR] Failed to decompile: " + jarPath + " - Out of memory");
                System.err.println("[HINT] Try reducing -j or --vf-threads, or increasing heap with -Xmx");
            } catch (Exception e) {
                failOwnedFuture(hash, ownedFuture, e);
                failedCount.incrementAndGet();
                System.err.println("[ERROR] Failed to decompile: " + jarPath + " - " + e.getMessage());
            } finally {
                activeArchiveTasks.decrementAndGet();
            }
            return null;
        }

        private void failOwnedFuture(String hash, CompletableFuture<Path> ownedFuture, Throwable error) {
            if (ownedFuture == null) {
                return;
            }
            ownedFuture.completeExceptionally(error);
            hashToOutputDir.remove(hash, ownedFuture);
        }

        private static Path resolveOutputDir(String jarPath, String decompilePath) {
            File jarFile = new File(jarPath);
            String jarName = jarFile.getName();
            String baseName = jarName.endsWith(".war") ? jarName.replace(".war", "") : jarName.replace(".jar", "");

            File decompileDir = new File(decompilePath);
            File outputDir = decompileDir.getName().equals(baseName + "_src")
                    ? decompileDir
                    : new File(decompileDir, baseName + "_src");
            return outputDir.toPath();
        }

        private boolean isAlreadyDecompiled(String jarPath, String decompilePath) {
            Path outputDir = resolveOutputDir(jarPath, decompilePath);

            if (Files.isDirectory(outputDir) || Files.isSymbolicLink(outputDir)) {
                try (Stream<Path> contents = Files.list(outputDir)) {
                    return contents.findFirst().isPresent();
                } catch (IOException e) {
                    return false;
                }
            }

            return false;
        }
    }

    private static boolean containsTargetPackage(String jarPath) {
        return DecompileUtil.containsTargetPackage(jarPath, targetPackage);
    }

    private static String formatSize(long sizeBytes) {
        long sizeMb = sizeBytes / (1024 * 1024);
        if (sizeMb > 0) {
            return sizeMb + "MB";
        }

        long sizeKb = sizeBytes / 1024;
        if (sizeKb > 0) {
            return sizeKb + "KB";
        }

        return sizeBytes + "B";
    }

    private static boolean hasPathSegmentEndingWith(String path, String suffix) {
        for (String segment : path.split("[/\\\\]")) {
            if (segment.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String getArchiveBaseName(String fileName) {
        if (fileName.endsWith(".war")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        if (fileName.endsWith(".jar")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    private static void linkOrCopy(Path existing, Path link, String hash) throws IOException {
        String jarName = link.getFileName().toString();
        String hashPrefix = hash.substring(0, Math.min(8, hash.length()));
        try {
            Files.createSymbolicLink(link, existing);
            dedupSymlinkCount.incrementAndGet();
            System.out.println("[DEDUP] " + jarName + " -> symlink to existing (hash: " + hashPrefix + "...)");
        } catch (IOException | UnsupportedOperationException e) {
            copyDirectoryRecursively(existing, link);
            dedupCopyCount.incrementAndGet();
            System.out.println("[DEDUP] " + jarName + " -> copied from existing (symlink not supported)");
        }
    }

    private static void copyDirectoryRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
