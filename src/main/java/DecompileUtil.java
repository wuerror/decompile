import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class DecompileUtil {
    public static void decompileJar(String jarPath, String outputDir, boolean isTargetJar) throws IOException {
        decompileJar(jarPath, outputDir, isTargetJar, null, 1);
    }

    public static void decompileJar(String jarPath, String outputDir, boolean isTargetJar, String targetPackage) throws IOException {
        decompileJar(jarPath, outputDir, isTargetJar, targetPackage, 1);
    }

    public static void decompileJar(String jarPath, String outputDir, boolean isTargetJar, String targetPackage, int threadCount) throws IOException {
        decompileJar(jarPath, outputDir, isTargetJar, targetPackage, threadCount, true);
    }

    private static void decompileJar(
            String jarPath,
            String outputDir,
            boolean isTargetJar,
            String targetPackage,
            int threadCount,
            boolean processNestedJars
    ) throws IOException {
        File output = new File(outputDir);
        output.mkdirs();

        File jarFile = new File(jarPath);
        String jarName = jarFile.getName();
        String baseName = jarName.endsWith(".war") ? jarName.replace(".war", "") : jarName.replace(".jar", "");
        File outputRoot = new File(outputDir);
        File outputJarDir = outputRoot.getName().equals(baseName + "_src")
                ? outputRoot
                : new File(outputRoot, baseName + "_src");
        outputJarDir.mkdirs();

        if (targetPackage != null) {
            decompileJarFiltered(jarFile, outputJarDir, targetPackage, threadCount);
        } else {
            IResultSaver saver = new ConsoleResultSaver(outputJarDir.getAbsolutePath());
            Fernflower engine = createFernflower(saver, threadCount);
            try {
                engine.addSource(jarFile);
                engine.decompileContext();
            } finally {
                engine.clearContext();
            }
        }

        if (isTargetJar) {
            copyNonClassFiles(jarPath, outputJarDir.getAbsolutePath());
        }

        if (processNestedJars) {
            extractAndDecompileNestedJars(jarPath, outputJarDir.getAbsolutePath(), targetPackage, threadCount);
        }
    }

    private static void decompileJarFiltered(File jarFile, File outputJarDir, String targetPackage, int threadCount) throws IOException {
        String pkg = targetPackage.replace('.', '/') + "/";
        String[] prefixes = {pkg, "BOOT-INF/classes/" + pkg, "WEB-INF/classes/" + pkg};

        Path tempDir = Files.createTempDirectory("decompile-filter-");
        try {
            int count = 0;
            try (ZipFile zip = new ZipFile(jarFile)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    if (!name.endsWith(".class")) {
                        continue;
                    }

                    String stripped = null;
                    for (String p : prefixes) {
                        if (name.startsWith(p)) {
                            stripped = name.substring(p.length() - pkg.length());
                            break;
                        }
                    }
                    if (stripped == null) {
                        continue;
                    }

                    Path dest = tempDir.resolve(stripped);
                    Files.createDirectories(dest.getParent());
                    try (InputStream is = zip.getInputStream(entry)) {
                        Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    count++;
                }
            }

            if (count == 0) {
                return;
            }

            IResultSaver saver = new ConsoleResultSaver(outputJarDir.getAbsolutePath());
            Fernflower engine = createFernflower(saver, threadCount);
            try {
                engine.addSource(tempDir.toFile());
                engine.decompileContext();
            } finally {
                engine.clearContext();
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                }
            });
        } catch (IOException ignore) {
        }
    }

    private static void extractAndDecompileNestedJars(String jarPath, String parentOutputDir, String targetPackage, int threadCount) throws IOException {
        List<String> nestedJarPaths = new ArrayList<>();
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "decompile-temp-" + Thread.currentThread().getId());

        try {
            try (ZipFile zipFile = new ZipFile(jarPath)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();

                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();

                    if ((name.startsWith("BOOT-INF/lib/") || name.startsWith("WEB-INF/lib/") || name.startsWith("lib/"))
                            && name.endsWith(".jar") && !entry.isDirectory()) {
                        nestedJarPaths.add(name);
                    }
                }

                for (String nestedJarPath : nestedJarPaths) {
                    ZipEntry entry = zipFile.getEntry(nestedJarPath);
                    if (entry == null) {
                        continue;
                    }

                    String jarFileName = nestedJarPath.substring(nestedJarPath.lastIndexOf('/') + 1);
                    String dirPath = nestedJarPath.substring(0, nestedJarPath.lastIndexOf('/'));

                    tempDir.mkdirs();
                    File tempJar = new File(tempDir, jarFileName);

                    try (InputStream is = zipFile.getInputStream(entry);
                         FileOutputStream fos = new FileOutputStream(tempJar)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }

                    try {
                        boolean shouldDecompile = targetPackage == null || containsTargetPackage(tempJar.getAbsolutePath(), targetPackage);

                        if (shouldDecompile) {
                            System.out.println("[NESTED JAR] Decompiling: " + nestedJarPath);

                            File nestedOutputDir = new File(parentOutputDir, dirPath);
                            nestedOutputDir.mkdirs();

                            try {
                                decompileJarToSpecificDir(
                                        tempJar.getAbsolutePath(),
                                        nestedOutputDir.getAbsolutePath(),
                                        jarFileName,
                                        true,
                                        targetPackage,
                                        threadCount
                                );
                            } catch (Exception e) {
                                throw new IOException("Failed to decompile nested jar: " + nestedJarPath, e);
                            }
                        } else {
                            System.out.println("[NESTED JAR SKIPPED] " + nestedJarPath + " (does not contain target package)");
                        }
                    } finally {
                        tempJar.delete();
                    }
                }
            }
        } finally {
            deleteRecursively(tempDir.toPath());
        }
    }

    private static void decompileJarToSpecificDir(
            String jarPath,
            String outputDir,
            String jarFileName,
            boolean isTargetJar,
            String targetPackage,
            int threadCount
    ) throws IOException {
        File output = new File(outputDir);
        output.mkdirs();

        File jarFile = new File(jarPath);
        String baseName = jarFileName.endsWith(".war") ? jarFileName.replace(".war", "") : jarFileName.replace(".jar", "");
        File outputJarDir = new File(outputDir, baseName + "_src");
        outputJarDir.mkdirs();

        if (targetPackage != null) {
            decompileJarFiltered(jarFile, outputJarDir, targetPackage, threadCount);
        } else {
            IResultSaver saver = new ConsoleResultSaver(outputJarDir.getAbsolutePath());
            Fernflower engine = createFernflower(saver, threadCount);
            try {
                engine.addSource(jarFile);
                engine.decompileContext();
            } finally {
                engine.clearContext();
            }
        }

        if (isTargetJar) {
            copyNonClassFiles(jarPath, outputJarDir.getAbsolutePath());
        }
    }

    public static boolean containsTargetPackage(String jarPath, String targetPackage) {
        if (targetPackage == null) {
            return true;
        }

        String pkg = targetPackage.replace('.', '/') + "/";
        String[] prefixes = {pkg, "BOOT-INF/classes/" + pkg, "WEB-INF/classes/" + pkg};
        List<String> nestedJarNames = new ArrayList<>();

        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jarPath)) {
            Enumeration<java.util.jar.JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String name = entry.getName();
                for (String p : prefixes) {
                    if (name.startsWith(p)) {
                        return true;
                    }
                }
                if (!entry.isDirectory() && name.endsWith(".jar")
                        && (name.startsWith("BOOT-INF/lib/") || name.startsWith("WEB-INF/lib/") || name.startsWith("lib/"))) {
                    nestedJarNames.add(name);
                }
            }

            for (String nested : nestedJarNames) {
                java.util.jar.JarEntry ne = jarFile.getJarEntry(nested);
                if (ne == null) {
                    continue;
                }
                try (InputStream is = jarFile.getInputStream(ne);
                     ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry inner;
                    while ((inner = zis.getNextEntry()) != null) {
                        if (inner.getName().startsWith(pkg)) {
                            return true;
                        }
                    }
                } catch (IOException ignore) {
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    public static void decompileClassFileInPlace(String classPath) throws IOException {
        decompileClassFileInPlace(classPath, 1);
    }

    public static void decompileClassFileInPlace(String classPath, int threadCount) throws IOException {
        File classFile = new File(classPath);
        if (!classFile.exists() || !classFile.isFile()) {
            throw new FileNotFoundException("Class file not found: " + classPath);
        }

        File outputDir = classFile.getParentFile();
        if (outputDir == null) {
            outputDir = new File(".");
        }

        decompileSourcesInPlace(Collections.singletonList(classFile), outputDir, threadCount);
    }

    public static void decompileClassDirectory(String sourcePath, String outputPath, int threadCount) throws IOException {
        File dir = new File(sourcePath);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IOException("Directory not found: " + sourcePath);
        }

        List<File> classFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir.toPath())) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String str = path.toString();
                        return !hasPathSegmentEndingWith(str, "_src")
                                && !str.contains(".woodpecker")
                                && !str.contains("target" + File.separator + "classes")
                                && str.endsWith(".class");
                    })
                    .forEach(path -> classFiles.add(path.toFile()));
        }

        if (classFiles.isEmpty()) {
            throw new IOException("No .class files found under directory: " + sourcePath);
        }

        File outputDir = new File(outputPath);
        outputDir.mkdirs();
        IResultSaver saver = new ConsoleResultSaver(outputDir.getAbsolutePath());
        Fernflower engine = createFernflower(saver, threadCount);
        try {
            for (File source : classFiles) {
                engine.addSource(source);
            }
            engine.decompileContext();
        } catch (Exception | OutOfMemoryError e) {
            throw new IOException("Decompilation failed: " + e.getMessage(), e);
        } finally {
            engine.clearContext();
        }
    }

    public static void decompileClassDirectoryInPlace(String directoryPath) throws IOException {
        decompileClassDirectoryInPlace(directoryPath, 1);
    }

    public static void decompileClassDirectoryInPlace(String directoryPath, int threadCount) throws IOException {
        File dir = new File(directoryPath);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IOException("Directory not found: " + directoryPath);
        }

        List<File> classFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir.toPath())) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String str = path.toString();
                        return !hasPathSegmentEndingWith(str, "_src")
                                && !str.contains(".woodpecker")
                                && !str.contains("target" + File.separator + "classes")
                                && str.endsWith(".class");
                    })
                    .forEach(path -> classFiles.add(path.toFile()));
        }

        if (classFiles.isEmpty()) {
            throw new IOException("No .class files found under directory: " + directoryPath);
        }

        decompileSourcesInPlace(classFiles, dir, threadCount);
    }

    private static void decompileSourcesInPlace(List<File> sources, File outputDir, int threadCount) throws IOException {
        if (sources.isEmpty()) {
            return;
        }

        IResultSaver saver = new ConsoleResultSaver(outputDir.getAbsolutePath());
        Fernflower engine = createFernflower(saver, threadCount);
        try {
            for (File source : sources) {
                engine.addSource(source);
            }
            engine.decompileContext();
        } catch (Exception | OutOfMemoryError e) {
            throw new IOException("Decompilation failed: " + e.getMessage(), e);
        } finally {
            engine.clearContext();
        }
    }

    private static void copyNonClassFiles(String jarPath, String outputDir) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarPath)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            byte[] buffer = new byte[4096];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.endsWith(".class") || name.endsWith("/") ||
                        name.equals("META-INF/") || name.startsWith("META-INF/MANIFEST.MF") ||
                        name.startsWith("META-INF/") ||
                        (name.endsWith(".jar") && (name.startsWith("BOOT-INF/lib/") || name.startsWith("WEB-INF/lib/") || name.startsWith("lib/")))) {
                    continue;
                }

                File outputFile = new File(outputDir, name);
                if (!entry.isDirectory()) {
                    outputFile.getParentFile().mkdirs();
                    try (InputStream is = zipFile.getInputStream(entry);
                         FileOutputStream os = new FileOutputStream(outputFile)) {
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }
                } else {
                    outputFile.mkdirs();
                }
            }
        }
    }

    private static Fernflower createFernflower(IResultSaver saver, int threadCount) {
        Map<String, Object> options = new HashMap<>();
        options.put(IFernflowerPreferences.REMOVE_BRIDGE, "0");
        options.put(IFernflowerPreferences.DUMP_CODE_LINES, "1");
        options.put(IFernflowerPreferences.IGNORE_INVALID_BYTECODE, "1");
        options.put(IFernflowerPreferences.VERIFY_ANONYMOUS_CLASSES, "0");
        options.put(IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH, "0");
        options.put(IFernflowerPreferences.TERNARY_CONDITIONS, "1");
        options.put(IFernflowerPreferences.FORCE_JSR_INLINE, "1");
        options.put(IFernflowerPreferences.DUMP_BYTECODE_ON_ERROR, "1");
        options.put(IFernflowerPreferences.DUMP_EXCEPTION_ON_ERROR, "1");
        options.put(IFernflowerPreferences.DECOMPILER_COMMENTS, "0");
        options.put(IFernflowerPreferences.DECOMPILE_INNER, "1");
        options.put(IFernflowerPreferences.DECOMPILE_ASSERTIONS, "1");
        options.put(IFernflowerPreferences.HIDE_EMPTY_SUPER, "1");
        options.put(IFernflowerPreferences.DECOMPILE_ENUM, "1");
        options.put(IFernflowerPreferences.DECOMPILE_PREVIEW, "1");
        options.put(IFernflowerPreferences.REMOVE_GET_CLASS_NEW, "1");
        options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
        options.put(IFernflowerPreferences.SKIP_EXTRA_FILES, "1");
        options.put(IFernflowerPreferences.THREADS, String.valueOf(Math.max(1, threadCount)));

        return new Fernflower(saver, options, new IFernflowerLogger() {
            @Override
            public void writeMessage(String message, Severity severity) {
                if (severity == Severity.ERROR) {
                    System.err.println("[Vineflower] " + message);
                } else if (severity == Severity.WARN) {
                    System.out.println("[Vineflower WARN] " + message);
                }
            }

            @Override
            public void writeMessage(String message, Severity severity, Throwable t) {
                if (severity == Severity.ERROR) {
                    System.err.println("[Vineflower] " + message + " - " + t.getMessage());
                } else if (severity == Severity.WARN) {
                    System.out.println("[Vineflower WARN] " + message + " - " + t.getMessage());
                }
            }
        });
    }

    private static class ConsoleResultSaver implements IResultSaver {
        private final String outputPath;

        private ConsoleResultSaver(String outputPath) {
            this.outputPath = outputPath;
        }

        @Override
        public void saveFolder(String path) {
            new File(outputPath, path).mkdirs();
        }

        @Override
        public void copyFile(String source, String path, String entryName) {
            try {
                File dest = new File(outputPath, path + "/" + entryName);
                dest.getParentFile().mkdirs();
                Files.copy(Paths.get(source), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to copy file: " + source, e);
            }
        }

        @Override
        public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
            try {
                String effectivePath = (path == null || path.isEmpty()) && qualifiedName != null && qualifiedName.contains("/")
                        ? qualifiedName.substring(0, qualifiedName.lastIndexOf('/'))
                        : path;
                File file = new File(outputPath, effectivePath + "/" + entryName);
                file.getParentFile().mkdirs();
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                    writer.write(content);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to save class file: " + qualifiedName, e);
            }
        }

        @Override
        public void createArchive(String path, String archiveName, Manifest manifest) {
        }

        @Override
        public void saveDirEntry(String path, String archiveName, String entryName) {
        }

        @Override
        public void copyEntry(String source, String path, String archiveName, String entry) {
        }

        @Override
        public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
            try {
                File file = new File(outputPath, path + "/" + entryName);
                file.getParentFile().mkdirs();
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                    writer.write(content);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to save class entry: " + qualifiedName, e);
            }
        }

        @Override
        public void closeArchive(String path, String archiveName) {
        }

        @Override
        public void close() throws IOException {
        }
    }

    private static boolean hasPathSegmentEndingWith(String path, String suffix) {
        for (String segment : path.split("[/\\\\]")) {
            if (segment.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
