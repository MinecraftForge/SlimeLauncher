/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.launcher;

import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

final class JarVersionInfo {
    public final String specificationTitle;
    public final String specificationVendor;
    public final String specificationVersion;
    public final String implementationTitle;
    public final String implementationVendor;
    public final String implementationVersion;

    private JarVersionInfo(
        @Nullable String specificationTitle,
        @Nullable String specificationVendor,
        @Nullable String specificationVersion,
        @Nullable String implementationTitle,
        @Nullable String implementationVendor,
        @Nullable String implementationVersion
    ) {
        this.specificationTitle = specificationTitle != null ? specificationTitle : "";
        this.specificationVendor = specificationVendor != null ? specificationVendor : "";
        this.specificationVersion = specificationVersion != null ? specificationVersion : "";
        this.implementationTitle = implementationTitle != null ? implementationTitle : "";
        this.implementationVendor = implementationVendor != null ? implementationVendor : "";
        this.implementationVersion = implementationVersion != null ? implementationVersion : "";
    }

    void hello(Consumer<String> consumer, boolean vendor, boolean newLine) {
        consumer.accept(this.getHello(vendor) + (newLine ? "\n" : ""));
    }

    String getHello(boolean vendor) {
        String ret = String.format("%s %s", this.implementationTitle, this.implementationVersion);
        return vendor ? ret + String.format(" by %s", this.implementationVendor) : ret;
    }

    static JarVersionInfo of(String packageName) {
        return of(Package.getPackage(packageName));
    }

    static JarVersionInfo of(Class<?> clazz) {
        return of(clazz.getPackage());
    }

    static JarVersionInfo of(Package pkg) {
        return new JarVersionInfo(
            pkg.getSpecificationTitle(),
            pkg.getSpecificationVendor(),
            pkg.getSpecificationVersion(),
            pkg.getImplementationTitle(),
            pkg.getImplementationVendor(),
            pkg.getImplementationVersion()
        );
    }
}
