package dev.vatn.plugins.devenv.model;

import java.util.List;

/**
 * Global package inventory across the detected package managers.
 */
public record PackageInventory(
        List<PackageEntry> brewFormulae,
        List<PackageEntry> brewCasks,
        List<ServiceEntry> brewServices,
        List<PackageEntry> npmGlobals,
        List<PackageEntry> pipGlobals) {

    public static PackageInventory empty() {
        return new PackageInventory(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
