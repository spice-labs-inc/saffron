# Ground Truth Rebuild Plan: Per-Filesystem Testing

## Implementation Status

| Phase | Status | Description |
|-------|--------|-------------|
| Phase 1 | ✅ Complete | Scanner updated with classification logic |
| Phase 2 | ✅ Complete | Java data classes updated with new fields |
| Phase 3 | ✅ Complete | Per-filesystem verification test created |
| Phase 4 | ⏸️ Blocked | Awaiting Docker environment to regenerate JSON |
| Phase 5 | ✅ Complete | Update existing tests to use per-filesystem ground truth |

---

## Executive Summary

This plan addresses the architectural gap in Saffron's test framework where ground truth data exists per-filesystem, but tests only verify aggregate counts across all filesystems. The rebuild ensures each filesystem (partition, LVM LV, etc.) is individually tested against its own ground truth.

**Current State:**
- Ground truth JSON files already contain per-filesystem data (`filesystems` array)
- Tests verify total file count across all filesystems only
- Individual filesystem behavior is not validated

**Target State:**
- Each filesystem is individually mounted and verified
- Specific expectations per filesystem (root LV vs home LV vs boot partition)
- Tests can assert filesystem-specific paths exist (/etc/debian_version on root, not home)

---

## 1. Current Ground Truth Structure

### JSON Schema (already supports per-filesystem)

```json
{
  "imagePath": "/corpus/vdi/modern/ubuntu-22.04-vbox.vdi",
  "imageBasename": "ubuntu-22.04-vbox.vdi",
  "filesystemCount": 2,
  "totalFiles": 128783,
  "totalDirectories": 15621,
  "filesystems": [
    {
      "device": "/dev/sda2",
      "fstype": "vfat",
      "fileCount": 8,
      "directoryCount": 3,
      "sampleFiles": [...]
    },
    {
      "device": "/dev/vgubuntu/root",
      "fstype": "ext4",
      "fileCount": 128775,
      "directoryCount": 15618,
      "sampleFiles": [...]
    }
  ]
}
```

### Java Data Classes

```java
// CorpusTestData.java - already supports this structure
static class CorpusImageData {
    String imagePath;
    String imageBasename;
    int filesystemCount;
    int totalFiles;
    int totalDirectories;
    List<FilesystemData> filesystems;  // <-- Already per-filesystem
    String error;
}

static class FilesystemData {
    String device;      // "/dev/vgubuntu/root" or "/dev/sda1"
    String fstype;      // "ext4", "vfat", "xfs"
    int fileCount;
    int directoryCount;
    List<SampleFile> sampleFiles;
}
```

---

## 2. Problem Analysis

### Issue: LvmTest Failure Example

The ubuntu-22.04-vbox.vdi image has TWO logical volumes:
- **root LV**: 510GB, contains /etc, /usr, /bin (the OS)
- **swap_1 LV**: 976MB, swap partition (no filesystem to mount)

Current test code:
```java
// LvmTest.java - line 148-179
Optional<LogicalVolumeDisk> lvOpt = vg.largestLogicalVolume();  // Gets root (510GB)

// Later, checks for root directories:
assertThat(fs.resolve("/etc")).isPresent();  // Passes - on root LV
assertThat(fs.resolve("/usr")).isPresent();  // Passes - on root LV
assertThat(fs.resolve("/bin")).isPresent();  // Passes - on root LV
```

This happens to work because `largestLogicalVolume()` returns the root LV. But if the test selected swap_1, all assertions would fail.

### Missing Test Coverage

1. **vfat boot partition**: Not tested at all (skipped by LVM-focused tests)
2. **home LV**: If present, not tested separately from root LV
3. **Multiple XFS filesystems**: Each should be individually verified

---

## 3. Proposed Changes

### 3.1 Enhanced Ground Truth JSON (New Fields)

Add filesystem classification to distinguish expected content:

