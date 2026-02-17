/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.corpus;

import io.spicelabs.saffron.DiskFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Represents a single image entry in the test corpus manifest.
 *
 * @param id unique identifier for this image
 * @param path relative path within the corpus directory
 * @param format disk format (vmdk, qcow2, vhd, vhdx, vdi)
 * @param formatVersion format-specific version string
 * @param era "legacy" (2005-2010) or "modern" (2011+)
 * @param year year the image was created/released
 * @param sourceUrl where the image was downloaded from
 * @param license SPDX license identifier
 * @param os operating system description
 * @param filesystem primary filesystem type
 * @param partitionScheme "mbr" or "gpt"
 * @param virtualSizeBytes virtual disk size in bytes
 * @param actualSizeBytes actual file size on disk
 * @param sha256 SHA-256 hash of the image file
 * @param features list of format-specific features (e.g., "sparse", "compressed")
 * @param knownFiles files with known content for verification
 * @param ciTier CI tier: "quick", "standard", or "full"
 * @param priority priority within tier (1 = highest)
 * @param provenance provenance tracking information
 */
public record CorpusImage(
        @NotNull String id,
        @NotNull String path,
        @NotNull String format,
        @Nullable String formatVersion,
        @NotNull String era,
        int year,
        @NotNull String sourceUrl,
        @NotNull String license,
        @Nullable String os,
        @Nullable String filesystem,
        @Nullable String partitionScheme,
        long virtualSizeBytes,
        long actualSizeBytes,
        @NotNull String sha256,
        @NotNull List<String> features,
        @NotNull List<KnownFile> knownFiles,
        @NotNull String ciTier,
        int priority,
        @NotNull Provenance provenance
) {

    /**
     * Returns the DiskFormat enum for this image, or empty if the format is not
     * supported by Saffron (e.g., "iso", "ova").
     */
    public @NotNull Optional<DiskFormat> diskFormat() {
        return switch (format.toLowerCase()) {
            case "vmdk" -> Optional.of(DiskFormat.VMDK);
            case "qcow2" -> Optional.of(DiskFormat.QCOW2);
            case "vhd" -> Optional.of(DiskFormat.VHD);
            case "vhdx" -> Optional.of(DiskFormat.VHDX);
            case "vdi" -> Optional.of(DiskFormat.VDI);
            case "raw", "dmg", "img" -> Optional.of(DiskFormat.RAW);
            default -> Optional.empty();
        };
    }

    /**
     * Returns true if this is a legacy image (2005-2010).
     */
    public boolean isLegacy() {
        return "legacy".equals(era) || (year >= 2005 && year <= 2010);
    }

    /**
     * Returns true if this image should be included in CI quick tier.
     */
    public boolean isQuickTier() {
        return "quick".equals(ciTier);
    }

    /**
     * Returns true if this image should be included in CI standard tier.
     */
    public boolean isStandardTier() {
        return "quick".equals(ciTier) || "standard".equals(ciTier);
    }

    /**
     * A file within the image with known content for verification.
     *
     * @param path path within the filesystem
     * @param sha256 expected SHA-256 hash of file content
     * @param size expected file size in bytes
     */
    public record KnownFile(
            @NotNull String path,
            @NotNull String sha256,
            long size
    ) {}

    /**
     * Provenance tracking for an image.
     *
     * @param sourceUrl original download URL
     * @param downloadDate date the image was downloaded (ISO-8601)
     * @param downloadSha256 SHA-256 at time of download
     * @param license SPDX license identifier
     * @param licenseVerified whether license was manually verified
     * @param malwareScanned whether image was malware scanned
     * @param malwareScanDate date of last malware scan (ISO-8601)
     * @param notes additional notes about the image
     */
    public record Provenance(
            @NotNull String sourceUrl,
            @NotNull String downloadDate,
            @NotNull String downloadSha256,
            @NotNull String license,
            boolean licenseVerified,
            boolean malwareScanned,
            @Nullable String malwareScanDate,
            @Nullable String notes
    ) {}

    /**
     * Creates a builder for constructing CorpusImage instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for CorpusImage.
     */
    public static final class Builder {
        private String id;
        private String path;
        private String format;
        private String formatVersion;
        private String era = "modern";
        private int year = 2024;
        private String sourceUrl = "";
        private String license = "unknown";
        private String os;
        private String filesystem;
        private String partitionScheme;
        private long virtualSizeBytes;
        private long actualSizeBytes;
        private String sha256 = "";
        private List<String> features = List.of();
        private List<KnownFile> knownFiles = List.of();
        private String ciTier = "full";
        private int priority = 3;
        private Provenance provenance;

        public Builder id(String id) { this.id = id; return this; }
        public Builder path(String path) { this.path = path; return this; }
        public Builder format(String format) { this.format = format; return this; }
        public Builder formatVersion(String formatVersion) { this.formatVersion = formatVersion; return this; }
        public Builder era(String era) { this.era = era; return this; }
        public Builder year(int year) { this.year = year; return this; }
        public Builder sourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; return this; }
        public Builder license(String license) { this.license = license; return this; }
        public Builder os(String os) { this.os = os; return this; }
        public Builder filesystem(String filesystem) { this.filesystem = filesystem; return this; }
        public Builder partitionScheme(String partitionScheme) { this.partitionScheme = partitionScheme; return this; }
        public Builder virtualSizeBytes(long virtualSizeBytes) { this.virtualSizeBytes = virtualSizeBytes; return this; }
        public Builder actualSizeBytes(long actualSizeBytes) { this.actualSizeBytes = actualSizeBytes; return this; }
        public Builder sha256(String sha256) { this.sha256 = sha256; return this; }
        public Builder features(List<String> features) { this.features = features; return this; }
        public Builder knownFiles(List<KnownFile> knownFiles) { this.knownFiles = knownFiles; return this; }
        public Builder ciTier(String ciTier) { this.ciTier = ciTier; return this; }
        public Builder priority(int priority) { this.priority = priority; return this; }
        public Builder provenance(Provenance provenance) { this.provenance = provenance; return this; }

        public CorpusImage build() {
            if (provenance == null) {
                provenance = new Provenance(sourceUrl, "", sha256, license, false, false, null, null);
            }
            return new CorpusImage(id, path, format, formatVersion, era, year, sourceUrl, license,
                    os, filesystem, partitionScheme, virtualSizeBytes, actualSizeBytes, sha256,
                    features, knownFiles, ciTier, priority, provenance);
        }
    }
}
