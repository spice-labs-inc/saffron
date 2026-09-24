# VHDX test resources

| File | Origin | Purpose |
|---|---|---|
| `magic-only.vhdx` | `scripts/generate-test-resources.sh` | 512-byte file identifier only; format detection |
| `fixtures/chunk-ratio-512.vhdx` | `generate-fixtures.sh` (qemu-img 7.2, Debian bookworm, in Docker) | Dynamic, 512-byte logical sectors, 1 MiB blocks, 5 GiB virtual. Block at 1 MiB filled with `0xCD`, block at 4608 MiB filled with `0xAB`. The second block sits past the first 4 GiB BAT chunk, so reading it requires honouring the sector-bitmap entry the spec interleaves after every `chunkRatio` payload entries. |

No third-party content is embedded. qemu is GPL-2.0; the generated image contains only the bytes written by the script.

Regenerate with:

```
src/test/resources/vhdx/generate-fixtures.sh
```

Tests: `VhdxChunkRatioTest`.
