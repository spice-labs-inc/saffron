/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ext4;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sparse files on an indirect-block (ext2/ext3) inode read back with their
 * holes as zeros, at the right offsets.
 *
 * <h2>LLM section</h2>
 * <ul>
 *   <li>Fixture: {@code src/test/resources/ext2/sparse.img}, made by
 *       {@code generate-fixture.sh}; its layout comment and {@link #expected()}
 *       must agree.</li>
 *   <li>Holes cover a direct pointer, a whole single-indirect pointer, a
 *       double-indirect block's first entry and runs inside an indirect
 *       block. A reader that skips a zero pointer without advancing shifts
 *       every later block down (surveyor-build#81).</li>
 *   <li>Both read paths are checked: the lazy {@code openStream()} and the
 *       eager {@code readAllBytes()}.</li>
 * </ul>
 */
class Ext2SparseFileTest {

    private static final Path IMAGE = Path.of("src/test/resources/ext2/sparse.img");
    private static final int BLOCK = 1024;
    private static final int[] DATA_BLOCKS = {0, 11, 600, 700};
    private static final int TAIL_BLOCK = 1000;
    private static final int TAIL_LENGTH = 300;

    /** The bytes generate-fixture.sh wrote to /sparse.bin. */
    private static byte[] expected() {
        byte[] data = new byte[TAIL_BLOCK * BLOCK + TAIL_LENGTH];
        for (int block : DATA_BLOCKS) {
            fill(data, block, BLOCK);
        }
        fill(data, TAIL_BLOCK, TAIL_LENGTH);
        return data;
    }

    private static void fill(byte[] data, int block, int length) {
        for (int i = 0; i < length; i++) {
            data[block * BLOCK + i] = (byte) (1 + (block + i) % 255);
        }
    }

    private static FileSystemEntry.RegularFile sparseFile(FileSystem fs) throws IOException {
        Optional<FileSystemEntry> entry = fs.resolve("/sparse.bin");
        assertThat(entry).as("resolve /sparse.bin").isPresent();
        assertThat(entry.get()).isInstanceOf(FileSystemEntry.RegularFile.class);
        return (FileSystemEntry.RegularFile) entry.get();
    }

    @Test
    void sparseFileReadsHolesAsZerosInPlace() throws IOException {
        byte[] expected = expected();
        try (VirtualDisk disk = DiskReader.open(IMAGE)) {
            List<FileSystem> mounted = FileSystemMount.mountAll(disk);
            assertThat(mounted).as("filesystems in " + IMAGE).hasSize(1);
            try (FileSystem fs = mounted.get(0)) {
                FileSystemEntry.RegularFile file = sparseFile(fs);
                assertThat(file.size()).isEqualTo(expected.length);

                byte[] streamed;
                try (InputStream in = file.openStream()) {
                    streamed = in.readAllBytes();
                }
                assertThat(Arrays.mismatch(streamed, expected))
                        .as("first wrong byte (openStream)").isEqualTo(-1);
                assertThat(Arrays.mismatch(file.readAllBytes(), expected))
                        .as("first wrong byte (readAllBytes)").isEqualTo(-1);
            }
        }
    }
}
