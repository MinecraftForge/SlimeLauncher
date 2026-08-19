/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.launcher;

import joptsimple.AbstractOptionSpec;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.data.json.MinecraftVersion;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.util.logging.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Main {
    static final Logger LOGGER = Logger.create();
    private static final boolean DISABLE_ASSETS = Boolean.getBoolean("net.minecraftforge.launcher.skip.assets");
    private static final boolean DISABLE_COREMOD_SEARCH = Boolean.getBoolean("net.minecraftforge.launcher.skip.coremod.search");

    public static void main(String[] args) throws Throwable {
        long start = System.currentTimeMillis();
        Launcher launcher;
        try {
            LOGGER.capture();
            launcher = run(args);
        } catch (Throwable e) {
            LOGGER.release();
            throw e;
        }

        long total = System.currentTimeMillis() - start;
        if (LOGGER.isCapturing()) {
            LOGGER.drop();
            LOGGER.getInfo().print("Slime Launcher setup is up-to-date");
        } else {
            LOGGER.getInfo().print("Slime Launcher has finished setting up");
        }
        LOGGER.getInfo().println(", took " + total + "ms\n");

        launcher.run();
    }

    private static Launcher run(String[] rawArgs) throws Throwable {
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
        ArgumentAcceptingOptionSpec<File> metadataO = parser
            .accepts("metadata", "The metadata directory or zip to use for runs")
            .withRequiredArg().ofType(File.class);

        // main
        ArgumentAcceptingOptionSpec<String> mainClassO = parser
            .accepts("main", "The main class to run")
            .withRequiredArg().ofType(String.class);

        ArgumentAcceptingOptionSpec<File> toObfO = parser
            .accepts("to-obf", "Mapping file containing mapped to obfuscated names")
            .withRequiredArg().ofType(File.class);
        ArgumentAcceptingOptionSpec<File> toSrgO = parser
            .accepts("to-srg", "Mapping file containing mapped to SRG names")
            .withRequiredArg().ofType(File.class);

        ArgumentAcceptingOptionSpec<String> sideO = parser
            .accepts("launcher-side", "The 'side' to launch, `client|server`. If `server`, asset downloading, natives, and other features will be skipped")
            .withRequiredArg().ofType(String.class);

        Package pkg = Main.class.getPackage();
        LOGGER.info(pkg.getImplementationTitle() + ' ' + pkg.getImplementationVersion());

        SplitArgs _split = new SplitArgs(rawArgs);
        String[] slArgs = _split.sl;
        String[] mcArgs = _split.mc;

        OptionSet options = parser.parse(slArgs);
        if (options.has(helpO)) {
            parser.printHelpOn(LOGGER.getInfo());
            LOGGER.info("To pass arguments into the main class,\n" +
                "add '--' after the Slime Launcher arguments,\n" +
                "followed by your main arguments.");
            throw new IllegalArgumentException("Incomplete or invalid arguments");
        }

        File cache = options.valueOf(cache0);
        File assets = options.valueOf(assetsO);
        String assetsRepo = options.valueOf(assetsRepo0);
        File metadata = options.valueOf(metadataO);
        String mainClass = options.valueOf(mainClassO);
        File toObf = options.valueOf(toObfO);
        File toSrg = options.valueOf(toSrgO);

        LOGGER.info("Main Class: " + mainClass);
        boolean isLegacyDev = LegacyDev.is(mainClass);
        boolean isUserDev = LegacyDev.isUserDev(mainClass);
        if (isLegacyDev || isUserDev)
            mcArgs = LegacyDev.enhanceArgs(mainClass, mcArgs);

        boolean isClient = detectClientSide(options.valueOf(sideO), mainClass, mcArgs);

        if (isLegacyDev)
            mainClass = LegacyDev.getMainClass();
        else if (isUserDev) { // This is only used for 1.14->1.16, and only ever targeted cpw.mods.modlauncher.Launcher
            mainClass = "cpw.mods.modlauncher.Launcher";
            LOGGER.info("Overwriting Main Class: " + mainClass);
        }

        MinecraftVersion versionJson;
        if (metadata.isDirectory()) {
            File versionJsonFile = new File(metadata, "minecraft/version.json");
            if (versionJsonFile.exists()) versionJson = JsonData.minecraftVersion(versionJsonFile);
            else throw new FileNotFoundException("Missing minecraft/version.json in " + metadata.getAbsolutePath());
        } else if (metadata.isFile()) {
            try (ZipFile zip = new ZipFile(metadata)) {
                File versionJsonFile = extract(zip, "minecraft/version.json", cache);
                versionJson = JsonData.minecraftVersion(versionJsonFile);
            }
        } else {
            throw new IllegalArgumentException("Invalid metadata path: " + metadata.getAbsolutePath());
        }

        if (isClient) {
            DownloadAssets.checkAssets(assetsRepo, assets, versionJson, DISABLE_ASSETS);
            LegacyDev.setupNatives(versionJson, new File(cache, "natives"));
        }

        if (toObf != null && toSrg != null) {
            String hashObf = HashFunction.sha1().hash(toObf);
            String hashSrg = HashFunction.sha1().hash(toSrg);
            String hash = HashFunction.sha1().hash(hashObf + hashSrg);
            File dir = new File(cache, "mappings/srgs/" + hash);
            if (!dir.exists())
                dir.mkdirs();

            // We need to build the files like old FG did: https://github.com/MinecraftForge/ForgeGradle/blob/FG_2.3/src/main/resources/net/minecraftforge/gradle/GradleStartCommon.java#L61

            IMappingFile toObfMap = IMappingFile.load(toObf); // m->o
            IMappingFile toSrgMap = IMappingFile.load(toSrg); // m->s
            System.setProperty("net.minecraftforge.gradle.GradleStart.srgDir", dir.getCanonicalPath());
            setup(new File(dir, "notch-srg.srg"), "notch-srg", () -> toObfMap.reverse().chain(toSrgMap));
            setup(new File(dir, "notch-mcp.srg"), "notch-mcp", toObfMap::reverse);
            setup(new File(dir, "srg-mcp.srg"), "srg-mcp", toSrgMap::reverse);
            setup(new File(dir, "mcp-srg.srg"), "mcp-srg", () -> toSrgMap);
            setup(new File(dir, "mcp-notch.srg"), "mcp-notch", () -> toObfMap);
            //System.setProperty("net.minecraftforge.gradle.GradleStart.csvDir", CSV_DIR.getCanonicalPath());
        }

        if (!DISABLE_COREMOD_SEARCH)
            FG2Hacks.searchCoremods(versionJson.id);

        LOGGER.info("Looking for main class: " + mainClass);
        Class<?> main = findMainClass(mainClass);
        MethodHandle mainMethod = findMainMethod(main);

        LOGGER.info("Sanitizing Minecraft arguments");
        for (int i = 0; i < mcArgs.length; i++) {
            mcArgs[i] = mcArgs[i]
                .replace("{asset_index}", versionJson.assetIndex.id)
                .replace("{assets_root}", assets.getAbsolutePath());
        }

        return new Launcher(mainClass, mainMethod, mcArgs);
    }

    private static void setup(File file, String name, Supplier<IMappingFile> map) throws IOException {
        System.setProperty("net.minecraftforge.gradle.GradleStart.srg." + name, file.getCanonicalPath());
        if (!file.exists())
            map.get().write(file.toPath(), IMappingFile.Format.SRG);
    }

    private static Class<?> findMainClass(String mainClass) {
        try {
            return Class.forName(mainClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not find main class!", e);
        }
    }

    private static MethodHandle findMainMethod(Class<?> main) {
        MethodHandles.Lookup lookup = findLookup();
        try {
            return lookup.findStatic(main, "main", MethodType.methodType(void.class, String[].class));
        } catch (IllegalAccessException e) {
            // Old versions of the bootstrap lib didn't export the class, so lets try with some basic reflection
            try {
                Method mtd = main.getDeclaredMethod("main", String[].class);
                return lookup.unreflect(mtd);
            } catch (NoSuchMethodException | IllegalAccessException ex) {
                IllegalStateException error = new IllegalStateException("Could not find main(String[]) in " + main.getName() + "!\r\n" +
                    "Try running with --add-opens java.base/java.lang.invoke=ALL-UNNAMED", e);
                error.addSuppressed(ex);
                throw error;
            }
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Could not find main(String[]) in " + main.getName() + '!', e);
        }
    }

    private static MethodHandles.Lookup findLookup() {
        // try and find the privileged lookup, this typically needs a command line argument but it **should** detect it from our Manifest
        try {
            Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            field.setAccessible(true);
            return (MethodHandles.Lookup)field.get(null);
        } catch (Exception ignored) {
            // This is expected if we don't have --add-opens java.base/java.lang.invoke=net.minecraftforge.launcher
        }
        return MethodHandles.lookup();
    }

    private static boolean detectClientSide(String arg, String mainClass, String[] mcArgs) {
        if (arg != null) {
            if ("client".equals(arg))
                return true;
            if ("server".equals(arg))
                return true;
            throw new IllegalArgumentException("Invalid --launcher-side argument: " + arg);
        }

        if (mainClass.toLowerCase(Locale.ENGLISH).contains("server"))
            return false;

        for (int x = 0; x < mcArgs.length; x++) {
            if (mcArgs[x].startsWith("--launchTarget")) {
                String value = mcArgs[x].indexOf('=') == 15 ? mcArgs[x].substring(16) : x < mcArgs.length - 1 ? mcArgs[x + 1] : null;
                return value == null || !value.toLowerCase(Locale.ENGLISH).contains("server");
            }
        }
        return true;
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
            LOGGER.info("Launching using main class: " + this.name);
            this.main.invokeExact(this.args);
        }
    }

    private static final class SplitArgs {
        private final String[] sl;
        private final String[] mc;

        private SplitArgs(String[] args) {
            // we're looking for the first "--"
            int splitIdx = -1;
            for (int i = 0; i < args.length; i++) {
                if ("--".equals(args[i])) {
                    splitIdx = i;
                    break;
                }
            }

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
