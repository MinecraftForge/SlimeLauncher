/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.launcher;

import net.minecraftforge.util.data.MCJsonUtils;
import net.minecraftforge.util.os.OS;

import java.io.File;

final class Constants {
    private Constants() { }

    static final File ASSETS_DIR = new File(MCJsonUtils.getMCDir(OS.current()), "assets");
    static final String RESOURCES_URL = "https://resources.download.minecraft.net/";
}
