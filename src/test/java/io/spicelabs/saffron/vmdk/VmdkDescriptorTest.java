/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.vmdk;

import io.spicelabs.saffron.vmdk.descriptor.VmdkDescriptor;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link VmdkDescriptor}.
 */
class VmdkDescriptorTest {

    @Test
    void parse_validDescriptor_parsesCorrectly() throws IOException {
        String content = """
                # Disk DescriptorFile
                version=1
                CID=0b8fb407
                parentCID=ffffffff
                createType="monolithicSparse"

                # Extent description
                RW 20971520 SPARSE "disk.vmdk"

                # The Disk Data Base
                #DDB
                ddb.virtualHWVersion = "4"
                ddb.geometry.cylinders = "1305"
                ddb.geometry.heads = "16"
                ddb.geometry.sectors = "63"
                ddb.adapterType = "ide"
                """;

        VmdkDescriptor descriptor = VmdkDescriptor.parse(content);

        assertThat(descriptor.version()).isEqualTo(1);
        assertThat(descriptor.cid()).isEqualTo("0b8fb407");
        assertThat(descriptor.parentCid()).isEqualTo("ffffffff");
        assertThat(descriptor.createType()).isEqualTo("monolithicSparse");
        assertThat(descriptor.extents()).hasSize(1);
        assertThat(descriptor.virtualHWVersion()).isEqualTo("4");
        assertThat(descriptor.adapterType()).isEqualTo("ide");
    }

    @Test
    void parse_extent_parsesCorrectly() throws IOException {
        String content = """
                # Disk DescriptorFile
                version=1
                CID=fffffffe
                parentCID=ffffffff
                createType="monolithicSparse"

                RW 20971520 SPARSE "disk.vmdk"
                """;

        VmdkDescriptor descriptor = VmdkDescriptor.parse(content);

        assertThat(descriptor.extents()).hasSize(1);
        VmdkDescriptor.Extent extent = descriptor.extents().get(0);
        assertThat(extent.accessMode()).isEqualTo(VmdkDescriptor.AccessMode.RW);
        assertThat(extent.sizeInSectors()).isEqualTo(20971520);
        assertThat(extent.type()).isEqualTo(VmdkDescriptor.ExtentType.SPARSE);
        assertThat(extent.filename()).isEqualTo("disk.vmdk");
    }

    @Test
    void parse_readOnlyExtent_parsesCorrectly() throws IOException {
        String content = """
                # Disk DescriptorFile
                version=1
                CID=fffffffe
                parentCID=ffffffff
                createType="streamOptimized"

                RDONLY 10485760 SPARSE "readonly.vmdk"
                """;

        VmdkDescriptor descriptor = VmdkDescriptor.parse(content);

        assertThat(descriptor.extents()).hasSize(1);
        VmdkDescriptor.Extent extent = descriptor.extents().get(0);
        assertThat(extent.accessMode()).isEqualTo(VmdkDescriptor.AccessMode.RDONLY);
    }

    @Test
    void parse_multipleExtents_parsesAll() throws IOException {
        String content = """
                # Disk DescriptorFile
                version=1
                CID=fffffffe
                parentCID=ffffffff
                createType="twoGbMaxExtentSparse"

                RW 4194304 SPARSE "disk-s001.vmdk"
                RW 4194304 SPARSE "disk-s002.vmdk"
                RW 2097152 SPARSE "disk-s003.vmdk"
                """;

        VmdkDescriptor descriptor = VmdkDescriptor.parse(content);

        assertThat(descriptor.extents()).hasSize(3);
        assertThat(descriptor.totalSectors()).isEqualTo(4194304 + 4194304 + 2097152);
    }

    @Test
    void geometry_whenPresent_returnsParsedGeometry() throws IOException {
        String content = """
                # Disk DescriptorFile
                version=1
                CID=fffffffe
                parentCID=ffffffff
                createType="monolithicSparse"

                RW 20971520 SPARSE "disk.vmdk"

                ddb.geometry.cylinders = "1305"
                ddb.geometry.heads = "16"
                ddb.geometry.sectors = "63"
                """;

        VmdkDescriptor descriptor = VmdkDescriptor.parse(content);

        assertThat(descriptor.geometry()).isPresent();
        VmdkDescriptor.Geometry geometry = descriptor.geometry().get();
        assertThat(geometry.cylinders()).isEqualTo(1305);
        assertThat(geometry.heads()).isEqualTo(16);
        assertThat(geometry.sectorsPerTrack()).isEqualTo(63);
    }

    @Test
    void hasParent_withNoParent_returnsFalse() throws IOException {
        String content = """
                version=1
                CID=fffffffe
                parentCID=ffffffff
                createType="monolithicSparse"
                """;

        VmdkDescriptor descriptor = VmdkDescriptor.parse(content);

        assertThat(descriptor.hasParent()).isFalse();
    }

    @Test
    void hasParent_withParent_returnsTrue() throws IOException {
        String content = """
                version=1
                CID=12345678
                parentCID=87654321
                createType="vmfsSparse"
                """;

        VmdkDescriptor descriptor = VmdkDescriptor.parse(content);

        assertThat(descriptor.hasParent()).isTrue();
    }

    @Test
    void totalBytes_calculatesCorrectly() throws IOException {
        String content = """
                version=1
                CID=fffffffe
                parentCID=ffffffff
                createType="monolithicSparse"

                RW 2048 SPARSE "disk.vmdk"
                """;

        VmdkDescriptor descriptor = VmdkDescriptor.parse(content);

        assertThat(descriptor.totalBytes()).isEqualTo(2048 * 512);
    }

    @Test
    void parse_flatExtentWithOffset_parsesOffset() throws IOException {
        String content = """
                version=1
                CID=fffffffe
                parentCID=ffffffff
                createType="monolithicFlat"

                RW 20971520 FLAT "disk-flat.vmdk" 0
                """;

        VmdkDescriptor descriptor = VmdkDescriptor.parse(content);

        assertThat(descriptor.extents()).hasSize(1);
        VmdkDescriptor.Extent extent = descriptor.extents().get(0);
        assertThat(extent.type()).isEqualTo(VmdkDescriptor.ExtentType.FLAT);
        assertThat(extent.offset()).isEqualTo(0);
    }
}
