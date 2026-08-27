/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron;

import io.spicelabs.saffron.SaffronProbe.Kind;
import io.spicelabs.saffron.SaffronProbe.Result;
import io.spicelabs.saffron.container.ContainerDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link SaffronProbe}, the stream-friendly, byte-range-only
 * detector.
 *
 * <p>Two families of tests are exercised with a {@code null} file name so that
 * only <em>content</em> detection is at play (extension fallbacks are disabled):
 * this is what makes a right-name/wrong-assertion test impossible to slip past.
 */
class SaffronProbeTest {

    private static final String RES = "src/test/resources";
    private static final String CORPUS = Path.of("test-corpus").toAbsolutePath().toString();

    static boolean corpusExists() {
        return Files.isDirectory(Paths.get(CORPUS));
    }

    // ── Disk format head magics (content-only, null name) ──────────────────

    @Test
    void qcow2Magic_returnsDiskQcow2() throws IOException {
        byte[] prefix = readPrefix(res("qcow2/magic-only.qcow2"));
        assertKind(SaffronProbe.detect(prefix, null, null), Kind.DISK_QCOW2);
    }

    @Test
    void vmdkMagic_returnsDiskVmdk() throws IOException {
        byte[] prefix = readPrefix(res("vmdk/magic-only.vmdk"));
        assertKind(SaffronProbe.detect(prefix, null, null), Kind.DISK_VMDK);
    }

    @Test
    void vhdxMagic_returnsDiskVhdx() throws IOException {
        byte[] prefix = readPrefix(res("vhdx/magic-only.vhdx"));
        assertKind(SaffronProbe.detect(prefix, null, null), Kind.DISK_VHDX);
    }

    @Test
    void vdiTextSignature_returnsDiskVdi() throws IOException {
        byte[] prefix = readPrefix(res("vdi/magic-only.vdi"));
        assertKind(SaffronProbe.detect(prefix, null, null), Kind.DISK_VDI);
    }

    @Test
    void vhdFooter_returnsDiskVhd() {
        // A proper VHD has "conectix" at the start of its trailing 512-byte footer.
        byte[] suffix = new byte[SaffronProbe.MIN_SUFFIX];
        byte[] cookie = "conectix".getBytes();
        System.arraycopy(cookie, 0, suffix, 0, cookie.length);
        byte[] prefix = new byte[16];

        assertKind(SaffronProbe.detect(prefix, suffix, null), Kind.DISK_VHD);
    }