```json
{
  "filesystems": [
    {
      "device": "/dev/sda2",
      "fstype": "vfat",
      "label": "EFI System Partition",
      "fileCount": 8,
      "directoryCount": 3,
      "purpose": "boot",           // NEW: boot, root, home, var, swap, unknown
      "isMountable": true,         // NEW: false for swap, LUKS
      "mountPoint": "/boot/efi",   // NEW: expected mount point (if known)
      "sampleFiles": [...]
    },
    {
      "device": "/dev/vgubuntu/root",
      "fstype": "ext4",
      "label": null,
      "fileCount": 128775,
      "directoryCount": 15618,
      "purpose": "root",           // NEW: identifies as root filesystem
      "isMountable": true,
      "mountPoint": "/",
      "expectedPaths": [           // NEW: paths that MUST exist
            "/etc",
            "/etc/debian_version",
            "/bin",
            "/usr",
            "/lib"
      ],
      "sampleFiles": [...]
    },
    {
      "device": "/dev/vgubuntu/swap_1",
      "fstype": "swap",
      "label": null,
      "fileCount": 0,
      "directoryCount": 0,
      "purpose": "swap",           // NEW: identifies as swap
      "isMountable": false,        // NEW: cannot be mounted as filesystem
      "sampleFiles": []
    }
  ]
}
```

### 3.2 Updated Java Data Classes

```java
// CorpusTestData.java
static class FilesystemData {
    String device;
    String fstype;
    String label;
    int fileCount;
    int directoryCount;

    // NEW FIELDS
    String purpose;           // "boot", "root", "home", "var", "swap", "data", "unknown"
    boolean isMountable;      // false for swap, LUKS, LVM2_member
    String mountPoint;        // expected mount point (e.g., "/", "/boot", "/home")
    List<String> expectedPaths;  // paths that must exist on this filesystem

    List<SampleFile> sampleFiles;

    // Helper methods
    boolean isRootFilesystem() {
        return "root".equals(purpose) || "/".equals(mountPoint);
    }

    boolean isBootFilesystem() {
        return "boot".equals(purpose) || "/boot".equals(mountPoint);
    }
}
```

### 3.3 New Test: PerFilesystemVerificationTest

Create a new JUnit 5 test that generates dynamic tests per filesystem:

```java
@TestFactory
@EnabledIf("corpusExists")
Stream<DynamicTest> verifyEachFilesystem() {
    List<DynamicTest> tests = new ArrayList<>();

    for (CorpusImageData imageData : allImages) {
        for (FilesystemData fsData : imageData.filesystems) {
            // Skip unmountable filesystems (swap, LUKS)
            if (!fsData.isMountable) {
                continue;
            }

            String testName = imageData.imageBasename + ":" + fsData.device;

            tests.add(DynamicTest.dynamicTest(testName, () -> {
                verifyFilesystem(imageData, fsData);
            }));
        }
    }

    return tests.stream();
}

private void verifyFilesystem(CorpusImageData image, FilesystemData expected) {
    try (VirtualDisk disk = DiskReader.open(resolvePath(image.imagePath))) {
        // Find the specific filesystem by device/LV name
        FileSystem fs = mountSpecificFilesystem(disk, expected.device);

        // INVARIANT 1: File count matches exactly
        long actualFiles = countFiles(fs);
        assertThat(actualFiles)
            .as("File count for %s on %s", expected.device, image.imageBasename)
            .isEqualTo(expected.fileCount);

        // INVARIANT 2: Directory count matches exactly
        long actualDirs = countDirectories(fs);
        assertThat(actualDirs)
            .as("Directory count for %s on %s", expected.device, image.imageBasename)
            .isEqualTo(expected.directoryCount);

        // INVARIANT 3: Expected paths exist (for root/boot filesystems)
        if (expected.expectedPaths != null) {
            for (String path : expected.expectedPaths) {
                assertThat(fs.resolve(path))
                    .as("Required path %s must exist on %s", path, expected.device)
                    .isPresent();
            }
        }

        // INVARIANT 4: Sample files exist with correct SHA256
        for (SampleFile sample : expected.sampleFiles) {
            FileSystemEntry entry = fs.resolve(sample.path)
                .orElseThrow(() -> new AssertionError(
                    "Sample file not found: " + sample.path));

            byte[] content = ((FileSystemEntry.RegularFile) entry).readAllBytes();
            String actualSha256 = sha256(content);

            assertThat(actualSha256)
                .as("SHA256 for %s on %s", sample.path, expected.device)
                .isEqualToIgnoringCase(sample.sha256);
        }
    }
}
```

