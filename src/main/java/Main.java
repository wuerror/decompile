import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    private static String targetPackage = null;
    private static final AtomicInteger processedCount = new AtomicInteger(0);
    private static final AtomicInteger skippedCount = new AtomicInteger(0);
    private static final AtomicInteger failedCount = new AtomicInteger(0);
    private static final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors())
    );

    public static void main(String[] args) throws IOException {
        ArgumentParser parser = ArgumentParsers.newArgumentParser("decompile")
                .description("Decompile JAR/WAR files to Java source code with Vineflower");
        parser.addArgument("path")
                .required(true)
                .help("Directory containing JAR/WAR files to decompile");
        parser.addArgument("-tp", "--target-package")
                .required(false)
                .help("Target package name - only decompile JARs containing this package (skip third-party JARs)");
        
        Namespace ns = null;
        try {
            ns = parser.parseArgs(args);
        } catch (ArgumentParserException e) {
            parser.printHelp();
            System.exit(1);
            return;
        }

        String path = (String) ns.get("path");
        targetPackage = (String) ns.get("target_package");

        File inputFile = new File(path);
        File baseDir;

        if (inputFile.isFile()) {
            baseDir = inputFile.getParentFile();
        } else {
            baseDir = inputFile;
        }

        File decompileDir = new File(baseDir, ".java-decompile");
        if (!decompileDir.exists()) {
            decompileDir.mkdirs();
        }

        System.out.println("Starting decompilation...");
        System.out.println("Source: " + inputFile.getAbsolutePath());
        System.out.println("Output directory: " + decompileDir.getAbsolutePath());
        if (targetPackage != null) {
            System.out.println("Target package filter: " + targetPackage);
        }
        System.out.println("Thread pool size: " + executor.getCorePoolSize());
        System.out.println();

        if (inputFile.isFile()) {
            processSingleFile(inputFile.getAbsolutePath(), decompileDir.getAbsolutePath());
        } else {
            processDirectory(path, decompileDir.getAbsolutePath());
        }
        
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        
        System.out.println();
        System.out.println("Decompilation completed!");
        System.out.println("Processed: " + processedCount.get() + " files");
        System.out.println("Skipped: " + skippedCount.get() + " files");
        if (failedCount.get() > 0) {
            System.out.println("Failed: " + failedCount.get() + " files");
        }
    }

    private static void processSingleFile(String jarPath, String decompilePath) {
        if (jarPath.endsWith(".jar") || jarPath.endsWith(".war")) {
            DecompileTask task = new DecompileTask(jarPath, decompilePath, null);
            try {
                task.call();
            } catch (Exception e) {
                System.err.println("[ERROR] Failed to process file: " + e.getMessage());
            }
        } else {
            System.err.println("[ERROR] File must be a .jar or .war file");
        }
    }

    private static void processDirectory(String path, String decompilePath) throws IOException {
        String absolutePath = new File(path).getAbsolutePath();

        List<Future<?>> futures = new ArrayList<>();

        Files.walk(Paths.get(absolutePath))
                .filter(path1 -> {
                    String strPath = path1.toString();
                    return !strPath.contains(".java-decompile")
                        && !strPath.contains(".woodpecker")
                        && !strPath.contains("target" + File.separator + "classes");
                })
                .forEach(path1 -> {
                    String strPath = path1.toString();

                    if (strPath.endsWith(".jar") || strPath.endsWith(".war")) {
                        DecompileTask task = new DecompileTask(strPath, decompilePath, absolutePath);
                        futures.add(executor.submit(task));
                    }
                });
        
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                System.err.println("[ERROR] Task execution failed: " + e.getMessage());
            }
        }
    }

    private static class DecompileTask implements Callable<Void> {
        private final String jarPath;
        private final String decompilePath;
        private final String absolutePath;

        public DecompileTask(String jarPath, String decompilePath, String absolutePath) {
            this.jarPath = jarPath;
            this.decompilePath = decompilePath;
            this.absolutePath = absolutePath;
        }

        @Override
        public Void call() throws Exception {
            if (shouldDecompile(jarPath)) {
                try {
                    boolean isTargetJar = targetPackage == null || containsTargetPackage(jarPath);
                    DecompileUtil.decompileJar(jarPath, decompilePath, isTargetJar, targetPackage);
                    processedCount.incrementAndGet();
                    System.out.println("[DECOMPILED] " + jarPath);
                } catch (Exception e) {
                    failedCount.incrementAndGet();
                    System.err.println("[ERROR] Failed to decompile: " + jarPath + " - " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                skippedCount.incrementAndGet();
                System.out.println("[SKIPPED] " + jarPath);
            }
            return null;
        }
    }

    private static boolean shouldDecompile(String jarPath) {
        if (targetPackage == null) {
            return true;
        }
        return containsTargetPackage(jarPath);
    }

    private static boolean containsTargetPackage(String jarPath) {
        String packageName = targetPackage.replace('.', '/');
        String entryName = packageName + "/";
        
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jarPath)) {
            return jarFile.stream()
                    .anyMatch(entry -> entry.getName().startsWith(entryName));
        } catch (IOException e) {
            return false;
        }
    }
}
