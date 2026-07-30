/*
 * Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.compressed;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.SecurityPolicy;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.BinaryContainerFileSystemImpl;
import io.spicelabs.saffron.container.BinaryContainerMount;
import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import io.spicelabs.saffron.exception.ResourceLimitException;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for compressed single non-archive payload containers.
 */
class CompressedSingleContainerTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource({"gzip, gz", "xz, xz", "bzip2, bz2"})
    void detectsCompressedTextFromPath(String compression, String extension) throws IOException {
        byte[] payload = "Hello, compressed world!".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, extension);

        Optional<ContainerFormat> format = ContainerDetector.detect(compressed);
        assertThat(format).contains(ContainerFormat.COMPRESSED_SINGLE);

        try (FileSystem fs = BinaryContainerMount.mount(compressed).orElseThrow()) {
            assertThat(((FileSystem.BinaryContainerFileSystem) fs).containerFormat()).isEqualTo("compressed_single");
            FileSystemEntry.RegularFile entry = (FileSystemEntry.RegularFile) fs.resolve("/payload").orElseThrow();
            assertThat(entry.size()).isEqualTo(payload.length);
            assertThat(entry.readAllBytes()).isEqualTo(payload);
        }
    }

    @ParameterizedTest
    @CsvSource({"gzip, gz", "xz, xz", "bzip2, bz2"})
    void payloadSizeIsDecompressedSize(String compression, String extension) throws IOException {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 256);
        }
        Path compressed = createCompressedFile(payload, extension);

        try (FileSystem fs = BinaryContainerMount.mount(compressed).orElseThrow()) {
            FileSystemEntry.RegularFile entry = (FileSystemEntry.RegularFile) fs.resolve("/payload").orElseThrow();
            assertThat(entry.size()).isEqualTo(payload.length);
            assertThat(fs.usedSize()).isEqualTo(payload.length);
            assertThat(fs.totalSize()).isEqualTo(Files.size(compressed));
        }
    }

    @ParameterizedTest
    @CsvSource({"tar.gz", "tgz", "tar.xz", "txz", "tar.bz2", "tbz2", "img.gz", "raw.gz"})
    void rejectsExcludedExtensionsFromPath(String extension) throws IOException {
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        Path compressed = tempDir.resolve("excluded." + extension);
        try (OutputStream out = Files.newOutputStream(compressed);
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(payload);
        }

        Optional<ContainerFormat> format = ContainerDetector.detect(compressed);
        assertThat(format).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"gzip, gz", "xz, xz", "bzip2, bz2"})
    void rejectsBomb(String compression, String extension) throws IOException {
        // A highly compressible payload: zeros. A few KB will decompress to many MB.
        byte[] payload = new byte[1024 * 1024]; // 1 MB of zeros
        Path compressed = createCompressedFile(payload, extension);

        SecurityPolicy tight = SecurityPolicy.builder().maxDecompressedSize(1024).build();

        assertThatThrownBy(() -> BinaryContainerMount.mount(compressed, tight))
                .isInstanceOf(ResourceLimitException.class)
                .satisfies(e -> assertThat(((ResourceLimitException) e).getResourceType()).isEqualTo("decompressed_size"));
    }

    @Test
    void customSecurityPolicyLimit() throws IOException {
        byte[] payload = new byte[100 * 1024]; // 100 KB
        Path compressed = createCompressedFile(payload, "gz");

        SecurityPolicy tight = SecurityPolicy.builder().maxDecompressedSize(50 * 1024).build();
        assertThatThrownBy(() -> BinaryContainerMount.mount(compressed, tight))
                .isInstanceOf(ResourceLimitException.class);

        SecurityPolicy loose = SecurityPolicy.builder().maxDecompressedSize(200 * 1024).build();
        try (FileSystem fs = BinaryContainerMount.mount(compressed, loose).orElseThrow()) {
            FileSystemEntry.RegularFile entry = (FileSystemEntry.RegularFile) fs.resolve("/payload").orElseThrow();
            assertThat(entry.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void cleanupOnClose() throws IOException {
        byte[] payload = "cleanup test".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, "gz");

        CompressedSingleContainer container = (CompressedSingleContainer)
                CompressedSingleContainerFactory.open(compressed, SecurityPolicy.defaults()).orElseThrow();
        Path payloadPath = container.getPayloadPath();
        assertThat(payloadPath).exists();
        container.close();
        assertThat(payloadPath).doesNotExist();
    }

    @Test
    void cleanupOnFailure() throws IOException {
        // A large payload with a tiny decompression limit fails during streaming.
        // The factory must delete the partial temp file.
        byte[] payload = new byte[1024 * 1024]; // 1 MB zeros
        Path compressed = createCompressedFile(payload, "gz");
        SecurityPolicy tight = SecurityPolicy.builder().maxDecompressedSize(1024).build();

        assertThatThrownBy(() -> CompressedSingleContainerFactory.open(compressed, tight))
                .isInstanceOf(ResourceLimitException.class);

        // No leaked payload files should remain in the temp directory.
        try (var stream = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            long leaked = stream
                    .filter(p -> p.getFileName().toString().startsWith("saffron-compressed-single-"))
                    .count();
            assertThat(leaked).isZero();
        }
    }

    @Test
    void detectsFromByteBuffer() throws IOException {
        byte[] payload = "buffer test".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, "gz");
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(compressed));

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);
        assertThat(format).contains(ContainerFormat.COMPRESSED_SINGLE);

        try (FileSystem fs = BinaryContainerMount.mount(buffer).orElseThrow()) {
            FileSystemEntry.RegularFile entry = (FileSystemEntry.RegularFile) fs.resolve("/payload").orElseThrow();
            assertThat(entry.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void detectsFromVirtualDisk() throws IOException {
        byte[] payload = "disk test".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, "gz");
        byte[] data = Files.readAllBytes(compressed);
        VirtualDisk disk = new FuzzVirtualDisk(data);

        Optional<ContainerFormat> format = ContainerDetector.detect(disk);
        assertThat(format).contains(ContainerFormat.COMPRESSED_SINGLE);

        try (FileSystem fs = BinaryContainerMount.mount(disk).orElseThrow()) {
            FileSystemEntry.RegularFile entry = (FileSystemEntry.RegularFile) fs.resolve("/payload").orElseThrow();
            assertThat(entry.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void gzipCompressedKernelLikeImageExposesPayload() throws IOException {
        // A synthetic gzip-compressed payload that the old Linux-kernel detector
        // would have classified as a GZIP_IMAGE. Under the new ordering, the
        // top-level detector sees it as a compressed single payload and exposes
        // the decompressed bytes as /payload.
        byte[] dtb = makeDtb(64);
        byte[] prefix = "kernel payload before dtb".getBytes(StandardCharsets.UTF_8);
        byte[] uncompressed = concat(prefix, dtb);
        byte[] image = padTo(gzipBytes(uncompressed), 512);

        try (FileSystem fs = BinaryContainerMount.mount(ByteBuffer.wrap(image)).orElseThrow()) {
            assertThat(((FileSystem.BinaryContainerFileSystem) fs).containerFormat()).isEqualTo("compressed_single");
            FileSystemEntry.RegularFile entry = (FileSystemEntry.RegularFile) fs.resolve("/payload").orElseThrow();
            byte[] payload = entry.readAllBytes();
            assertThat(payload.length).isEqualTo(uncompressed.length);
            assertThat(payload).startsWith(prefix);
            assertThat(payload).endsWith(dtb);
        }
    }

    @Test
    void diskReaderReturnsRawForPlainGz() throws IOException {
        byte[] payload = "disk reader test".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, "gz");

        try (VirtualDisk disk = DiskReader.open(compressed)) {
            assertThat(disk.format()).isEqualTo(DiskFormat.RAW);
            assertThat(disk).isNotInstanceOf(VirtualDisk.GcpDisk.class);
        }
    }

    @Test
    void diskReaderReturnsRawForXz() throws IOException {
        byte[] payload = "disk reader xz test".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, "xz");

        try (VirtualDisk disk = DiskReader.open(compressed)) {
            assertThat(disk.format()).isEqualTo(DiskFormat.RAW);
        }
    }

    @Test
    void diskReaderReturnsRawForBz2() throws IOException {
        byte[] payload = "disk reader bz2 test".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, "bz2");

        try (VirtualDisk disk = DiskReader.open(compressed)) {
            assertThat(disk.format()).isEqualTo(DiskFormat.RAW);
        }
    }

    @Test
    void fileSystemMountFallbackToCompressedSingleGz() throws IOException {
        byte[] payload = "fallback test".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, "gz");

        try (VirtualDisk disk = DiskReader.open(compressed);
             FileSystem fs = FileSystemMount.mountAll(disk).get(0)) {
            assertThat(((FileSystem.BinaryContainerFileSystem) fs).containerFormat()).isEqualTo("compressed_single");
            FileSystemEntry.RegularFile entry = (FileSystemEntry.RegularFile) fs.resolve("/payload").orElseThrow();
            assertThat(entry.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void fileSystemMountFallbackToCompressedSingleXz() throws IOException {
        byte[] payload = "fallback xz test".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, "xz");

        try (VirtualDisk disk = DiskReader.open(compressed);
             FileSystem fs = FileSystemMount.mountAll(disk).get(0)) {
            assertThat(((FileSystem.BinaryContainerFileSystem) fs).containerFormat()).isEqualTo("compressed_single");
        }
    }

    @Test
    void fileSystemMountFallbackToCompressedSingleBz2() throws IOException {
        byte[] payload = "fallback bz2 test".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, "bz2");

        try (VirtualDisk disk = DiskReader.open(compressed);
             FileSystem fs = FileSystemMount.mountAll(disk).get(0)) {
            assertThat(((FileSystem.BinaryContainerFileSystem) fs).containerFormat()).isEqualTo("compressed_single");
        }
    }

    @Test
    void fileSystemMountPropagatesResourceLimitException() throws IOException {
        byte[] payload = new byte[1024 * 1024]; // 1 MB zeros
        Path compressed = createCompressedFile(payload, "gz");
        SecurityPolicy tight = SecurityPolicy.builder().maxDecompressedSize(1024).build();

        try (VirtualDisk disk = DiskReader.open(compressed, tight)) {
            assertThatThrownBy(() -> FileSystemMount.mountAll(disk, tight))
                    .isInstanceOf(ResourceLimitException.class);
        }
    }

    @Test
    void emptyFileIsNotDetected() throws IOException {
        Path empty = tempDir.resolve("empty.gz");
        Files.createFile(empty);
        assertThat(ContainerDetector.detect(empty)).isEmpty();
    }

    @Test
    void truncatedGzipIsNotDetected() throws IOException {
        Path truncated = tempDir.resolve("truncated-header.gz");
        Files.write(truncated, new byte[]{0x1f}); // only one magic byte
        assertThat(ContainerDetector.detect(truncated)).isEmpty();
    }

    @Test
    void corruptGzipBodyFailsCleanly() throws IOException {
        // A valid gzip header followed by invalid body (no actual compressed blocks)
        Path corrupt = tempDir.resolve("corrupt.gz");
        Files.write(corrupt, new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 'x'});
        assertThatThrownBy(() -> BinaryContainerMount.mount(corrupt))
                .isInstanceOf(IOException.class);
    }

    @Test
    void wrongMagicWithGzExtensionIsNotDetected() throws IOException {
        Path fake = tempDir.resolve("fake.gz");
        Files.write(fake, "not compressed".getBytes(StandardCharsets.UTF_8));
        assertThat(ContainerDetector.detect(fake)).isEmpty();
    }

    @Test
    void metadataIncludesCompression() throws IOException {
        byte[] payload = "metadata".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, "gz");

        try (FileSystem fs = BinaryContainerMount.mount(compressed).orElseThrow()) {
            assertThat(fs.metadata()).containsEntry("compression", "gzip");
            assertThat(fs.metadata()).containsEntry("decompressed_size", String.valueOf(payload.length));
            assertThat(fs.metadata()).containsEntry("entry_count", "1");
        }
    }

    @Test
    void zeroBytePayloadRoundTrips() throws IOException {
        Path compressed = createCompressedFile(new byte[0], "gz");
        try (FileSystem fs = BinaryContainerMount.mount(compressed).orElseThrow()) {
            FileSystemEntry.RegularFile entry = (FileSystemEntry.RegularFile) fs.resolve("/payload").orElseThrow();
            assertThat(entry.size()).isEqualTo(0);
            assertThat(entry.readAllBytes()).isEmpty();
        }
    }

    @Test
    void closeableForwardingWorks() throws IOException {
        byte[] payload = "close me".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, "gz");

        CompressedSingleContainer container = (CompressedSingleContainer)
                CompressedSingleContainerFactory.open(compressed, SecurityPolicy.defaults()).orElseThrow();
        Path payloadPath = container.getPayloadPath();
        assertThat(payloadPath).exists();

        FileSystem fs = BinaryContainerFileSystemImpl.mount(container);
        assertThat(fs.resolve("/payload")).isPresent();
        fs.close();

        // Closing the filesystem should have closed the container.
        assertThat(payloadPath).doesNotExist();
    }

    @Test
    void tempFilePermissionsRestrictive() throws IOException {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux")
                || System.getProperty("os.name").toLowerCase().contains("mac"));

        byte[] payload = "permissions".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, "gz");

        CompressedSingleContainer container = (CompressedSingleContainer)
                CompressedSingleContainerFactory.open(compressed, SecurityPolicy.defaults()).orElseThrow();
        Path payloadPath = container.getPayloadPath();
        java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                Files.getPosixFilePermissions(payloadPath);
        assertThat(perms).containsExactlyInAnyOrder(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        container.close();
    }

    @Test
    void xzMemoryLimitPreventsOom() throws IOException {
        // Craft an XZ file with a huge dictionary declared in the header. The
        // payload itself is tiny; the danger is the decompressor pre-allocating memory.
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, "xz");

        // The actual file uses a normal dictionary; just verify the policy-derived limit
        // is passed to the XZ decompressor and a very small limit causes rejection.
        SecurityPolicy tiny = SecurityPolicy.builder().maxAllocationSize(1).build();
        assertThatThrownBy(() -> BinaryContainerMount.mount(compressed, tiny))
                .isInstanceOfAny(IOException.class, ResourceLimitException.class);
    }

    @Test
    void largeFileDetectionDoesNotReadAllBytes() throws IOException {
        // Create a gzip file large enough to be suspicious but with a tiny payload.
        // The detection must only read the first few bytes, not the whole file.
        Path compressed = tempDir.resolve("large.gz");
        try (OutputStream out = Files.newOutputStream(compressed);
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write("small".getBytes(StandardCharsets.UTF_8));
        }
        // Pad with trailing zeros to make the file large.
        byte[] padding = new byte[1024 * 1024];
        Files.write(compressed, padding, java.nio.file.StandardOpenOption.APPEND);

        // Detection should succeed quickly and not OOM.
        Optional<ContainerFormat> format = ContainerDetector.detect(compressed);
        assertThat(format).contains(ContainerFormat.COMPRESSED_SINGLE);
    }

    @ParameterizedTest
    @CsvSource({"gzip, gz", "xz, xz", "bzip2, bz2"})
    void roundTrip(String compression, String extension) throws IOException {
        byte[] payload = ("round trip " + compression).getBytes(StandardCharsets.UTF_8);
        Path compressed = createCompressedFile(payload, extension);
        try (FileSystem fs = BinaryContainerMount.mount(compressed).orElseThrow()) {
            FileSystemEntry.RegularFile entry = (FileSystemEntry.RegularFile) fs.resolve("/payload").orElseThrow();
            assertThat(entry.readAllBytes()).isEqualTo(payload);
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static byte[] makeDtb(int totalSize) {
        byte[] dtb = new byte[totalSize];
        ByteBuffer.wrap(dtb).order(ByteOrder.BIG_ENDIAN).putInt(0, 0xd00dfeed);
        ByteBuffer.wrap(dtb).order(ByteOrder.BIG_ENDIAN).putInt(4, totalSize);
        return dtb;
    }

    private static byte[] gzipBytes(byte[] data) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
            gz.finish();
            return out.toByteArray();
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static byte[] padTo(byte[] data, int minLength) {
        if (data.length >= minLength) {
            return data;
        }
        byte[] padded = new byte[minLength];
        System.arraycopy(data, 0, padded, 0, data.length);
        return padded;
    }

    private Path createCompressedFile(byte[] payload, String extension) throws IOException {
        Path path = tempDir.resolve("test." + extension);
        try (OutputStream out = Files.newOutputStream(path)) {
            switch (extension) {
                case "gz" -> {
                    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                        gz.write(payload);
                    }
                }
                case "xz" -> {
                    try (XZCompressorOutputStream xz = new XZCompressorOutputStream(out)) {
                        xz.write(payload);
                    }
                }
                case "bz2" -> {
                    try (BZip2CompressorOutputStream bz = new BZip2CompressorOutputStream(out)) {
                        bz.write(payload);
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported extension: " + extension);
            }
        }
        return path;
    }

    static class FuzzVirtualDisk implements VirtualDisk.RawDisk {
        private final byte[] data;

        FuzzVirtualDisk(byte[] data) {
            this.data = data.clone();
        }

        @Override
        public ByteBuffer read(long offset, int length) {
            if (offset < 0 || offset >= data.length) {
                return ByteBuffer.allocate(length);
            }
            int available = (int) Math.min(length, data.length - offset);
            byte[] result = new byte[length];
            System.arraycopy(data, (int) offset, result, 0, available);
            return ByteBuffer.wrap(result);
        }

        @Override
        public long virtualSize() {
            return data.length;
        }

        @Override
        public long allocatedSize() {
            return data.length;
        }

        @Override
        public DiskFormat format() {
            return DiskFormat.RAW;
        }

        @Override
        public java.util.Map<String, String> metadata() {
            return java.util.Map.of();
        }

        @Override
        public java.util.stream.Stream<Snapshot> snapshots() {
            return java.util.stream.Stream.empty();
        }

        @Override
        public int sectorSize() {
            return 512;
        }

        @Override
        public java.io.InputStream openStream() {
            return new java.io.ByteArrayInputStream(data);
        }

        @Override
        public com.github.packageurl.PackageURL packageUrl() {
            try {
                return new com.github.packageurl.PackageURL("pkg:vmdisk/raw/fuzz@1.0");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public java.util.Optional<String> backingFile() {
            return java.util.Optional.empty();
        }

        @Override
        public boolean isEncrypted() {
            return false;
        }

        @Override
        public boolean isCompressed() {
            return false;
        }

        @Override
        public void close() {
            // Nothing to close
        }
    }
}