### 3.4 Updated Scanner (scan_corpus.py)

Add logic to classify filesystems:

```python
def classify_filesystem(g, device, fstype, mount_point):
    """Determine the purpose of a filesystem (root, boot, home, etc.)."""

    # Swap is never mountable
    if fstype == 'swap':
        return {'purpose': 'swap', 'isMountable': False}

    # Try to mount and inspect
    try:
        g.mount_ro(device, '/')

        # Check for root filesystem indicators
        has_etc = g.exists('/etc')
        has_bin = g.exists('/bin')
        has_usr = g.exists('/usr')
        has_init = g.exists('/sbin/init') or g.exists('/usr/sbin/init')

        # Check for boot filesystem indicators
        has_efi = g.exists('/EFI') or glob.glob('/boot/efi/EFI/*')
        is_vfat = fstype == 'vfat'

        # Check for home filesystem indicators
        has_home_dirs = False
        if g.exists('/home'):
            # Check if /home has user directories
            home_entries = g.readdir('/home')
            has_home_dirs = any(e['name'] not in ('.', '..') for e in home_entries)

        g.umount_all()

        # Classify based on findings
        if has_etc and has_bin and has_usr and has_init:
            return {
                'purpose': 'root',
                'isMountable': True,
                'mountPoint': '/',
                'expectedPaths': ['/etc', '/bin', '/usr', '/lib']
            }
        elif is_vfat and has_efi:
            return {
                'purpose': 'boot',
                'isMountable': True,
                'mountPoint': '/boot/efi',
                'expectedPaths': ['/EFI']
            }
        elif has_home_dirs and not has_etc:
            return {
                'purpose': 'home',
                'isMountable': True,
                'mountPoint': '/home',
                'expectedPaths': []
            }
        else:
            return {
                'purpose': 'data',
                'isMountable': True,
                'mountPoint': None,
                'expectedPaths': []
            }

    except Exception as e:
        g.umount_all()
        return {'purpose': 'unknown', 'isMountable': True, 'error': str(e)}
```

---

## 4. Migration Strategy

### Phase 1: Update Scanner (1-2 days) ✅ COMPLETE

**Status:** Implemented and tested

**Changes Made:**

1. Modified `scan_corpus.py`:
   - Added `classify_filesystem()` - inspects mounted filesystem to determine purpose (root, boot, home, var, opt, data, unknown)
   - Added `classify_unmountable_filesystem()` - handles swap and LUKS partitions
   - Added new fields to JSON output:
     - `purpose`: "root", "boot", "home", "var", "opt", "data", "swap", "encrypted", "unknown"
     - `isMountable`: boolean (false for swap, LUKS)
     - `mountPoint`: expected mount point ("/", "/boot/efi", "/home", etc.)
     - `expectedPaths`: OS-specific paths that must exist (e.g., "/etc/debian_version")
   - Modified `scan_image()` to track unmountable filesystems (swap, LUKS) separately
   - Modified `scan_filesystem()` to call classifier and include new fields

2. Classification Logic:
   | Purpose | Detection Criteria |
   |---------|-------------------|
   | root | Has /etc, /bin, /usr |
   | boot | vfat with /EFI or /boot |
   | home | Has /home with user dirs, no /etc or /usr |
   | var | Has /var/log or /var/lib, no /usr |
   | opt | Has /opt with content, no /etc |
   | data | No system directories |
   | swap | fstype == "swap" |
   | encrypted | fstype == "crypto_LUKS" |

