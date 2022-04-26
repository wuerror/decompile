import com.github.kwart.jd.IOUtils;
import com.github.kwart.jd.JavaDecompiler;
import com.github.kwart.jd.JavaDecompilerConstants;
import com.github.kwart.jd.cli.CLIArguments;
import com.github.kwart.jd.cli.ExtCommander;
import com.github.kwart.jd.cli.InputOutputPair;
import com.github.kwart.jd.input.ClassFileInput;
import com.github.kwart.jd.input.DirInput;
import com.github.kwart.jd.input.JDInput;
import com.github.kwart.jd.input.ZipFileInput;
import com.github.kwart.jd.loader.ByteArrayLoader;
import com.github.kwart.jd.output.*;

import javax.imageio.IIOException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Decompiler {
    public static String decompileOneClass(String classFilePath) {
        try {
            CLIArguments cliArguments = new CLIArguments();
            JavaDecompiler javaDecompiler = new JavaDecompiler(cliArguments);
            InputStream inputStream = new FileInputStream(classFilePath);
            ByteArrayLoader bal = new ByteArrayLoader(inputStream, classFilePath);
            String aaa = javaDecompiler.decompileClass(bal, classFilePath);
            return aaa;
        } catch (Exception var12) {
            return null;
        }
    }

    public static void decompileClassFile2JavaFile(String classFilePath, String javaFilePath) {
        try {
            String decompiledStr = Decompiler.decompileOneClass(classFilePath);
            PrintStream printStream = new PrintStream(new FileOutputStream(javaFilePath));
            if (decompiledStr != null) {
                printStream.write(decompiledStr.getBytes(StandardCharsets.UTF_8));
            }
            printStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void decompileOneJar2Path(String jarPath, String targetPath) {
        try {
            String[] args = new String[] {jarPath, "-od", targetPath};
            CLIArguments cliArguments = new CLIArguments();
            JavaDecompiler javaDecompiler = new JavaDecompiler(cliArguments);
            ExtCommander jCmd = initCommander(args, cliArguments);
            JDOutput outputPlugin = initOutput(cliArguments);
            File file = new File(jarPath);
            InputOutputPair inOut = getInOutPlugins(file, outputPlugin);
            inOut.getJdInput().decompile(javaDecompiler, inOut.getJdOutput());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static ExtCommander initCommander(String[] args, CLIArguments cliArguments) {
        ExtCommander jCmd = new ExtCommander(cliArguments);
        jCmd.setAcceptUnknownOptions(true);
        jCmd.parse(args);
        jCmd.setProgramName("java -jar jd-cli.jar");
        jCmd.setUsageHead("jd-cli version " + JavaDecompilerConstants.VERSION + " - Copyright (C) 2015-2021 Josef Cacek\n\nThe jd-cli is a command line interface for the Java Decompiler");
        jCmd.setUsageTail("GPLv3");
        return jCmd;
    }

    private static JDOutput initOutput(CLIArguments cliArguments) {
        JDOutput outputPlugin = null;
        if (cliArguments.isOutputPluginSpecified()) {
            List<JDOutput> outPlugins = new ArrayList();
            if (cliArguments.isConsoleOut()) {
                outPlugins.add(new PrintStreamOutput(System.out));
            }
            File zipFile = cliArguments.getZipOutFile();
            if (zipFile != null) {
                try {
                    outPlugins.add(new ZipOutput(zipFile));
                } catch (Exception var7) {
                    var7.printStackTrace();
                }
            }

            File dir = cliArguments.getDirOutFile();
            if (dir !=null) {
                try {
                    outPlugins.add(new DirOutput(dir));
                } catch (Exception var6) {
                    var6.printStackTrace();
                }
            }
            if (outPlugins.size() > 0) {
                outputPlugin = new MultiOutput(outPlugins);
            }
        }
        return outputPlugin;
    }

    public static InputOutputPair getInOutPlugins(File inputFile, JDOutput outPlugin) throws NullPointerException, IOException {
        JDInput jdIn = null;
        JDOutput jdOut = null;
        if (inputFile.isDirectory()) {
            jdIn = new DirInput(inputFile.getPath());
            jdOut = new DirOutput(new File(inputFile.getName() + ".src"));
        } else {
            DataInputStream dis = new DataInputStream(new FileInputStream(inputFile));
            boolean var5 = false;

            int magic;
            try {
                magic = dis.readInt();
            } finally {
                IOUtils.closeQuietly(dis);
            }

            switch (magic) {
                case -889275714:
                    jdIn = new ClassFileInput(inputFile.getPath());
                    jdOut = new PrintStreamOutput(System.out);
                    break;
                case 1347093252:
                    jdIn = new ZipFileInput(inputFile.getPath());
                    String decompiledZipName = inputFile.getName();
                    int suffixPos = decompiledZipName.lastIndexOf(".");
                    if (suffixPos >= 0) {
                        decompiledZipName = decompiledZipName.substring(0, suffixPos) + ".src" + decompiledZipName.substring(suffixPos);
                    } else {
                        decompiledZipName = decompiledZipName + ".src";
                    }

                    jdOut = new ZipOutput(new File(decompiledZipName));
                    break;
                default:
                    throw new IllegalArgumentException("File type of was not recognized: " + inputFile);
            }
        }
        return new InputOutputPair((JDInput) jdIn, outPlugin, (JDOutput) jdOut);
    }
}