    @Test
    void rawGptSignature_returnsDiskRaw() {
        byte[] prefix = new byte[2048];
        // A real GPT has a protective MBR (non-zero boot area + 55 AA) before the
        // "EFI PART" signature at offset 512. Keep bytes 0..511 non-zero so the
        // prefix is not mistaken for RPi firmware (all-zero boot area).
        Arrays.fill(prefix, 0, 512, (byte) 0xee);
        prefix[510] = 0x55;
        prefix[511] = (byte) 0xaa;
        System.arraycopy("EFI PART".getBytes(), 0, prefix, 512, 8);

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.DISK_RAW);
    }

    @Test
    void rawMbrSignature_returnsDiskRaw() {
        byte[] prefix = new byte[1024];
        prefix[510] = 0x55;
        prefix[511] = (byte) 0xaa;

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.DISK_RAW);
    }

    @Test
    void rawByExtension_returnsDiskRaw() {
        byte[] prefix = new byte[4096];
        new Random(1).nextBytes(prefix);

        assertKind(SaffronProbe.detect(prefix, null, "disk.img"), Kind.DISK_RAW);
    }

    @Test
    void gzipWrappedRaw_requiresExtension() {
        byte[] prefix = new byte[]{0x1f, (byte) 0x8b, 0x08, 0};

        // gzip magic alone is a compressed single payload, not a wrapped raw.
        assertKind(SaffronProbe.detect(prefix, null, "payload.gz"), Kind.CONTAINER_COMPRESSED_SINGLE);

        // gzip magic + .raw.gz extension is a wrapped raw disk.
        assertKind(SaffronProbe.detect(prefix, null, "disk.raw.gz"), Kind.DISK_GZIP_WRAPPED_RAW);
    }

    // ── Container formats (content-only, null name) ────────────────────────

    @Test
    void elfMagic_returnsContainerElf() {
        byte[] prefix = minimalElf32();

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.CONTAINER_ELF);
    }

    @Test
    void androidBoot_returnsContainerAndroidBoot() {
        byte[] prefix = androidBoot();

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.CONTAINER_ANDROID_BOOT);
    }

    @Test
    void deviceTreeMagic_returnsContainerDtb() {
        // FIT/DTB share the d00dfeed magic; a 4096-byte prefix cannot classify a
        // large FIT's structure block, so the coarse DTB kind is reported.
        byte[] prefix = new byte[4096];
        prefix[0] = (byte) 0xd0;
        prefix[1] = 0x0d;
        prefix[2] = (byte) 0xfe;
        prefix[3] = (byte) 0xed;

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.CONTAINER_DTB);
    }

    @Test
    void wimMagic_returnsContainerWim() throws IOException {
        byte[] prefix = readPrefix(res("wim/valid.wim"));

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.CONTAINER_WIM);
    }

    @Test
    void linuxKernel_returnsContainerLinuxKernel() throws IOException {
        byte[] prefix = readPrefix(res("linux-kernel/iotgoat-x86-vmlinuz"));

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.CONTAINER_LINUX_KERNEL);
    }

    @Test
    void rpiFirmware_returnsContainerRpiFirmware() throws IOException {
        byte[] prefix = readPrefix(res("rpi-firmware/bootcode.bin"));

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.CONTAINER_RPI_FIRMWARE);
    }

    @Test
    void dmgFooter_returnsContainerDmg() throws IOException {
        byte[] suffix = readSuffix(res("dmg/valid.dmg"));
        byte[] prefix = new byte[16];

        assertKind(SaffronProbe.detect(prefix, suffix, null), Kind.CONTAINER_DMG);
    }

    // ── Bare filesystem images (content-only, null name) ───────────────────

    @Test
    void extSuperblock_returnsFilesystemExt() {
        byte[] prefix = extSuperblock();

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_EXT);
    }

    @Test
    void squashfsSuperblock_returnsFilesystemSquashfs() throws IOException {
        byte[] prefix = readPrefix(res("squashfs/alpine-minimal.squashfs"));

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_SQUASHFS);
    }

    @Test
    void jffs2Magic_returnsFilesystemJffs2() throws IOException {
        byte[] prefix = readPrefix(res("jffs2/fixtures/tree-zlib.jffs2"));

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_JFFS2);
    }

    @Test
    void cramfsMagic_returnsFilesystemCramfs() throws IOException {
        byte[] prefix = readPrefix(res("cramfs/fixtures/tree.cramfs"));

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_CRAMFS);
    }

    @Test
    void yaffs2Image_returnsFilesystemYaffs2() throws IOException {
        byte[] prefix = readPrefix(res("yaffs2/wild/unblob-sample.2048.64.le.yaffs2"));

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_YAFFS2);
    }

    @Test
    void ubifsImage_returnsFilesystemUbifs() throws IOException {
        byte[] prefix = readPrefix(res("ubi/wild/banana-zlib.ubifs"));

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_UBIFS);
    }

    @Test
    void ubiContainer_returnsContainerUbi() throws IOException {
        byte[] prefix = readPrefix(res("ubi/wild/fruits.ubi"));

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.CONTAINER_UBI);
    }

    @Test
    void jffs2Extension_returnsDiskRaw() {
        // JFFS2 images have no standard extension in the wild, but a caller
        // may name one .jffs2: the extension fallback (mirroring
        // DiskFormat.detectByExtension) treats it like other bare-filesystem
        // raw images.
        byte[] prefix = new byte[4096];
        new Random(3).nextBytes(prefix);

        assertKind(SaffronProbe.detect(prefix, null, "rootfs.jffs2"), Kind.DISK_RAW);
    }

    @Test
    void fatWithBootSignature_isNotShadowedByRaw() {
        // FAT carries 55 AA at offset 510 (a "raw MBR" signal). Filesystem
        // detection must run before the raw-MBR heuristic or this would be
        // reported as DISK_RAW.
        byte[] prefix = fatBootSector();

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_FAT);
    }

    @Test
    void ntfsWithBootSignature_isNotShadowedByRaw() {
        byte[] prefix = ntfsBootSector();

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_NTFS);
    }

    @Test
    void xfsMagic_returnsFilesystemXfs() {
        byte[] prefix = new byte[512];
        byte[] magic = "XFSB".getBytes();
        System.arraycopy(magic, 0, prefix, 0, magic.length);

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_XFS);
    }

    @Test
    void swapSignature_returnsFilesystemSwap() {
        byte[] prefix = new byte[4096];
        byte[] magic = "SWAPSPACE2".getBytes();
        System.arraycopy(magic, 0, prefix, 4086, magic.length);

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_SWAP);
    }

