package com.inferpkg.core;

import java.io.File;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class PackageInferencer {

    private static final List<String> IGNORED_PREFIXES = List.of(
        "java.", "javax.", "sun.", "jdk.", "org.springframework.",
        "org.apache.", "com.google.", "com.fasterxml.", "org.slf4j.",
        "ch.qos.", "org.junit.", "org.mockito.", "io.netty.",
        "com.aspose.", "com.itextpdf.", "javassist.", "org.bouncycastle.",
        "net.sf.", "org.hibernate.", "com.zaxxer."
    );

    public String inferBasePackage(List<String> appSources) {
        List<PackageCandidate> ranking = inferPackageRanking(appSources);
        if (ranking.isEmpty()) {
            return null;
        }
        return ranking.get(0).getPackageName();
    }

    public List<PackageCandidate> inferPackageRanking(List<String> appSources) {
        Map<String, Integer> packageCounts = new HashMap<>();

        for (String source : appSources) {
            File file = new File(source);

            if (file.isDirectory()) {
                scanDirectoryForPackages(file, "", packageCounts);
            } else {
                scanJarForPackages(source, packageCounts);
            }
        }

        int total = packageCounts.values().stream().mapToInt(Integer::intValue).sum();

        List<PackageCandidate> ranking = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : packageCounts.entrySet()) {
            double pct = total > 0 ? (entry.getValue() * 100.0 / total) : 0.0;
            ranking.add(new PackageCandidate(entry.getKey(), entry.getValue(), pct));
        }

        ranking.sort((e1, e2) -> Integer.compare(e2.getClassCount(), e1.getClassCount()));
        return ranking;
    }

    public Map<String, PackageCandidate> inferPerSource(List<String> appSources) {
        Map<String, PackageCandidate> result = new LinkedHashMap<>();
        for (String source : appSources) {
            List<PackageCandidate> ranking = inferPackageRanking(List.of(source));
            if (ranking.isEmpty()) {
                result.put(source, null);
            } else {
                result.put(source, ranking.get(0));
            }
        }
        return result;
    }

    private void scanJarForPackages(String jarPath, Map<String, Integer> counts) {
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                updatePackageCount(entry.getName(), counts);
            }
        } catch (Exception e) {
            // ignore unreadable jars
        }
    }

    private void scanDirectoryForPackages(File dir, String currentPath, Map<String, Integer> counts) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectoryForPackages(f, currentPath + f.getName() + "/", counts);
            } else {
                updatePackageCount(currentPath + f.getName(), counts);
            }
        }
    }

    private void updatePackageCount(String filePath, Map<String, Integer> counts) {
        if (!filePath.endsWith(".class")) return;

        String pkgPath = filePath.replace('\\', '/');
        int lastSlash = pkgPath.lastIndexOf('/');
        if (lastSlash == -1) return;

        String pkg = pkgPath.substring(0, lastSlash).replace('/', '.');

        String[] parts = pkg.split("\\.");
        if (parts.length < 2) return;

        String basePkg = parts[0] + "." + parts[1];

        for (String ignore : IGNORED_PREFIXES) {
            if (basePkg.startsWith(ignore) || (basePkg + ".").startsWith(ignore)) {
                return;
            }
        }

        counts.put(basePkg, counts.getOrDefault(basePkg, 0) + 1);
    }
}
