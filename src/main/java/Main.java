import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Main {
    public static void main(String[] args) throws IOException {
        ArgumentParser parser = ArgumentParsers.newArgumentParser("decompile").description("decompile utility");
        parser.addArgument("path").required(true).help("decompile all jar and class file in this directory");
        Namespace ns = null;
        try {
            ns = parser.parseArgs(args);
            String path = (String) ns.get("path");
            processDirectory(path);
        } catch (ArgumentParserException e) {
            parser.printHelp();
        }
    }

    private static void processDirectory(String path) throws IOException {
        String absolutePath = new File(path).getAbsolutePath();
        Files.walk(Paths.get(absolutePath)).filter(path1 -> {
            String strPath = path1.toString();
            if (strPath.contains(".java-decompile") || strPath.contains(".woodpecker")) {
                return false;
            } else {
                return true;
            }
        }).forEach(path1 -> {
            String strPath = path1.toString();
            String targetPath = strPath.replace(absolutePath, absolutePath + File.separator + ".java-decompile");
            try {
                processFile(strPath, targetPath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private static boolean hasSubBytesInIndex(byte[] a, byte[] b, int index) {
        if (a.length < b.length || index >= a.length) {
            return false;
        }
        for (int j=0;j<b.length;j++) {
            if (a[index + j] != b[j]) {
                return false;
            }
        }
        return true;
    }

    private static void processFile(String filePath, String targetPath) throws IOException {
        if (filePath.endsWith(".class")) {
            targetPath = targetPath.replace(".class", ".java");
            String targetDirectory = new File(targetPath).getParent();
            new File(targetDirectory).mkdirs();
            Decompiler.decompileClassFile2JavaFile(filePath, targetPath);
        } else if (filePath.endsWith(".jar")) {
            Decompiler.decompileOneJar2Path(filePath, targetPath);
        } else {
            Files.copy(Paths.get(filePath), Paths.get(targetPath));
        }
    }
}