@Test
    void btrfsSuperblock_returnsFilesystemBtrfs() {
        // Btrfs superblock magic lives at offset 65536 + 64, reachable at the
        // (128 KiB) MIN_PREFIX.
        byte[] prefix = new byte[SaffronProbe.MIN_PREFIX];
        byte[] magic = "_BHRfS_M".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, prefix, 65536 + 64, magic.length);

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_BTRFS);
    }

    @Test
    void exFatSuperblock_returnsFilesystemExfat() {
        byte[] prefix = exFatBootSector();

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_EXFAT);
    }

    @Test
    void hfsPlusSignature_returnsFilesystemHfsPlus() {
        byte[] prefix = new byte[2048];
        ByteBuffer bb = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN);
        bb.putShort(1024, (short) 0x482B); // HFS+ volume signature
        bb.putInt(1024 + 40, 4096);        // block size
        bb.putInt(1024 + 44, 100);         // total blocks

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_HFSPLUS);
    }

    @Test
    void apfsSignature_returnsFilesystemApfs() {
        byte[] prefix = new byte[128];
        ByteBuffer bb = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(32, 0x4253584E); // "NXSB"
        bb.putInt(36, 4096);       // block size

        assertKind(SaffronProbe.detect(prefix, null, null), Kind.FILESYSTEM_APFS);
    }

    @Test
    void qcow2Extension_returnsDiskQcow2() {
        byte[] prefix = new byte[4096];
        new Random(2).nextBytes(prefix);

        assertKind(SaffronProbe.detect(prefix, null, "disk.qcow2"), Kind.DISK_QCOW2);
    }

    @Test
    void amiExtension_returnsDiskAmi() {
        byte[] prefix = new byte[4096];

        assertKind(SaffronProbe.detect(prefix, null, "bundle.manifest.xml"), Kind.DISK_AMI);
    }

    // ── Negative / robustness (R3: never throws, empty result) ─────────────

    @Test
    void nullPrefix_returnsEmpty() {
        assertThat(SaffronProbe.detect(null, null, "x.qcow2")).isEmpty();
    }

    @Test
    void emptyPrefix_noExtension_returnsEmpty() {
        assertThat(SaffronProbe.detect(new byte[0], null, null)).isEmpty();
    }

    @Test
    void randomBytes_noExtension_returnsEmpty() {
        byte[] prefix = new byte[4096];
        new Random(12345L).nextBytes(prefix);
        assertThat(SaffronProbe.detect(prefix, null, null)).isEmpty();
    }

    @Test
    void truncatedPrefix_returnsEmpty() {
        byte[] tooShort = new byte[3]; // shorter than any head magic
        assertThat(SaffronProbe.detect(tooShort, null, null)).isEmpty();
    }

    @Test
    void nullSuffix_withValidPrefix_stillDetects() throws IOException {
        byte[] prefix = readPrefix(res("qcow2/magic-only.qcow2"));
        assertKind(SaffronProbe.detect(prefix, null, null), Kind.DISK_QCOW2);
    }

    @Test
    void nullFileName_contentStillDetects() throws IOException {
        byte[] prefix = readPrefix(res("qcow2/magic-only.qcow2"));
        assertThat(SaffronProbe.detect(prefix, new byte[0], null)).isPresent();
    }

    @Test
    void neverThrows_onPathologicalInputs() {
        // Various malformed arrays must never raise, per R3.
        assertThatCode(() -> SaffronProbe.detect(new byte[1], new byte[1], null)).doesNotThrowAnyException();
        assertThatCode(() -> SaffronProbe.detect(new byte[5], null, "")).doesNotThrowAnyException();
        assertThatCode(() -> SaffronProbe.detect(new byte[100], new byte[3], "x")).doesNotThrowAnyException();
        assertThatCode(() -> SaffronProbe.detect(null, null, null)).doesNotThrowAnyException();
    }

    // ── Determinism / purity (R4) ──────────────────────────────────────────

    @Test
    void repeatedCalls_areDeterministic() throws IOException {
        byte[] prefix = readPrefix(res("qcow2/magic-only.qcow2"));
        Result a = SaffronProbe.detect(prefix, null, null).orElseThrow();
        Result b = SaffronProbe.detect(prefix, null, null).orElseThrow();
        assertThat(a).isEqualTo(b);
        assertThat(a.kind()).isEqualTo(Kind.DISK_QCOW2);
    }

    @Test
    void detect_doesNotMutateCallerArrays() {
        byte[] prefix = new byte[4096];
        new Random(9).nextBytes(prefix);
        byte[] suffix = new byte[512];
        new Random(10).nextBytes(suffix);
        byte[] prefixCopy = prefix.clone();
        byte[] suffixCopy = suffix.clone();

        SaffronProbe.detect(prefix, suffix, "a.vmdk");

        assertThat(prefix).isEqualTo(prefixCopy);
        assertThat(suffix).isEqualTo(suffixCopy);
    }

    // ── Parity (R2) ────────────────────────────────────────────────────────

    @Test
    @EnabledIf("corpusExists")
    void parity_noFalseNegatives_onDiskImages() throws IOException {
        List<String> images = List.of(
                "qcow2/modern/cirros-0.6.2-x86_64.qcow2",
                "qcow2/modern/alpine-3.19-cloud-amd64.qcow2",
                "qcow2/cloud/ubuntu/ubuntu-24.04-server-cloudimg-amd64.qcow2",
                "qcow2/modern/ubuntu-22.04-cloudimg-amd64.qcow2",
                "qcow2/modern/rocky-9-cloud-amd64.qcow2",
                "qcow2/cloud/fedora/fedora-40-cloud-base-amd64.qcow2",
                "qcow2/modern/debian-12-generic-amd64.qcow2",
                "vhd/native/ubuntu/ubuntu-24.04-azure-amd64.vhd",
                "vhd/modern/ubuntu-22.04-azure.vhd",
                "vdi/modern/ubuntu-22.04-vbox.vdi",
                "vdi/modern/debian-11-vbox.vdi",
                "vmdk/modern/ubuntu-21.04-vmware.vmdk",
                "vmdk/native/photon/photon-5.0-x86_64.vmdk",
                "raw/cloud/debian/debian-12-genericcloud-amd64.raw");

        for (String rel : images) {
            Path p = corpusPath(rel);
            if (!Files.isRegularFile(p)) {
                continue;
            }
            Optional<Result> probe = SaffronProbe.detect(readPrefix(p), readSuffix(p), p.getFileName().toString());
            boolean legacy = DiskFormat.detect(p).isPresent() || ContainerDetector.detect(p).isPresent();

            assertThat(probe.isPresent())
                    .as("SaffronProbe present parity for " + rel + " (legacy=" + legacy + ")")
                    .isEqualTo(legacy);
            assertThat(legacy).as("fixture should be a supported artifact: " + rel).isTrue();
        }
    }

    @Test
    void parity_noFalseNegatives_onBareFilesystem() throws IOException {
        // A bare squashfs image has no disk/container magic; DiskFormat detects it
        // only via the .squashfs extension, and the probe via its superblock magic.
        // Both must report present.
        Path p = res("squashfs/alpine-minimal.squashfs");
        Optional<Result> probe = SaffronProbe.detect(readPrefix(p), readSuffix(p), p.getFileName().toString());
        boolean legacy = DiskFormat.detect(p).isPresent() || ContainerDetector.detect(p).isPresent();

        assertThat(probe.isPresent()).as("squashfs probe present").isTrue();
        assertThat(legacy).as("squashfs legacy present").isTrue();
        assertThat(probe.get().kind()).isEqualTo(Kind.FILESYSTEM_SQUASHFS);
    }

    @Test
    @EnabledIf("corpusExists")
    void parity_noFalsePositives_onNonArtifacts() throws IOException {
        // A plain text file and a random blob must be rejected by both.
        Path txt = corpusPath("manifest.json");
        if (Files.isRegularFile(txt)) {
            byte[] prefix = readPrefix(txt);
            Optional<Result> probe = SaffronProbe.detect(prefix, readSuffix(txt), txt.getFileName().toString());
            assertThat(probe.isPresent())
                    .as("manifest.json must not be a Saffron artifact")
                    .isEqualTo(DiskFormat.detect(txt).isPresent() || ContainerDetector.detect(txt).isPresent());
            assertThat(probe).isEmpty();
        }
    }

    @Test
    void parity_randomBytes_bothEmpty() throws IOException {
        // Local, corpus-independent: random bytes with no extension must be
        // rejected by both SaffronProbe and DiskFormat/ContainerDetector.
        java.nio.file.Path tmp = Files.createTempFile("saffron-probe-", ".bin");
        try {
            byte[] prefix = new byte[4096];
            new Random(77).nextBytes(prefix);
            Files.write(tmp, prefix);
            Optional<Result> probe = SaffronProbe.detect(prefix, null, tmp.getFileName().toString());
            boolean legacy = DiskFormat.detect(tmp).isPresent() || ContainerDetector.detect(tmp).isPresent();
            assertThat(probe.isPresent()).as("random .bin").isEqualTo(legacy);
            assertThat(probe).isEmpty();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static Path res(String rel) {
        return Path.of(RES, rel);
    }

    private static void assertKind(Optional<Result> result, Kind kind) {
        assertThat(result).isPresent()
                .hasValueSatisfying(r -> assertThat(r.kind()).isEqualTo(kind));
    }

    private static Path corpusPath(String rel) {
        return Path.of(CORPUS, rel);
    }

    private static byte[] readPrefix(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[SaffronProbe.MIN_PREFIX];
            int n = in.read(buf);
            return n < buf.length ? Arrays.copyOf(buf, n) : buf;
        }
    }

    private static byte[] readSuffix(Path p) throws IOException {
        long size = Files.size(p);
        int len = (int) Math.min(SaffronProbe.MIN_SUFFIX, size);
        byte[] buf = new byte[len];
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            ch.position(size - len);
            ch.read(ByteBuffer.wrap(buf));
        }
        return buf;
    }

    /** A minimal, self-consistent 32-bit ELF header (52 bytes). */
    private static byte[] minimalElf32() {
        byte[] b = new byte[64];
        b[0] = 0x7f;
        b[1] = 'E';
        b[2] = 'L';
        b[3] = 'F';
        b[4] = 1;      // ELFCLASS32
        b[5] = 1;      // ELFDATA2LSB
        b[6] = 1;      // EV_CURRENT
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort(16, (short) 2);     // e_type = ET_EXEC
        bb.putShort(18, (short) 3);     // e_machine = EM_386
        bb.putInt(20, 1);               // e_version
        bb.putShort(40, (short) 52);    // e_ehsize
        bb.putShort(42, (short) 32);    // e_phentsize (must equal PHDR_SIZE_32)
        bb.putShort(46, (short) 40);    // e_shentsize (SHDR_SIZE_32)
        // e_phnum=0, e_shnum=0, e_shstrndx=0 (SHN_UNDEF) are already 0.
        return b;
    }

    /** A synthetic Android boot image header (v2). */
    private static byte[] androidBoot() {
        byte[] b = new byte[4096];
        System.arraycopy("ANDROID!".getBytes(), 0, b, 0, 8);
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(36, 2048);  // page_size = 2048 (supported power of two)
        bb.putInt(40, 2);     // header version = 2
        return b;
    }

    /** A minimal ext2/3/4 superblock with valid magic. */
    private static byte[] extSuperblock() {
        byte[] b = new byte[4096];
        int sb = 1024;
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(sb + 0, 128);            // s_inodes_count
        bb.putInt(sb + 4, 8);              // s_blocks_count
        bb.putInt(sb + 12, 0);             // s_free_blocks_count
        bb.putInt(sb + 16, 0);             // s_free_inodes_count
        bb.putInt(sb + 24, 0);             // s_log_block_size = 0 (1024 bytes)
        bb.putShort(sb + 56, (short) 0xEF53); // s_magic
        return b;
    }

    /** A minimal FAT boot sector (carries 55 AA at 510). */
    private static byte[] fatBootSector() {
        byte[] b = new byte[4096];
        b[0] = (byte) 0xEB;
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort(11, (short) 512);   // bytes_per_sector
        bb.put(13, (byte) 1);           // sectors_per_cluster
        bb.putShort(14, (short) 1);     // reserved_sectors
        bb.put(16, (byte) 2);           // num_fats
        bb.putShort(17, (short) 512);   // root_entry_count
        bb.putShort(19, (short) 100);   // total_sectors_16
        bb.putShort(22, (short) 1);     // sectors_per_fat_16
        b[510] = 0x55;
        b[511] = (byte) 0xAA;
        return b;
    }

    /** A minimal NTFS boot sector (carries 55 AA at 510). */
    private static byte[] ntfsBootSector() {
        byte[] b = new byte[4096];
        byte[] oem = "NTFS    ".getBytes();
        System.arraycopy(oem, 0, b, 3, oem.length);
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort(11, (short) 512);  // bytes_per_sector
        bb.put(13, (byte) 8);          // sectors_per_cluster
        bb.putLong(40, 1024L);         // total_sectors
        b[510] = 0x55;
        b[511] = (byte) 0xAA;
        return b;
    }

    /** A minimal exFAT boot sector (carries 55 AA at 510). */
    private static byte[] exFatBootSector() {
        byte[] b = new byte[4096];
        b[0] = (byte) 0xEB;
        byte[] fsName = "EXFAT   ".getBytes();
        System.arraycopy(fsName, 0, b, 3, fsName.length);
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort(104, (short) 0x0100); // fs_revision = 1.0
        bb.put(108, (byte) 9);            // bytes_per_sector_shift = 512
        bb.put(109, (byte) 0);            // sectors_per_cluster_shift
        bb.putLong(72, 1024L);            // volume_length
        bb.putInt(88, 128);               // cluster_heap_offset
        bb.putInt(92, 4);                 // cluster_count
        b[510] = 0x55;
        b[511] = (byte) 0xAA;
        return b;
    }
}