/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.launcher;

import net.minecraftforge.util.data.json.AssetsIndex;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.data.json.MinecraftVersion;
import net.minecraftforge.util.download.DownloadUtils;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.util.logging.Log;

import java.io.File;
import java.util.Map;

/** Handles downloading assets for Minecraft. */
final class DownloadAssets {
    /**
     * Downloads assets for the given Minecraft version.
     *
     * @param repo        The assets repository to download from (see {@link Constants#RESOURCES_URL}).
     * @param assetsDir   The directory to store the assets in.
     * @param versionJson The version.json file for the version to download assets for.
     */
    static void download(String repo, File assetsDir, MinecraftVersion versionJson) {
        AssetsIndex index = JsonData.assetsIndex(downloadIndex(versionJson, assetsDir));

        File objectsDir = new File(assetsDir, "objects");
        if (!objectsDir.exists() && !objectsDir.mkdirs())
            throw new IllegalStateException("Failed to create objects directory: " + objectsDir);

        for (Map.Entry<String, AssetsIndex.Asset> entry : index.objects.entrySet()) {
            String name = entry.getKey();
            AssetsIndex.Asset asset = entry.getValue();
            String assetDest = getAssetDest(asset.hash);
            File file = new File(objectsDir, assetDest);
            if (file.exists()) {
                Log.debug("Considering existing file with size " + file.length() + " for " + name);
                if (file.length() == asset.size) {
                    Log.debug("Size check succeeded. Skipping.");
                    continue;
                }
            }

            // We need to download assets? Release the log so the consumer is aware.
            Log.release();
            try {
                Log.info("Downloading missing asset: " + name);
                DownloadUtils.downloadFile(file, repo + assetDest);
                String newSha1 = HashFunction.SHA1.sneakyHash(file);
                if (!newSha1.equals(asset.hash)) {
                    file.delete();
                    throw new IllegalStateException(String.format("Failed to verify asset %s. Expected %s got %s", name, asset.hash, newSha1));
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to download " + name, e);
            }
        }
    }

    private static String getAssetDest(String hash) {
        return hash.substring(0, 2) + '/' + hash;
    }

    private static File downloadIndex(MinecraftVersion versionJson, File assetsDir) {
        File index = new File(assetsDir, "indexes/" + versionJson.assetIndex.id + ".json");
        if (index.exists() && index.length() == versionJson.assetIndex.size) {
            return index;
        }

        if (!index.getParentFile().getAbsoluteFile().exists() && !index.getParentFile().getAbsoluteFile().mkdirs())
            throw new IllegalArgumentException("Failed to create index directory: " + index.getParentFile());

        try {
            DownloadUtils.downloadFile(index, versionJson.assetIndex.url);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to download assets index", e);
        }

        String newSha1 = HashFunction.SHA1.sneakyHash(index);
        if (!newSha1.equals(versionJson.assetIndex.sha1))
            throw new IllegalStateException(String.format("Failed to verify assets index. Expected %s got %s", versionJson.assetIndex.sha1, newSha1));

        return index;
    }
}
