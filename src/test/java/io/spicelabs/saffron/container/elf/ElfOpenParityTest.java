/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.elf;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.container.BinaryContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defensive-semantics parity tests (phase 7, R7.3).
 *
 * <h2>LLM section</h2>
 * <p>All three open variants — {@code open(Path)}, {@code open(ByteBuffer)},
 * and {@code open(VirtualDisk)} — must agree on the same bytes and never
 * escape unchecked exceptions for malformed input (never-throw
 * invariant).</p>
 */
class ElfOpenParityTest {

    @Test
    void openVariantsAgreeOnMalformedInputs(@TempDir Path dir) throws IOException {
        Random rnd = new Random(0xB0B1E & 0xfffffff);
        for (int i = 0; i < 50; i++) {
            byte[] bytes = new byte[rnd.nextInt(2048)];
            rnd.nextBytes(bytes);

            Optional<BinaryContainer> fromBuffer = ElfContainer.open(ByteBuffer.wrap(bytes), bytes.length);

            Path file = dir.resolve("fuzz-" + i + ".bin");
            Files.write(file, bytes);
            Optional<BinaryContainer> fromPath = ElfContainer.open(file);

            io.spicelabs.saffron.VirtualDisk disk = new TestDisk(bytes);
            Optional<BinaryContainer> fromDisk = ElfContainer.open(disk);

            assertThat(fromPath).as("iteration %d", i).isEqualTo(fromBuffer);
            assertThat(fromDisk).as("iteration %d", i).isEqualTo(fromBuffer);
            fromPath.ifPresent(c -> {
                try {
                    c.close();
                } catch (IOException ignored) {
                }
            });
            fromDisk.ifPresent(c -> {
                try {
                    c.close();
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void openVariantsNeverEscapeUncheckedOnNearValidCorruption(@TempDir Path dir) throws IOException {
        // A parseable ELF whose section NAME offset points outside the
        // string table: buildEntries throws IllegalArgumentException on the
        // unchecked path - the defensive catch must convert it to empty.
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.shName1 = 0xFFFF; // name offset far beyond the strtab
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS64, true,
                "payload".getBytes(), ".data", overrides);
        byte[] bytes = elf.array().clone();
        Path file = dir.resolve("corrupt-name.elf");
        Files.write(file, bytes);

        Optional<BinaryContainer> fromPath = ElfContainer.open(file);
        Optional<BinaryContainer> fromDisk = ElfContainer.open(new TestDisk(bytes));
        assertThat(fromPath).isEmpty();
        assertThat(fromDisk).isEmpty();
    }

    @Test
    void openVariantsAgreeOnValidElf(@TempDir Path dir) throws IOException {
        ByteBuffer elf = ElfTestFixtures.buildValidElf(ElfTestFixtures.ELFCLASS64, true);
        byte[] bytes = elf.array().clone();
        Path file = dir.resolve("valid.elf");
        Files.write(file, bytes);

        Optional<BinaryContainer> fromBuffer = ElfContainer.open(ByteBuffer.wrap(bytes), bytes.length);
        Optional<BinaryContainer> fromPath = ElfContainer.open(file);
        Optional<BinaryContainer> fromDisk = ElfContainer.open(new TestDisk(bytes));

        assertThat(fromBuffer).isPresent();
        assertThat(fromPath).isPresent();
        assertThat(fromDisk).isPresent();
        fromBuffer.get().close();
        fromPath.get().close();
        fromDisk.get().close();
    }

    /** Minimal in-memory VirtualDisk over a byte array. */
    static final class TestDisk implements io.spicelabs.saffron.VirtualDisk.RawDisk {
        private final byte[] data;

        TestDisk(byte[] data) {
            this.data = data;
        }

        @Override
        public ByteBuffer read(long offset, int length) {
            int available = (int) Math.min(length, data.length - offset);
            byte[] out = new byte[length];
            System.arraycopy(data, (int) offset, out, 0, available);
            return ByteBuffer.wrap(out);
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
        public java.util.stream.Stream<io.spicelabs.saffron.VirtualDisk.Snapshot> snapshots() {
            return java.util.stream.Stream.empty();
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
        public java.util.Optional<String> backingFile() {
            return java.util.Optional.empty();
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
                return new com.github.packageurl.PackageURL("pkg:vmdisk/raw/test@1.0");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
        }
    }
}
