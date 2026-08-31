/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.yaffs2;

import io.spicelabs.saffron.exception.ResourceLimitException;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boundary tests for the walk depth default and the readAllBytes cap
 * (phase 4 T4.3 and phase 5 T5.3), using hand-crafted YAFFS2 images.
 *
 * <h2>LLM section</h2>
 * <ul>
 *   <li>walk(): a 520-deep directory chain walks fully with an explicit
 *       depth but is truncated by the default cap (512). Object IDs skip
 *       YAFFS2's sentinels: 3 = UNLINKED and 4 = DELETED (a parentId of
 *       either hides the child).</li>
 *   <li>readAllBytes(): the driver derives file size from CHUNK EXTENTS
 *       (header file_size is untrusted), so the test writes real chunks
 *       covering cap−1, cap, and cap+1 bytes. cap−1 and cap succeed;
 *       cap+1 throws ResourceLimitException; openStream() still serves
 *       cap+1 bytes.</li>
 * </ul>
 */
class Yaffs2BoundaryTest {

    private static FileSystem mount(byte[] image) throws IOException {
        return Yaffs2FileSystemImpl.mount(new Yaffs2SecurityTest.Region(image));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void walkDefaultDepthIsBoundedButExplicitDepthReachesDeep() throws IOException {
        Yaffs2ImageWriter w = new Yaffs2ImageWriter();
        int depth = 520;
        long previousObjId = 1; // root
        for (int i = 0; i < depth; i++) {
            // Skip sentinels: objId 3 = UNLINKED, objId 4 = DELETED.
            long objId = i == 0 ? 2 : 4 + i;
            w.header(objId, Yaffs2ImageWriter.TYPE_DIRECTORY, previousObjId, "d" + i,
                    Yaffs2ImageWriter.MODE_DIR, 0, null, 0);
            previousObjId = objId;
        }

        try (FileSystem fs = mount(w.bytes())) {
            long defaultCount = fs.walk().count();
            long explicitCount = fs.walk("/", 600).count();

            // The default cap (512) stops the recursion: the explicit
            // walk must see strictly more entries (the deepest chain).
            assertThat(explicitCount).isGreaterThan(defaultCount);
            assertThat(defaultCount).isLessThanOrEqualTo(513); // root + 512 levels

            StringBuilder deepPath = new StringBuilder();
            for (int i = 0; i < depth; i++) {
                deepPath.append("/d").append(i);
            }
            // resolve() follows find() per segment (uncapped by design);
            // the DEFAULT walk must truncate before the deepest level.
            assertThat(fs.walk().noneMatch(e -> e.path().equals(deepPath.toString())))
                    .isTrue();
            assertThat(fs.walk("/", 600).anyMatch(e -> e.path().equals(deepPath.toString())))
                    .isTrue();
        }
    }

    private static final long CAP = 16L * 1024 * 1024;

    /** Writes full pages until the chunk-extent size reaches {@code target}. */
    private static void writeChunksToSize(Yaffs2ImageWriter w, long objId, long target) {
        long page = Yaffs2ImageWriter.PAGE;
        long chunk = 1;
        long written = 0;
        byte[] zeros = new byte[(int) page];
        while (written < target) {
            long remaining = target - written;
            if (remaining >= page) {
                w.dataChunk(objId, chunk, zeros);
                written += page;
            } else {
                w.dataChunk(objId, chunk, new byte[(int) remaining]);
                written += remaining;
            }
            chunk++;
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void readAllBytesCapBoundaries() throws IOException {
        // cap-1: succeeds with the declared extent.
        Yaffs2ImageWriter w1 = new Yaffs2ImageWriter();
        w1.header(2, Yaffs2ImageWriter.TYPE_FILE, 1, "small", Yaffs2ImageWriter.MODE_REG,
                0, null, 0);
        writeChunksToSize(w1, 2, CAP - 1);
        try (FileSystem fs = mount(w1.bytes())) {
            FileSystemEntry.RegularFile file =
                    (FileSystemEntry.RegularFile) fs.resolve("/small").orElseThrow();
            assertThat(file.readAllBytes()).hasSize((int) (CAP - 1));
        }

        // cap exactly: succeeds (the guard is strictly greater-than).
        Yaffs2ImageWriter w2 = new Yaffs2ImageWriter();
        w2.header(2, Yaffs2ImageWriter.TYPE_FILE, 1, "atcap", Yaffs2ImageWriter.MODE_REG,
                0, null, 0);
        writeChunksToSize(w2, 2, CAP);
        try (FileSystem fs = mount(w2.bytes())) {
            FileSystemEntry.RegularFile file =
                    (FileSystemEntry.RegularFile) fs.resolve("/atcap").orElseThrow();
            assertThat(file.readAllBytes()).hasSize((int) CAP);
        }

        // cap+1: readAllBytes refuses; openStream() still serves it.
        Yaffs2ImageWriter w3 = new Yaffs2ImageWriter();
        w3.header(2, Yaffs2ImageWriter.TYPE_FILE, 1, "big", Yaffs2ImageWriter.MODE_REG,
                0, null, 0);
        writeChunksToSize(w3, 2, CAP + 1);
        try (FileSystem fs = mount(w3.bytes())) {
            FileSystemEntry.RegularFile file =
                    (FileSystemEntry.RegularFile) fs.resolve("/big").orElseThrow();
            assertThatThrownBy(file::readAllBytes).isInstanceOf(ResourceLimitException.class);
            try (InputStream in = file.openStream()) {
                assertThat(in.readAllBytes()).hasSize((int) (CAP + 1));
            }
        }
    }
}