3. Testing:
   - Created `test_scanner.py` with unit tests for all classification scenarios
   - All 8 tests pass (root, boot, home, var, swap, LUKS, JSON structure, backward compatibility)

**Next Steps:** Build Docker image and run against test images when Docker is available.

### Phase 2: Update Java Data Classes (1 day) ✅ COMPLETE

**Status:** Implemented and tested

**Changes Made:**

1. Modified `src/test/java/io/spicelabs/saffron/corpus/CorpusTestData.java`:
   - Added new fields to `FilesystemData`:
     - `String purpose` - classification from scanner
     - `Boolean isMountable` - may be null for old JSON
     - `String mountPoint` - expected mount point
     - `List<String> expectedPaths` - paths that must exist
   - Added helper methods for backward compatibility:
     - `isMountable()` - returns default based on fstype if null
     - `getPurpose()` - infers from fstype if null
     - `getMountPoint()` - infers from purpose if null
     - `getExpectedPaths()` - returns defaults based on purpose
     - `isRootFilesystem()` - true if purpose is "root"
     - `isBootFilesystem()` - true if purpose is "boot"

2. Created comprehensive test class:
   - `CorpusTestDataTest.java` with 8 test cases
   - Tests backward compatibility with old JSON
   - Tests new field deserialization
   - Tests all classification scenarios
   - All tests pass

**Backward Compatibility:**
| Scenario | Behavior |
|----------|----------|
| Old JSON without new fields | Helper methods provide sensible defaults |
| New JSON with all fields | Uses actual values from JSON |
| Mixed old/new in corpus | Each file handled independently |

**Next Steps:** Phase 3 - Create per-filesystem verification test

### Phase 3: Create New Test Class (2-3 days) ✅ COMPLETE

**Status:** Implemented and compiled

**Changes Made:**

1. Created `src/test/java/io/spicelabs/saffron/corpus/PerFilesystemVerificationTest.java`:
   - Generates one dynamic test per mountable filesystem in corpus
   - Test naming: `imageBasename:device` (e.g., "ubuntu-22.04-vbox.vdi:/dev/vgubuntu/root")

2. Key Features:
   - **Device-to-mount mapping**: Parses device strings ("/dev/sda2", "/dev/vgubuntu/root") and mounts the corresponding filesystem
   - **Partition support**: Handles /dev/sda2, /dev/nvme0n1p3, /dev/vda1, /dev/xvda1, /dev/hda1, /dev/loop0p1
   - **LVM support**: Handles /dev/vgubuntu/root, /dev/mapper/vgubuntu-root
   - **Unmountable filtering**: Automatically skips swap and LUKS partitions

3. Verification Invariants:
   | Invariant | Description |
   |-----------|-------------|
   | File count | Must match ground truth exactly |
   | Directory count | Must match ground truth exactly |
   | Expected paths | All paths in `expectedPaths` must exist (e.g., /etc, /bin for root) |
   | Sample files | All sample files must exist with correct SHA256 |

4. Device Parsing Logic:
   ```java
   // Partitions: extract index from device string
   /dev/sda2       -> index 1 (0-based)
   /dev/nvme0n1p3  -> index 2
   /dev/vda1       -> index 0

   // LVM: extract LV name
   /dev/vgubuntu/root    -> LV name "root"
   /dev/mapper/vg-ubuntu-root -> LV name "root"
   ```

5. Summary Output:
   - Groups failures by image
   - Reports pass/fail counts per filesystem
   - Detailed error messages for debugging

**Build Status:** Compiles successfully

**Next Steps:** Phase 4 - Regenerate ground truth JSON with new fields

### Phase 4: Fix Scanner Issues & Regenerate Ground Truth (3-5 days) ⏸️ PARTIALLY COMPLETE - Docker Not Available

**Status:** Scanner fixes implemented, awaiting Docker for regeneration

**Issues Fixed:**

