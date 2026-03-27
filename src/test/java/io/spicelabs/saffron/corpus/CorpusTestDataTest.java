/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.corpus;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CorpusTestData classes, particularly backward compatibility
 * and classification helper methods.
 */
class CorpusTestDataTest {

    private static final Gson GSON = new Gson();

    @Test
    void testBackwardCompatibility_OldJsonWithoutNewFields() {
        // Old JSON format (before Phase 2) - should still work
        String oldJson = """
            {
                "device": "/dev/sda1",
                "fstype": "ext4",
                "fileCount": 100,
                "directoryCount": 10,
                "sampleFiles": []
            }
            """;

        CorpusTestData.FilesystemData fs = GSON.fromJson(oldJson, CorpusTestData.FilesystemData.class);

        // Basic fields should deserialize
        assertThat(fs.device).isEqualTo("/dev/sda1");
        assertThat(fs.fstype).isEqualTo("ext4");
        assertThat(fs.fileCount).isEqualTo(100);
        assertThat(fs.directoryCount).isEqualTo(10);

        // New fields should be null
        assertThat(fs.purpose).isNull();
        assertThat(fs.isMountable).isNull();
        assertThat(fs.mountPoint).isNull();
        assertThat(fs.expectedPaths).isNull();

        // Helper methods should provide defaults
        assertThat(fs.isMountable()).isTrue();  // ext4 is mountable
        assertThat(fs.getPurpose()).isEqualTo("unknown");  // ext4, not vfat
        assertThat(fs.getMountPoint()).isNull();  // unknown purpose
        assertThat(fs.getExpectedPaths()).isEmpty();

        assertThat(fs.isRootFilesystem()).isFalse();
        assertThat(fs.isBootFilesystem()).isFalse();
    }

    @Test
    void testNewJsonWithAllFields() {
        // New JSON format (after Phase 2)
        String newJson = """
            {
                "device": "/dev/vgubuntu/root",
                "fstype": "ext4",
                "fileCount": 128775,
                "directoryCount": 15618,
                "purpose": "root",
                "isMountable": true,
                "mountPoint": "/",
                "expectedPaths": ["/etc", "/bin", "/usr", "/etc/debian_version"],
                "sampleFiles": []
            }
            """;

        CorpusTestData.FilesystemData fs = GSON.fromJson(newJson, CorpusTestData.FilesystemData.class);

        assertThat(fs.device).isEqualTo("/dev/vgubuntu/root");
        assertThat(fs.purpose).isEqualTo("root");
        assertThat(fs.isMountable).isTrue();
        assertThat(fs.mountPoint).isEqualTo("/");
        assertThat(fs.expectedPaths).containsExactly("/etc", "/bin", "/usr", "/etc/debian_version");

        // Helper methods should use the actual values
        assertThat(fs.isMountable()).isTrue();
        assertThat(fs.getPurpose()).isEqualTo("root");
        assertThat(fs.getMountPoint()).isEqualTo("/");
        assertThat(fs.getExpectedPaths()).containsExactly("/etc", "/bin", "/usr", "/etc/debian_version");

        assertThat(fs.isRootFilesystem()).isTrue();
        assertThat(fs.isBootFilesystem()).isFalse();
    }

    @Test
    void testSwapFilesystemClassification() {
        String swapJson = """
            {
                "device": "/dev/sda2",
                "fstype": "swap",
                "fileCount": 0,
                "directoryCount": 0,
                "sampleFiles": []
            }
            """;

        CorpusTestData.FilesystemData fs = GSON.fromJson(swapJson, CorpusTestData.FilesystemData.class);

        // Old JSON - should infer from fstype
        assertThat(fs.isMountable()).isFalse();  // swap is not mountable
        assertThat(fs.getPurpose()).isEqualTo("swap");
    }

    @Test
    void testVfatBootClassification() {
        String vfatJson = """
            {
                "device": "/dev/sda1",
                "fstype": "vfat",
                "fileCount": 10,
                "directoryCount": 3,
                "sampleFiles": []
            }
            """;

        CorpusTestData.FilesystemData fs = GSON.fromJson(vfatJson, CorpusTestData.FilesystemData.class);

        // Old JSON - should infer boot from vfat
        assertThat(fs.getPurpose()).isEqualTo("boot");
        assertThat(fs.isBootFilesystem()).isTrue();
        assertThat(fs.getMountPoint()).isEqualTo("/boot/efi");
        assertThat(fs.getExpectedPaths()).contains("/EFI");
    }

