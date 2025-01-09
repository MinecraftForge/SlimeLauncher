/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.launcher;

import joptsimple.AbstractOptionSpec;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.data.json.MinecraftVersion;
import net.minecraftforge.util.logging.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Main {
    public static void main(String[] args) throws Throwable {
        long start = System.nanoTime();
        Launcher launcher;
        try {
            Log.capture();
            launcher = run(args);
        } catch (Throwable e) {
            Log.release();
            throw e;
        }

        long total = (System.nanoTime() - start) / 1_000_000;
        if (Log.isCapturing()) {
            Log.drop();
            Log.INFO.print("Slime Launcher setup is up-to-date");
        } else {
            Log.INFO.print("Slime Launcher has finished setting up");
        }
        Log.INFO.println(", took " + total + "ms\n");

        launcher.run();
    }

    private static Launcher run(String[] args) throws Throwable {
        OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();

        // help message
        AbstractOptionSpec<Void> helpO = parser
            .accepts("help", "Displays this help message and exits")
            .forHelp();

        // cache directory
        ArgumentAcceptingOptionSpec<File> cache0 = parser
            .accepts("cache", "Directory to store launcher metadata")
            .withRequiredArg().ofType(File.class);

        // assets directory
        ArgumentAcceptingOptionSpec<File> assetsO = parser
            .accepts("assets", "Directory to store assets")
            .withRequiredArg().ofType(File.class).defaultsTo(Constants.ASSETS_DIR);

        // assets repo
        ArgumentAcceptingOptionSpec<String> assetsRepo0 = parser
            .accepts("assets-repo", "The assets repository (download server)")
            .withRequiredArg().ofType(String.class).defaultsTo(Constants.RESOURCES_URL);

        // metadata
        ArgumentAcceptingOptionSpec<File> metadataZip0 = parser
            .accepts("metadata", "The metadata.zip to use for runs")
            .withRequiredArg().ofType(File.class);

        // main
        ArgumentAcceptingOptionSpec<String> mainClassO = parser
            .accepts("main", "The main class to run")
            .withRequiredArg().ofType(String.class);

        JarVersionInfo.of(Main.class).hello(Log::info, false, true);

        SplitArgs split = new SplitArgs(args);

        OptionSet options = parser.parse(split.sl);
        if (options.has(helpO)) {
            parser.printHelpOn(Log.INFO);
            Log.info("To pass arguments into the main class,\n" +
                "add '--' after the Slime Launcher arguments,\n" +
                "followed by your main arguments.");
            throw new IllegalArgumentException("Incomplete or invalid arguments");
        }

        File cache = options.valueOf(cache0);
        File assets = options.valueOf(assetsO);
        String assetsRepo = options.valueOf(assetsRepo0);
        // TODO [SlimeLauncher][Jonathing] CHANGE THIS TO DIR! It is already extracted by FG7!
        File metadataZip = options.valueOf(metadataZip0);
        String mainClass = options.valueOf(mainClassO);

        MinecraftVersion versionJson;
        try (ZipFile zip = new ZipFile(metadataZip)) {
            versionJson = JsonData.minecraftVersion(
                extract(zip, "minecraft/version.json", cache)
            );
        }

        Log.info("Checking assets");
        byte indent = Log.push();
        DownloadAssets.download(assetsRepo, assets, versionJson);
        Log.pop(indent);

        Log.info("Looking for main class: " + mainClass);
        MethodHandle mainMethod;
        try {
            Class<?> main = Class.forName(mainClass);
            mainMethod = MethodHandles.lookup().findStatic(main, "main", MethodType.methodType(void.class, String[].class));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("Could not find main class!", e);
        }

        Log.info("Sanitizing Minecraft arguments");
        Arrays.asList(split.mc).replaceAll(s -> s
            .replace("{asset_index}", versionJson.assetIndex.id)
            .replace("{assets_root}", assets.getAbsolutePath()));

        return new Launcher(mainClass, mainMethod, split.mc);
    }

    private static final class Launcher {
        private final String name;
        private final MethodHandle main;
        private final String[] args;

        private Launcher(String name, MethodHandle main, String[] args) {
            this.name = name;
            this.main = main;
            this.args = args;
        }

        @SuppressWarnings("ConfusingArgumentToVarargsMethod")
        private void run() throws Throwable {
            Log.info("Launching using main class: " + this.name);
            this.main.invokeExact(this.args);
        }
    }

    private static final class SplitArgs {
        private final String[] sl;
        private final String[] mc;

        private SplitArgs(String[] args) {
            // we're looking for the first "--"
            int splitIdx = Arrays.asList(args).indexOf("--");

            if (splitIdx < 0) {
                this.sl = args;
                this.mc = new String[0];
            } else {
                this.sl = new String[splitIdx];
                this.mc = new String[args.length - splitIdx - 1];
                System.arraycopy(args, 0, this.sl, 0, splitIdx);
                System.arraycopy(args, splitIdx + 1, this.mc, 0, args.length - splitIdx - 1);
            }
        }
    }

    private static File extract(ZipFile zip, String name, File cache) throws IOException {
        File metadataDir = new File(cache, "metadata");
        if (!metadataDir.exists() && !metadataDir.mkdirs())
            throw new IllegalStateException("Failed to create directory: " + metadataDir.getAbsolutePath());

        ZipEntry entry = zip.getEntry(name);
        if (entry == null)
            throw new FileNotFoundException("Missing " + name + " in " + zip.getName());

        File output = new File(metadataDir, name);
        File outputDir = output.getParentFile();
        if (!outputDir.exists() && !outputDir.mkdirs())
            throw new IllegalStateException("Failed to create directory: " + outputDir.getAbsolutePath());

        // InputStream#transferTo(OutputStream)
        try (FileOutputStream out = new FileOutputStream(output)) {
            InputStream stream = zip.getInputStream(entry);
            byte[] buf = new byte[8192];
            int length;
            while ((length = stream.read(buf)) != -1) {
                out.write(buf, 0, length);
            }
        }

        return output;
    }
}