1. **Directory Count Mismatch** ✅
   - **Problem:** Scanner didn't count root directory ("/"), Saffron does
   - **Fix:** Added `dirs = ["/"]` in `walk_filesystem()` to include root
   - **File:** `tools/corpus-scanner/scan_corpus.py:359`

2. **EFI Partition Mounting** ✅
   - **Problem:** `FileSystemMount.findFilesystems()` skipped partitions < 200 sectors
   - **Fix:** Reduced threshold to 100 sectors (allows ~50MB EFI partitions)
   - **File:** `src/main/java/io/spicelabs/saffron/fs/FileSystemMount.java:91`

3. **Btrfs Subvolume Support** ✅
   - **Problem:** Test couldn't mount `btrfsvol:/dev/sda4/home` format
   - **Fix:** Added `mountBtrfsSubvolume()` method with subvolume path resolution
   - **File:** `src/test/java/io/spicelabs/saffron/corpus/PerFilesystemVerificationTest.java`

**Current State:**
- 70 JSON files exist (old format, without classification fields)
- Scanner ready with all fixes
- Regeneration scripts prepared

**To Complete Phase 4:**

Run on a system with Docker:
```bash
cd tools/corpus-scanner
./regenerate-all.sh
```

**Validation Checklist:**
- [ ] Directory counts match Saffron (includes root "/")
- [ ] EFI partitions mount correctly
- [ ] Btrfs subvolumes accessible
- [ ] All JSON files have `purpose` field
- [ ] All JSON files have `isMountable` field
- [ ] All JSON files have `expectedPaths` field

### Phase 5: Update/Deprecate Old Tests (1-2 days) ✅ COMPLETE

**Status:** Implemented and compiled

**Changes Made:**

1. **Updated `LvmTest.java`:**
   - Added ground truth loading (`loadGroundTruth()`)
   - Added LV-specific lookup (`findLvGroundTruth()`)
   - Rewrote `testMountFilesystemFromLvm()` to test EACH logical volume individually
   - Uses ground truth to verify:
     - Expected paths exist (from `expectedPaths`)
     - Root filesystem has /etc, /bin, /usr
     - Unmountable filesystems (swap) are correctly identified
   - Falls back to basic validation if no ground truth available

2. **Made `CorpusTestData` public:**
   - Classes now public so they can be used from other test packages (e.g., `lvm`)
   - All fields and methods are public for Gson deserialization and access

3. **Test behavior with old vs new ground truth:**

   | Scenario | Behavior |
   |----------|----------|
   | Old ground truth (no new fields) | Uses backward-compatible defaults, basic validation |
   | New ground truth (with fields) | Full per-LV validation with expected paths |
   | No ground truth | Basic validation (checks for /etc, /bin, /usr on root) |

**Example Test Output:**
```
Testing LV: root
  Mounted ext4 filesystem
  Ground truth found: purpose=root
  Verifying expected paths: [/etc, /bin, /usr, /etc/debian_version]
  ✓ All paths verified
Testing LV: swap_1
  No filesystem detected (may be swap or raw)
  ✓ Ground truth confirms unmountable (swap/LUKS)
```

---

## 5. Testing Strategy

### New Test: PerFilesystemVerificationTest

**Purpose:** Verify each filesystem individually matches its ground truth.

**Test Cases Generated:**
- One test per mountable filesystem in corpus
- Example: ubuntu-22.04-vbox.vdi generates 2 tests:
  - `ubuntu-22.04-vbox.vdi:/dev/sda2` (vfat boot)
  - `ubuntu-22.04-vbox.vdi:/dev/vgubuntu/root` (ext4 root)

**Invariants:**
1. File count matches ground truth exactly
2. Directory count matches ground truth exactly
3. All `expectedPaths` exist on the filesystem
4. All sample files have correct SHA256

**CI Considerations:**
- Use `@EnabledIf` to skip if ground truth file missing (CI mode)
- Use `@EnabledIf` to skip if image file missing (CI mode)
- Tests run in parallel at class level (Maven surefire config)

