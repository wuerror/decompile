import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DecompileUtil {

    public static void decompileJar(String jarPath, String outputDir, boolean isTargetJar) throws IOException {
        decompileJar(jarPath, outputDir, isTargetJar, null);
    }

    public static void decompileJar(String jarPath, String outputDir, boolean isTargetJar, String targetPackage) throws IOException {
        File output = new File(outputDir);
        output.mkdirs();
        
        File jarFile = new File(jarPath);
        String jarName = jarFile.getName();
        String baseName = jarName.endsWith(".war") ? jarName.replace(".war", "") : jarName.replace(".jar", "");
        File outputJarDir = new File(outputDir, baseName + "_src");
        outputJarDir.mkdirs();
        
        IResultSaver saver = new ConsoleResultSaver(outputJarDir.getAbsolutePath());
        
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
        options.put(IFernflowerPreferences.DECOMPILER_COMMENTS, "1");
        options.put(IFernflowerPreferences.DECOMPILE_INNER, "1");
        options.put(IFernflowerPreferences.DECOMPILE_ASSERTIONS, "1");
        options.put(IFernflowerPreferences.HIDE_EMPTY_SUPER, "1");
        options.put(IFernflowerPreferences.DECOMPILE_ENUM, "1");
        options.put(IFernflowerPreferences.DECOMPILE_PREVIEW, "1");
        options.put(IFernflowerPreferences.REMOVE_GET_CLASS_NEW, "1");
        options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
        
        Fernflower engine = new Fernflower(saver, options, new IFernflowerLogger() {
            @Override
            public void writeMessage(String message, Severity severity) {
                if (severity == Severity.ERROR) {
                    System.err.println("[Vineflower] " + message);
                }
            }

            @Override
            public void writeMessage(String message, Severity severity, Throwable t) {
                if (severity == Severity.ERROR) {
                    System.err.println("[Vineflower] " + message + " - " + t.getMessage());
                }
            }
        });
        
        engine.addSource(jarFile);
        engine.decompileContext();
        engine.clearContext();
        
        if (isTargetJar) {
            copyNonClassFiles(jarPath, outputJarDir.getAbsolutePath());
        }

        // 递归处理嵌套的jar包（如Spring Boot的BOOT-INF/lib/*.jar）
        extractAndDecompileNestedJars(jarPath, outputDir, targetPackage);
    }

    private static void extractAndDecompileNestedJars(String jarPath, String outputDir, String targetPackage) throws IOException {
        List<String> nestedJarPaths = new ArrayList<>();

        try (ZipFile zipFile = new ZipFile(jarPath)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                // 检查是否是嵌套的jar包
                if ((name.startsWith("BOOT-INF/lib/") || name.startsWith("WEB-INF/lib/") || name.startsWith("lib/"))
                        && name.endsWith(".jar") && !entry.isDirectory()) {
                    nestedJarPaths.add(name);
                }
            }

            // 提取并反编译嵌套的jar包
            for (String nestedJarPath : nestedJarPaths) {
                ZipEntry entry = zipFile.getEntry(nestedJarPath);
                if (entry == null) continue;

                // 从路径中提取jar文件名（如从 BOOT-INF/lib/spring-boot.jar 提取 spring-boot.jar）
                String jarFileName = nestedJarPath.substring(nestedJarPath.lastIndexOf('/') + 1);

                // 创建临时文件，使用原始jar名称
                File tempDir = new File(System.getProperty("java.io.tmpdir"), "decompile-temp");
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

                // 检查是否包含目标package（如果指定了）
                boolean shouldDecompile = targetPackage == null || containsTargetPackage(tempJar.getAbsolutePath(), targetPackage);

                if (shouldDecompile) {
                    try {
                        System.out.println("[NESTED JAR] Decompiling: " + nestedJarPath);
                        decompileJar(tempJar.getAbsolutePath(), outputDir, true, targetPackage);
                    } catch (Exception e) {
                        System.err.println("[ERROR] Failed to decompile nested jar: " + nestedJarPath + " - " + e.getMessage());
                    }
                } else {
                    System.out.println("[NESTED JAR SKIPPED] " + nestedJarPath + " (does not contain target package)");
                }

                tempJar.delete();
            }
        }
    }

    private static boolean containsTargetPackage(String jarPath, String targetPackage) {
        if (targetPackage == null) {
            return true;
        }

        String packageName = targetPackage.replace('.', '/');
        String entryName = packageName + "/";

        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jarPath)) {
            return jarFile.stream()
                    .anyMatch(entry -> entry.getName().startsWith(entryName));
        } catch (IOException e) {
            return false;
        }
    }
    
    private static void copyNonClassFiles(String jarPath, String outputDir) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarPath)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                
                if (name.endsWith(".class") || name.endsWith("/") || 
                    name.equals("META-INF/") || name.startsWith("META-INF/MANIFEST.MF") ||
                    name.startsWith("META-INF/")) {
                    continue;
                }
                
                File outputFile = new File(outputDir, name);
                if (!entry.isDirectory()) {
                    outputFile.getParentFile().mkdirs();
                    try (InputStream is = zipFile.getInputStream(entry);
                         FileOutputStream os = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[4096];
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
    
    private static class ConsoleResultSaver implements IResultSaver {
        private final String outputPath;
        
        public ConsoleResultSaver(String outputPath) {
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
                System.err.println("[ERROR] Failed to copy file: " + source + " - " + e.getMessage());
            }
        }

        @Override
        public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
            try {
                File file = new File(outputPath, path + "/" + entryName);
                file.getParentFile().mkdirs();
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                    writer.write(content);
                }
            } catch (IOException e) {
                System.err.println("[ERROR] Failed to save class file: " + qualifiedName + " - " + e.getMessage());
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
                System.err.println("[ERROR] Failed to save class entry: " + qualifiedName + " - " + e.getMessage());
            }
        }

        @Override
        public void closeArchive(String path, String archiveName) {
        }

        @Override
        public void close() throws IOException {
        }
    }
}
