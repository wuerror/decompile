package com.inferpkg.core;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class FatJarExtractor {

    private static final String TEMP_DIR_PREFIX = "inferpkg_";

    private final List<File> tempDirs = new ArrayList<>();

    public boolean isFatJar(String jarPath) {
        try (JarFile jar = new JarFile(jarPath)) {
            return jar.getEntry("BOOT-INF/classes/") != null ||
                   jar.getEntry("WEB-INF/classes/") != null;
        } catch (IOException e) {
            return false;
        }
    }

    public String extractClassesToTemp(String jarPath) {
        try {
            File tempDir = createTempDir();
            tempDirs.add(tempDir);

            File jarFile = new File(jarPath);
            File classesDir = new File(tempDir, jarFile.getName() + "_classes");
            classesDir.mkdirs();

            extractClasses(jarPath, classesDir);
            return classesDir.getAbsolutePath();
        } catch (IOException e) {
            System.err.println("[WARN] Failed to extract classes from: " + jarPath + " - " + e.getMessage());
            return null;
        }
    }

    public List<String> listEmbeddedLibs(String jarPath) {
        List<String> libs = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if ((name.startsWith("BOOT-INF/lib/") || name.startsWith("WEB-INF/lib/"))
                        && name.endsWith(".jar")) {
                    libs.add(name);
                }
            }
        } catch (IOException e) {
            System.err.println("[WARN] Failed to list embedded libs in: " + jarPath + " - " + e.getMessage());
        }
        return libs;
    }

    private void extractClasses(String jarPath, File destDir) throws IOException {
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                String innerPath = null;
                if (name.startsWith("BOOT-INF/classes/")) {
                    innerPath = name.substring("BOOT-INF/classes/".length());
                } else if (name.startsWith("WEB-INF/classes/")) {
                    innerPath = name.substring("WEB-INF/classes/".length());
                }

                if (innerPath == null || innerPath.isEmpty()) {
                    continue;
                }

                File outFile = new File(destDir, innerPath);
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (InputStream in = jar.getInputStream(entry);
                         OutputStream out = new FileOutputStream(outFile)) {
                        copy(in, out);
                    }
                }
            }
        }
    }

    private File createTempDir() {
        try {
            return Files.createTempDirectory(TEMP_DIR_PREFIX).toFile();
        } catch (IOException e) {
            throw new RuntimeException("Could not create temp directory for JAR unpacking", e);
        }
    }

    public void cleanup() {
        for (File dir : tempDirs) {
            deleteDir(dir);
        }
        tempDirs.clear();
    }

    private void deleteDir(File dir) {
        if (dir != null && dir.exists()) {
            try (Stream<Path> walk = Files.walk(dir.toPath())) {
                walk.sorted((a, b) -> b.compareTo(a))
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (IOException e) {
                // ignore cleanup errors
            }
        }
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
}
