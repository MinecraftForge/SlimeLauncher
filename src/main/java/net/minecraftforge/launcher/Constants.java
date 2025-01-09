/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.launcher;

import net.minecraftforge.util.data.MCJsonUtils;

import java.io.File;

interface Constants {
    File ASSETS_DIR = new File(MCJsonUtils.getMCDir(), "assets");
    String RESOURCES_URL = "https://resources.download.minecraft.net/";
}