### Updated Test: LvmTest

**Purpose:** Specifically test LVM functionality using ground truth.

**Changes:**
```java
@Test
@EnabledIf("testFileExists")
void testEachLogicalVolume() throws IOException {
    Path testFile = Files.exists(VDI_TEST_FILE) ? VDI_TEST_FILE : DEBIAN_TEST_FILE;

    // Load ground truth for this image
    CorpusImageData groundTruth = loadGroundTruth(testFile);

    try (VirtualDisk disk = DiskReader.open(testFile)) {
        for (FilesystemData fsData : groundTruth.filesystems) {
            // Only test LVM filesystems
            if (!fsData.device.contains("/dev/") || !fsData.device.contains("/")) {
                continue;  // Skip non-LVM like /dev/sda1
            }

            // Mount and verify THIS specific LV
            FileSystem fs = mountLvByName(disk, extractLvName(fsData.device));

            // Verify against ground truth
            verifyFilesystemContents(fs, fsData);
        }
    }
}
```

---

## 6. Files to Modify

| File | Change Type | Description |
|------|-------------|-------------|
| `tools/corpus-scanner/scan_corpus.py` | Modify | Add filesystem classification; add new JSON fields |
| `src/test/java/io/spicelabs/saffron/corpus/CorpusTestData.java` | Modify | Add `purpose`, `isMountable`, `mountPoint`, `expectedPaths` fields |
| `src/test/java/io/spicelabs/saffron/corpus/PerFilesystemVerificationTest.java` | Create | New test class for per-filesystem verification |
| `src/test/java/io/spicelabs/saffron/lvm/LvmTest.java` | Modify | Use ground truth to test each LV individually |
| `src/test/resources/corpus-verification/*.json` | Regenerate | Run scanner to regenerate all ground truth files |

---

## 7. Backward Compatibility

### JSON Format Compatibility

Gson ignores unknown fields by default. Old Java code reading new JSON:
- **New fields in JSON**: Ignored by old Java code (safe)
- **New Java code reading old JSON**: New fields will be null/default (handled by helper methods)

```java
// Defensive helper for backward compatibility
static class FilesystemData {
    // ... existing fields ...

    String purpose;  // May be null for old JSON
    Boolean isMountable;  // May be null for old JSON

    // Default for old JSON without these fields
    boolean isMountable() {
        return isMountable != null ? isMountable : !"swap".equals(fstype);
    }

    String getPurpose() {
        if (purpose != null) return purpose;
        // Infer from fstype for old JSON
        if ("swap".equals(fstype)) return "swap";
        if ("vfat".equals(fstype)) return "boot";
        return "unknown";
    }
}
```

---

## 8. Rollback Plan

If issues arise:

1. **Revert JSON files**: Restore from git history
2. **Revert Java changes**: Remove new test class, revert data class changes
3. **Keep scanner changes**: New fields are additive and harmless

---

## 9. Success Criteria

1. **All filesystems tested**: Each mountable filesystem has a corresponding test
2. **LVM volumes individually validated**: Each LV verified against its own ground truth
3. **Root filesystem detection**: Tests confirm /etc, /bin, /usr exist on root LV, not just "any" filesystem
4. **Boot partition coverage**: vfat/EFI partitions are explicitly tested
5. **CI passes**: Tests gracefully skip when ground truth or images are missing (CI mode)
6. **Local tests pass**: All 76 images pass per-filesystem verification (full corpus mode)

---

## 10. Timeline Estimate

| Phase | Duration | Cumulative |
|-------|----------|------------|
| Update Scanner | 1-2 days | 2 days |
| Update Java Data Classes | 1 day | 3 days |
| Create New Test Class | 2-3 days | 6 days |
| Regenerate Ground Truth | 3-5 days | 11 days |
| Update/Deprecate Old Tests | 1-2 days | 13 days |
| **Total** | **~2 weeks** | |

Note: Regenerating ground truth is the longest phase due to I/O bound scanning of 76 VM images (~150GB).
