# Disk Image Formats — Hardening Contract (LLM Summary)

## Contract (post phase-1)

- Truncated file: reads of ALLOCATED regions past EOF → checked
  `IOException` (never hang, never silent zeros). Reads below the
  truncation point stay byte-exact. Sparse/unallocated regions → zeros
  without touching the file.
- All on-disk size fields validated BEFORE allocation; violations →
  `IOException` at open. Table reads capped at 16 MiB (qcow2 L1, VHD BAT,
  VHDX BAT, VMDK GD 4 MiB).
- Validated ranges: VHD currentSize ≤2TiB, blockSize pow2 512B..8MiB,
  entries ≤4M; VDI blockSize pow2 ≤64MiB, blocksInHdd ≤4M; VHDX
  virtualSize ≤64TiB, blockSize pow2 1MiB..256MiB, region entries ≤2047;
  VMDK capacity ≤2TiB no overflow, grainSize pow2 1..4096 sectors, GD
  ≤1M; GPT entrySize ≤4096, entries within disk.
- Differencing VHD/VDI/VHDX/VMDK rejected at open with IOException
  (previously silent zeros for unallocated blocks); detection unchanged.
- `DiskRegion.read` converts unchecked bounds errors to `IOException`
  (checked boundary for filesystem drivers).
- AMI: missing parts reject at open; skipFully/readFully semantics; part
  index (binary search); 10k part cap; manifest number errors →
  `InvalidDiskException` (module's documented error type — an intentional
  scoped decision, not the plan's literal IOException).

## Files changed

- `raw/RawDiskImpl.java`, `qcow2/cluster/ClusterReader.java`,
  `vhd/VhdDiskImpl.java`, `vhdx/VhdxDiskImpl.java`,
  `vhdx/metadata/VhdxMetadata.java`, `vdi/VdiDiskImpl.java`,
  `vmdk/VmdkDiskImpl.java`, `ami/AmiDiskImpl.java`,
  `partition/GptPartitionTable.java`, `lvm/DiskRegion.java`.
- `pom.xml`: second surefire execution `hardening` (single fork, no
  reuse, serial) for `**/diskhardening/*Test.java`.

## Test mapping

| Claim | Test |
|---|---|
| Truncation → IOException, sparse seam, no hang | `DiskTruncationTest` (9 methods), `DiskTruncationSweepTest` |
| Hostile headers rejected (per field) | `DiskValidationTest` (18 methods) + `acceptedRangeBoundariesStillOpen` |
| Differencing rejected, detect unchanged, name-not-type | `DifferencingDiskRejectionTest` (5 methods) |
| AMI loud failures + full reads | `AmiDiskHardeningTest`, `AmiReadFullyTest` |
| Checked boundary at DiskRegion + quiet detectors | `CheckedBoundaryTest` (5 methods) |
| Deterministic cross-L2-read race closed | `ChannelRaceTest.concurrentReadsOfDifferentL2TablesNeverCrossRead` |
| Concurrent reads byte-exact across all formats | `DiskConcurrencyTest.concurrentRandomReadsMatchReferenceAcrossFormats` |
| L2 cache replacement under concurrency correct | `DiskConcurrencyTest.l2CacheReplacementUnderConcurrencyIsCorrect` |
| Stream contract: one stream per thread, len==0 returns 0 | `DiskConcurrencyTest.concurrentStreamsOnePerThreadReadCorrectly` |

## Thread safety (phase 2)

- Channel position+read is atomic under `synchronized(channel)` in ALL
  disk impls: raw/vdi/vmdk were already synchronized; qcow2
  (readFromChannel, loadL1Table, loadL2Table, readCompressedCluster,
  snapshot table), vhd (readFromChannel), vhdx (readFromChannel, BAT,
  region table, metadata via VhdxMetadata.readFully) are now.
- qcow2 L2 cache: check + read + update all inside the channel monitor
  (no torn index/table pairing; no volatile dance).
- Backing-disk reads (`backingDisk.read`) always occur OUTSIDE the
  channel lock (no deadlock on circular backing chains).
- `openStream()` streams are single-threaded per instance;
  `read(b, off, 0)` returns 0 (InputStream contract) in VHD/VHDX/VDI/
  VMDK streams — this was a latent bug the concurrency suite exposed
  (JDK `readNBytes` calls read with len==0 at exact buffer fills).
- Build config fix (found during this phase): surefire's plugin-level
  `<excludes>` was inherited by the `hardening` execution, silently
  skipping ALL diskhardening tests in full builds. The excludes now live
  in the `default-test` execution only; the full build runs both
  executions (1724 default + 48 hardening = 1772).

All new tests were red pre-fix (recorded: qcow2/VHD/VHDX truncation tests
timed out at 30 s on the infinite loops; VDI/VMDK/raw failed on silent
zero data; validation/differencing/AMI/boundary classes failed on the
unchecked/pre-silent behaviors).