    @Test
    void testNewJsonWithSwap() {
        // New JSON format with swap
        String swapJson = """
            {
                "device": "/dev/vgubuntu/swap_1",
                "fstype": "swap",
                "fileCount": 0,
                "directoryCount": 0,
                "purpose": "swap",
                "isMountable": false,
                "mountPoint": null,
                "expectedPaths": [],
                "sampleFiles": []
            }
            """;

        CorpusTestData.FilesystemData fs = GSON.fromJson(swapJson, CorpusTestData.FilesystemData.class);

        assertThat(fs.purpose).isEqualTo("swap");
        assertThat(fs.isMountable).isFalse();
        assertThat(fs.isMountable()).isFalse();
        assertThat(fs.getExpectedPaths()).isEmpty();
    }

    @Test
    void testBootFilesystem() {
        String bootJson = """
            {
                "device": "/dev/sda2",
                "fstype": "vfat",
                "fileCount": 8,
                "directoryCount": 3,
                "purpose": "boot",
                "isMountable": true,
                "mountPoint": "/boot/efi",
                "expectedPaths": ["/EFI", "/EFI/BOOT"],
                "sampleFiles": []
            }
            """;

        CorpusTestData.FilesystemData fs = GSON.fromJson(bootJson, CorpusTestData.FilesystemData.class);

        assertThat(fs.isBootFilesystem()).isTrue();
        assertThat(fs.isRootFilesystem()).isFalse();
        assertThat(fs.getExpectedPaths()).containsExactly("/EFI", "/EFI/BOOT");
    }

    @Test
    void testFullImageDataDeserialization() {
        String imageJson = """
            {
                "imagePath": "/corpus/test.qcow2",
                "imageBasename": "test.qcow2",
                "filesystemCount": 3,
                "totalFiles": 1000,
                "totalDirectories": 100,
                "filesystems": [
                    {
                        "device": "/dev/sda1",
                        "fstype": "vfat",
                        "fileCount": 10,
                        "directoryCount": 3,
                        "purpose": "boot",
                        "isMountable": true,
                        "mountPoint": "/boot/efi",
                        "expectedPaths": ["/EFI"],
                        "sampleFiles": []
                    },
                    {
                        "device": "/dev/sda2",
                        "fstype": "ext4",
                        "fileCount": 990,
                        "directoryCount": 97,
                        "purpose": "root",
                        "isMountable": true,
                        "mountPoint": "/",
                        "expectedPaths": ["/etc", "/bin"],
                        "sampleFiles": [
                            {"path": "/etc/passwd", "size": 100, "sha256": "abc123"}
                        ]
                    },
                    {
                        "device": "/dev/sda3",
                        "fstype": "swap",
                        "fileCount": 0,
                        "directoryCount": 0,
                        "purpose": "swap",
                        "isMountable": false,
                        "mountPoint": null,
                        "expectedPaths": [],
                        "sampleFiles": []
                    }
                ]
            }
            """;

        CorpusTestData.CorpusImageData image = GSON.fromJson(imageJson, CorpusTestData.CorpusImageData.class);

        assertThat(image.imageBasename).isEqualTo("test.qcow2");
        assertThat(image.filesystemCount).isEqualTo(3);
        assertThat(image.filesystems).hasSize(3);

        // Check boot filesystem
        CorpusTestData.FilesystemData bootFs = image.filesystems.get(0);
        assertThat(bootFs.isBootFilesystem()).isTrue();
        assertThat(bootFs.isMountable()).isTrue();

        // Check root filesystem
        CorpusTestData.FilesystemData rootFs = image.filesystems.get(1);
        assertThat(rootFs.isRootFilesystem()).isTrue();
        assertThat(rootFs.isMountable()).isTrue();
        assertThat(rootFs.sampleFiles).hasSize(1);
        assertThat(rootFs.sampleFiles.get(0).path).isEqualTo("/etc/passwd");

        // Check swap
        CorpusTestData.FilesystemData swapFs = image.filesystems.get(2);
        assertThat(swapFs.isMountable()).isFalse();
        assertThat(swapFs.getPurpose()).isEqualTo("swap");
    }

    @Test
    void testFirstFilesystemType() {
        CorpusTestData.CorpusImageData image = new CorpusTestData.CorpusImageData();

        // No filesystems
        assertThat(image.firstFilesystemType()).isNull();

        // With filesystems
        CorpusTestData.FilesystemData fs1 = new CorpusTestData.FilesystemData();
        fs1.fstype = "vfat";
        CorpusTestData.FilesystemData fs2 = new CorpusTestData.FilesystemData();
        fs2.fstype = "ext4";

        image.filesystems = List.of(fs1, fs2);
        assertThat(image.firstFilesystemType()).isEqualTo("vfat");
    }
}
