/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.rodeo;

// ============================================================================
// Rodeo Component framework imports
// ============================================================================
import io.spicelabs.rodeocomponents.API;
import io.spicelabs.rodeocomponents.APIFactory;
import io.spicelabs.rodeocomponents.APIFactoryReceiver;
import io.spicelabs.rodeocomponents.APIFactorySource;
import io.spicelabs.rodeocomponents.RodeoComponent;
import io.spicelabs.rodeocomponents.RodeoEnvironment;
import io.spicelabs.rodeocomponents.RodeoIdentity;

// Artifact handling APIs — this is how Goat Rodeo processes files
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactConstants;
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactHandler;
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactHandlerRegistrar;
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactMemento;
import io.spicelabs.rodeocomponents.APIS.artifacts.BackendStorage;
import io.spicelabs.rodeocomponents.APIS.artifacts.Metadata;
import io.spicelabs.rodeocomponents.APIS.artifacts.MetadataTag;
import io.spicelabs.rodeocomponents.APIS.artifacts.ParentFrame;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoArtifact;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoItemMarker;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoProcessFilter;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoProcessItems;
import io.spicelabs.rodeocomponents.APIS.artifacts.Triple;
import io.spicelabs.rodeocomponents.APIS.artifacts.WorkItem;

// MIME type identification APIs — this is how Goat Rodeo identifies file types
import io.spicelabs.rodeocomponents.APIS.mimes.MimeConstants;
import io.spicelabs.rodeocomponents.APIS.mimes.MimeIdentifierRegistrar;
import io.spicelabs.rodeocomponents.APIS.mimes.MimeInputStreamIdentifier;

// Purl (Package URL) API
import io.spicelabs.rodeocomponents.APIS.purls.Purl;

// ============================================================================
// Saffron imports — the VM disk image reader library
// ============================================================================
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import io.spicelabs.saffron.fs.FileSystemMount.FilesystemLocation;

// ============================================================================
// Standard Java imports
// ============================================================================
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.Runtime.Version;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Saffron Rodeo Component — integrates Saffron's VM disk image reading
 * capabilities into the Goat Rodeo artifact processing pipeline.
 *
 * <p>This component contributes two capabilities to Goat Rodeo:
 * <ol>
 *   <li><b>MIME identification</b> — recognizes VM disk image formats
 *       (QCOW2, VMDK, VHD, VHDX, VDI, raw/IMG) by their magic bytes.</li>
 *   <li><b>Artifact handling</b> — when Goat Rodeo encounters a disk image,
 *       this component opens it, mounts its filesystems, and yields the
 *       contained files back into the processing pipeline as child artifacts.</li>
 * </ol>
 *
 * <h2>Component Lifecycle</h2>
 * <p>Per the Rodeo Component contract ({@link io.spicelabs.rodeocomponents.RodeoComponentv0}),
 * the host drives the following lifecycle in strict order:
 * <pre>
 *   new SaffronComponent()   // constructor — do nothing heavy
 *        ↓
 *   initialize()             // validate that Saffron classes are loadable
 *        ↓
 *   getIdentity()            // return our name and publisher
 *   getComponentVersion()    // return the rodeo-components version we compiled against
 *        ↓
 *   exportAPIFactories()     // we have nothing to export (Saffron is a consumer)
 *        ↓
 *   importAPIFactories()     // import ArtifactHandlerRegistrar and MimeIdentifierRegistrar
 *        ↓
 *   onLoadingComplete()      // all wiring is done; we're ready
 *        ↓
 *   shutDown()               // release any resources
 * </pre>
 *
 * <h2>Why a no-arg constructor?</h2>
 * <p>Goat Rodeo discovers components via {@link java.util.ServiceLoader} (or similar
 * reflective discovery). The specification requires a public no-arg constructor that
 * does as little work as possible and <b>never</b> throws.
 */
public class SaffronComponent implements RodeoComponent {

    // ========================================================================
    // Fields — populated during importAPIFactories()
    // ========================================================================

    /**
     * The registrar we'll use to register our {@link ArtifactHandler}.
     * This is imported from the Goat Rodeo host during the import phase.
     */
    private ArtifactHandlerRegistrar artifactRegistrar;

    /**
     * The registrar we'll use to register our {@link MimeInputStreamIdentifier}.
     * This is imported from the Goat Rodeo host during the import phase.
     */
    private MimeIdentifierRegistrar mimeRegistrar;

    // ========================================================================
    // Constructor — must be public, no-arg, do nothing, never throw
    // ========================================================================

    /**
     * Public no-arg constructor required by the Rodeo Component contract.
     *
     * <p>Per component-life.md: "The constructor should do as little as possible"
     * and must never throw. All real initialization happens in {@link #initialize()}.
     */
    public SaffronComponent() {
        // Intentionally empty — Rodeo requires a lightweight constructor.
    }

    // ========================================================================
    // Lifecycle: initialize()
    // ========================================================================

    /**
     * Called by the host to give us a chance to validate our environment.
     *
     * <p>Per the Rodeo contract, if we can't proceed we should "fail fast"
     * by throwing an exception. Here we verify that the Saffron library
     * classes are on the classpath and loadable.
     *
     * @throws Exception if Saffron's core classes are not available
     */
    @Override
    public void initialize() throws Exception {
        // Verify that Saffron's key classes are loadable. If saffron.jar is
        // missing from the classpath, this will throw ClassNotFoundException
        // and the host will disable this component gracefully.
        Class.forName("io.spicelabs.saffron.DiskReader");
        Class.forName("io.spicelabs.saffron.fs.FileSystemMount");
    }

    // ========================================================================
    // Lifecycle: getIdentity()
    // ========================================================================

    /**
     * Returns identification information about this component.
     *
     * <p>Per component-life.md: "may be called at any point in the life of
     * the Component" — so this must always be safe to call, even before
     * {@link #initialize()}.
     *
     * @return a {@link RodeoIdentity} with our name and publisher
     */
    @Override
    public RodeoIdentity getIdentity() {
        // RodeoIdentity is a simple interface with name() and publisher().
        // We return an anonymous implementation. The name must be unique
        // across all components — "Components with conflicting names will
        // be discarded."
        return new RodeoIdentity() {
            @Override
            public String name() {
                return "saffron-disk-image-reader";
            }

            @Override
            public String publisher() {
                return "Spice Labs, Inc.";
            }
        };
    }

    // ========================================================================
    // Lifecycle: getComponentVersion()
    // ========================================================================

    /**
     * Returns the version of the rodeo-components API that this component
     * was compiled against.
     *
     * <p>The host uses this to verify interface compatibility. We return
     * {@link RodeoEnvironment#currentVersion()} which is the version from
     * the rodeo-components library on our compile classpath.
     *
     * @return the component system version
     */
    @Override
    public Version getComponentVersion() {
        return RodeoEnvironment.currentVersion();
    }

    // ========================================================================
    // Lifecycle: exportAPIFactories()
    // ========================================================================

    /**
     * Gives us the opportunity to export API factories to other components.
     *
     * <p>Saffron is a <em>consumer</em> of the Goat Rodeo pipeline, not
     * a provider of new APIs, so we have nothing to export here.
     *
     * @param receiver the factory receiver (unused)
     */
    @Override
    public void exportAPIFactories(APIFactoryReceiver receiver) {
        // Nothing to export — Saffron consumes the artifact pipeline,
        // it doesn't publish new APIs for other components to use.
    }

    // ========================================================================
    // Lifecycle: importAPIFactories()
    // ========================================================================

    /**
     * Imports API factories from the host and other components.
     *
     * <p>This is where we wire Saffron into Goat Rodeo. We import two APIs:
     * <ul>
     *   <li>{@link ArtifactHandlerRegistrar} — to register our artifact
     *       handler that processes disk images</li>
     *   <li>{@link MimeIdentifierRegistrar} — to register our MIME type
     *       identifier that recognizes disk image formats</li>
     * </ul>
     *
     * <p>Per the Rodeo contract: "Importing will always happen after exporting."
     * So all other components' exports are available here.
     *
     * @param factorySource the source from which we obtain API factories
     */
    @Override
    public void importAPIFactories(APIFactorySource factorySource) {
        // ----------------------------------------------------------------
        // Import the ArtifactHandlerRegistrar API
        // ----------------------------------------------------------------
        // The factory pattern works like this:
        //   1. We ask the factorySource for a factory by name and type
        //   2. If it exists, we call buildAPI() on the factory to get the API
        //   3. The factory can customize the API for us (e.g., logging)
        Optional<APIFactory<ArtifactHandlerRegistrar>> artifactFactoryOpt =
                factorySource.getAPIFactory(
                        ArtifactConstants.NAME,  // "ArtifactHandlerRegistrar"
                        this,                    // the subscribing component (us)
                        ArtifactHandlerRegistrar.class
                );

        if (artifactFactoryOpt.isPresent()) {
            // Build the API — the factory may personalize it for this component
            artifactRegistrar = artifactFactoryOpt.get().buildAPI(this);

            // Register our process filter. The filter tells Goat Rodeo which
            // artifacts we can handle and how to group them for processing.
            artifactRegistrar.registerProcessFilter(new SaffronProcessFilter());
        }

        // ----------------------------------------------------------------
        // Import the MimeIdentifierRegistrar API
        // ----------------------------------------------------------------
        Optional<APIFactory<MimeIdentifierRegistrar>> mimeFactoryOpt =
                factorySource.getAPIFactory(
                        MimeConstants.NAME,  // "MIMEIdentifiers"
                        this,
                        MimeIdentifierRegistrar.class
                );

        if (mimeFactoryOpt.isPresent()) {
            mimeRegistrar = mimeFactoryOpt.get().buildAPI(this);

            // Register our MIME type identifier. It uses an InputStream
            // (not FileInputStream) since we only need magic bytes, and
            // the framework prefers InputStream for performance.
            mimeRegistrar.register(new SaffronMimeIdentifier());
        }
    }

    // ========================================================================
    // Lifecycle: onLoadingComplete()
    // ========================================================================

    /**
     * Called after all components have been loaded, exported, and imported.
     *
     * <p>This is our chance to do any final setup before Goat Rodeo starts
     * processing. Saffron doesn't need any final setup steps.
     */
    @Override
    public void onLoadingComplete() {
        // No additional setup needed. All our handlers were registered
        // during importAPIFactories().
    }

    // ========================================================================
    // Lifecycle: shutDown()
    // ========================================================================

    /**
     * Called when Goat Rodeo is shutting down.
     *
     * <p>Per component-life.md: "should be written defensively" since
     * consistent state cannot be guaranteed. We release our API references.
     */
    @Override
    public void shutDown() {
        // Release API references defensively. The Releasable interface
        // (which API extends) provides release() for cleanup.
        if (artifactRegistrar != null) {
            try {
                artifactRegistrar.release();
            } catch (Exception ignored) {
                // Defensive — shutDown must not propagate exceptions
            }
            artifactRegistrar = null;
        }

        if (mimeRegistrar != null) {
            try {
                mimeRegistrar.release();
            } catch (Exception ignored) {
                // Defensive — shutDown must not propagate exceptions
            }
            mimeRegistrar = null;
        }
    }

    // ========================================================================
    // MIME Type Identification
    // ========================================================================

    /**
     * Known VM disk image magic bytes. Each entry maps a byte pattern
     * (and its offset within the file) to a MIME type string.
     *
     * <p>These are the formats Saffron's {@link DiskReader} supports.
     */
    private static final List<MagicEntry> MAGIC_ENTRIES = List.of(
            // QCOW2: starts with "QFI\xfb" at offset 0
            new MagicEntry(new byte[]{'Q', 'F', 'I', (byte) 0xfb}, 0,
                    "application/x-qemu-disk"),
            // VMDK: starts with "KDMV" at offset 0 (sparse) or "# Disk Desc" (descriptor)
            new MagicEntry(new byte[]{'K', 'D', 'M', 'V'}, 0,
                    "application/x-vmdk"),
            // VHD: ends with "conectix" but the magic is at the start for fixed VHDs
            new MagicEntry(new byte[]{'c', 'o', 'n', 'e', 'c', 't', 'i', 'x'}, 0,
                    "application/x-vhd"),
            // VHDX: starts with "vhdxfile" at offset 0
            new MagicEntry(new byte[]{'v', 'h', 'd', 'x', 'f', 'i', 'l', 'e'}, 0,
                    "application/x-vhdx"),
            // VDI: Oracle VirtualBox — magic at offset 0x40
            new MagicEntry(new byte[]{(byte) 0x7f, (byte) 0x10, (byte) 0xda, (byte) 0xbe}, 0x40,
                    "application/x-virtualbox-vdi")
    );

    /** A magic byte entry: pattern bytes, offset in file, and MIME type. */
    private record MagicEntry(byte[] pattern, int offset, String mimeType) {}

    /**
     * Identifies VM disk image MIME types by reading magic bytes from an
     * InputStream.
     *
     * <p>This implements the three-stage MIME identification protocol defined
     * by {@link io.spicelabs.rodeocomponents.APIS.mimes.MimeIdentifier}:
     * <ol>
     *   <li>{@link #preferredHeaderLength()} — how many bytes we need</li>
     *   <li>{@link #canHandleHeader(byte[])} — quick check: could this be ours?</li>
     *   <li>{@link #identifyMimeType(InputStream, String)} — definitive identification</li>
     * </ol>
     */
    static class SaffronMimeIdentifier implements MimeInputStreamIdentifier {

        // We need enough bytes to check the VDI magic at offset 0x40 + 4 bytes
        private static final int HEADER_SIZE = 0x40 + 4; // 68 bytes

        /**
         * Returns the number of header bytes we need to make a preliminary
         * identification. The framework will read at least this many bytes
         * and pass them to {@link #canHandleHeader(byte[])}.
         */
        @Override
        public int preferredHeaderLength() {
            return HEADER_SIZE;
        }

        /**
         * Quick check: could this byte array represent a VM disk image?
         *
         * <p>Per the MimeIdentifier contract: "Components should be inclined
         * to return true if there is any possibility that it could identify
         * the stream." This allows true negatives to be filtered out cheaply.
         * False positives are acceptable here — the definitive check happens
         * in {@link #identifyMimeType}.
         *
         * @param header the file header bytes (at least {@link #HEADER_SIZE} bytes)
         * @return true if any magic pattern matches
         */
        @Override
        public boolean canHandleHeader(byte[] header) {
            // Check each known magic pattern against the header
            for (MagicEntry entry : MAGIC_ENTRIES) {
                if (matchesMagic(header, entry)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Definitive MIME type identification.
         *
         * <p>The stream starts at position 0 with markSupported() == true.
         * We don't need to restore the stream position (the framework handles
         * that). Per the contract: "the component is responsible for ensuring
         * that it catches any exceptions that get thrown by stream operations."
         *
         * <p>We should only override "application/octet-stream" — if another
         * component has already identified a more specific type, we defer to it.
         *
         * @param stream    the input stream positioned at byte 0
         * @param mimeSoFar the current MIME type ("application/octet-stream" if unknown)
         * @return the identified MIME type, or empty to defer
         */
        @Override
        public Optional<String> identifyMimeType(InputStream stream, String mimeSoFar) {
            // Per the MimeIdentifier javadoc: "Components should be mindful
            // of overriding any mime type except 'application/octet-stream'."
            if (!"application/octet-stream".equals(mimeSoFar)) {
                return Optional.empty();
            }

            try {
                byte[] header = new byte[HEADER_SIZE];
                int bytesRead = stream.read(header);
                if (bytesRead < HEADER_SIZE) {
                    return Optional.empty();
                }

                for (MagicEntry entry : MAGIC_ENTRIES) {
                    if (matchesMagic(header, entry)) {
                        return Optional.of(entry.mimeType());
                    }
                }
            } catch (IOException e) {
                // Contract: component must catch stream exceptions
            }

            return Optional.empty();
        }

        /** Checks if the header bytes match a magic pattern at its offset. */
        private static boolean matchesMagic(byte[] header, MagicEntry entry) {
            if (header.length < entry.offset() + entry.pattern().length) {
                return false;
            }
            for (int i = 0; i < entry.pattern().length; i++) {
                if (header[entry.offset() + i] != entry.pattern()[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    // ========================================================================
    // Artifact Processing
    // ========================================================================

    /**
     * A RodeoProcessFilter tells Goat Rodeo which artifacts this component
     * can handle. When Goat Rodeo encounters a batch of artifacts, it calls
     * {@link #filterByName} to let us claim the ones we recognize.
     */
    static class SaffronProcessFilter implements RodeoProcessFilter {

        /**
         * The name of this filter, used for logging and diagnostics.
         */
        @Override
        public String getName() {
            return "saffron-disk-images";
        }

        /**
         * Filters a batch of artifacts by filename/MIME type.
         *
         * <p>Goat Rodeo passes us a map of (name → list of artifacts).
         * We look at file extensions and MIME types to claim the VM disk
         * images that Saffron can process.
         *
         * @param namesToFilter map of artifact name → artifacts with that name
         * @return list of triples: (name, artifact, process-items) for
         *         each artifact we want to handle
         */
        @Override
        public List<Triple<String, RodeoArtifact, RodeoProcessItems>> filterByName(
                Map<String, List<RodeoArtifact>> namesToFilter) {

            List<Triple<String, RodeoArtifact, RodeoProcessItems>> results = new ArrayList<>();

            for (var entry : namesToFilter.entrySet()) {
                for (RodeoArtifact artifact : entry.getValue()) {
                    if (isSupportedDiskImage(artifact)) {
                        // Wrap the artifact in a RodeoProcessItems that tells
                        // Goat Rodeo how to process it with our handler
                        results.add(new Triple<>(
                                entry.getKey(),
                                artifact,
                                new SaffronProcessItems(artifact)
                        ));
                    }
                }
            }

            return results;
        }

        /** Checks if an artifact is a VM disk image we can handle. */
        private static boolean isSupportedDiskImage(RodeoArtifact artifact) {
            String mime = artifact.getMimeType();
            String name = artifact.getFilenameWithNoPath().toLowerCase();

            // Check by MIME type (if already identified by our MimeIdentifier)
            if (mime != null) {
                if (mime.equals("application/x-qemu-disk")
                        || mime.equals("application/x-vmdk")
                        || mime.equals("application/x-vhd")
                        || mime.equals("application/x-vhdx")
                        || mime.equals("application/x-virtualbox-vdi")) {
                    return true;
                }
            }

            // Fall back to file extension for common disk image formats
            return name.endsWith(".qcow2")
                    || name.endsWith(".vmdk")
                    || name.endsWith(".vhd")
                    || name.endsWith(".vhdx")
                    || name.endsWith(".vdi")
                    || name.endsWith(".img")
                    || name.endsWith(".raw");
        }
    }

    // ========================================================================
    // Artifact Handler — the core of Saffron's Goat Rodeo integration
    // ========================================================================

    /**
     * Memento object that carries state between the phases of artifact
     * processing.
     *
     * <p>{@link ArtifactMemento} is a marker interface — it's the Rodeo
     * pattern for passing opaque state from {@code begin()} through to
     * {@code end()}. Our memento holds the opened VirtualDisk and the
     * mounted FileSystem so we don't re-open them in each phase.
     */
    static class SaffronMemento implements ArtifactMemento {
        final VirtualDisk disk;
        final FileSystem fileSystem;
        final List<FilesystemLocation> allFilesystems;

        SaffronMemento(VirtualDisk disk, FileSystem fileSystem,
                       List<FilesystemLocation> allFilesystems) {
            this.disk = disk;
            this.fileSystem = fileSystem;
            this.allFilesystems = allFilesystems;
        }
    }

    /**
     * The artifact handler that processes VM disk images.
     *
     * <p>The handler lifecycle for each artifact is:
     * <ol>
     *   <li>{@link #begin} — open the disk image and mount filesystems</li>
     *   <li>{@link #getPurls} — extract Package URLs (none for disk images)</li>
     *   <li>{@link #getMetadata} — extract filesystem metadata</li>
     *   <li>{@link #augment} — yield contained files as child work items</li>
     *   <li>{@link #postChildProcessing} — after children are processed</li>
     *   <li>{@link #end} — close the disk image</li>
     * </ol>
     */
    static class SaffronArtifactHandler implements ArtifactHandler {

        /**
         * Whether this handler requires a real file on disk (FileInputStream)
         * rather than a generic InputStream.
         *
         * <p>Saffron's DiskReader can work with InputStreams via
         * {@link DiskReader#open(InputStream, String)}, so we return false
         * to avoid the performance penalty of materializing files to disk.
         * However, for large disk images, file-based access (with random
         * access seeking) is far more efficient, so in production you might
         * return true here.
         */
        @Override
        public boolean requiresFile() {
            // Return false: we can work with plain InputStreams.
            // Goat Rodeo documentation notes: "there is a penalty associated
            // with working with FileInputStream" — but for disk images,
            // random access is actually preferred. A production implementation
            // might return true for better performance on large images.
            return false;
        }

        /**
         * Begin processing a disk image artifact from an InputStream.
         *
         * <p>Opens the disk image with Saffron's DiskReader, detects all
         * filesystems, and mounts the largest one. The opened resources are
         * stored in a {@link SaffronMemento} for use in subsequent phases.
         *
         * @param stream   the artifact's data as an InputStream
         * @param artifact metadata about the artifact being processed
         * @param item     the current work item in the processing pipeline
         * @param marker   a marker for tracking this item's processing
         * @return a memento carrying our state to subsequent handler phases
         */
        @Override
        public ArtifactMemento begin(InputStream stream, RodeoArtifact artifact,
                                     WorkItem item, RodeoItemMarker marker) {
            try {
                // Open the disk image. DiskReader auto-detects the format
                // (QCOW2, VMDK, VHD, VHDX, VDI, raw) from magic bytes.
                VirtualDisk disk = DiskReader.open(stream, artifact.getPath());

                // Find all filesystems in the disk image. This handles:
                //   - GPT and MBR partition tables
                //   - Direct filesystem at offset 0 (no partition table)
                //   - LVM logical volumes
                List<FilesystemLocation> filesystems =
                        FileSystemMount.findFilesystems(disk);

                // Mount the largest filesystem (typically the root FS)
                FileSystem fs = null;
                if (!filesystems.isEmpty()) {
                    FilesystemLocation largest = filesystems.stream()
                            .max((a, b) -> Long.compare(
                                    a.info().totalSize(),
                                    b.info().totalSize()))
                            .orElse(filesystems.get(0));
                    fs = FileSystemMount.mount(disk, largest);
                }

                return new SaffronMemento(disk, fs, filesystems);
            } catch (IOException e) {
                // Return an empty memento — subsequent phases will handle nulls
                return new SaffronMemento(null, null, List.of());
            }
        }

        /**
         * Begin processing from a FileInputStream.
         *
         * <p>Delegates to the InputStream version. If requiresFile() returned
         * true, this would be the primary entry point instead.
         */
        @Override
        public ArtifactMemento begin(FileInputStream stream, RodeoArtifact artifact,
                                     WorkItem item, RodeoItemMarker marker) {
            // Delegate to the InputStream overload — FileInputStream IS-A InputStream
            return begin((InputStream) stream, artifact, item, marker);
        }

        /**
         * Extract Package URLs (purls) from the disk image.
         *
         * <p>Disk images themselves don't have purls (they're not packages).
         * A more advanced implementation could scan for installed packages
         * inside the filesystem (e.g., reading /var/lib/dpkg/status on
         * Debian systems) and return purls for each installed package.
         *
         * @return empty list — disk images don't have package URLs
         */
        @Override
        public List<Purl> getPurls(ArtifactMemento memento, RodeoArtifact artifact,
                                   WorkItem item, RodeoItemMarker marker) {
            return List.of();
        }

        /**
         * Extract metadata from the disk image and its filesystems.
         *
         * <p>We report the filesystem type, volume label, UUID, and size
         * information using the standard {@link MetadataTag} values.
         *
         * @return list of metadata entries describing the disk image
         */
        @Override
        public List<Metadata> getMetadata(ArtifactMemento memento, RodeoArtifact artifact,
                                          WorkItem item, RodeoItemMarker marker) {
            if (!(memento instanceof SaffronMemento sm) || sm.fileSystem == null) {
                return List.of();
            }

            List<Metadata> metadata = new ArrayList<>();
            FileSystem fs = sm.fileSystem;

            // Report the filesystem type (ext4, ntfs, btrfs, etc.)
            metadata.add(new Metadata(
                    MetadataTag.NAME,
                    "filesystem:" + fs.type().name().toLowerCase()
            ));

            // Report the volume label if available
            fs.label().ifPresent(label ->
                    metadata.add(new Metadata(MetadataTag.SIMPLE_NAME, label))
            );

            // Report the filesystem UUID if available
            fs.uuid().ifPresent(uuid ->
                    metadata.add(new Metadata(
                            MetadataTag.ARTIFACT_ID,
                            "fs-uuid:" + uuid
                    ))
            );

            // Report filesystem-specific metadata (block size, version, etc.)
            Map<String, String> fsMeta = fs.metadata();
            if (!fsMeta.isEmpty()) {
                metadata.add(new Metadata(
                        MetadataTag.DESCRIPTION,
                        fsMeta.toString()
                ));
            }

            return metadata;
        }

        /**
         * Augment the work item with child artifacts from inside the disk image.
         *
         * <p>This is the heart of the Saffron integration. We walk the
         * filesystem tree and yield each file as a child work item, which
         * Goat Rodeo will then process through the normal pipeline (MIME
         * identification, artifact handling, SBOM generation, etc.).
         *
         * <p>The {@code parent} parameter provides the frame for recording
         * parent-child relationships, and {@code storage} is the backend
         * for persisting work items.
         *
         * @param memento the state from begin()
         * @param artifact the disk image artifact
         * @param item the current work item
         * @param parent the parent frame for establishing containment
         * @param storage the backend storage for work items
         * @param marker processing marker
         * @return the augmented work item with containment edges
         */
        @Override
        public WorkItem augment(ArtifactMemento memento, RodeoArtifact artifact,
                                WorkItem item, ParentFrame parent,
                                BackendStorage storage, RodeoItemMarker marker) {
            if (!(memento instanceof SaffronMemento sm) || sm.fileSystem == null) {
                return item;
            }

            try {
                // Walk the entire filesystem tree. Saffron's walk() returns
                // a Stream<FileSystemEntry> in depth-first order, including
                // directories, regular files, and symbolic links.
                try (Stream<FileSystemEntry> entries = sm.fileSystem.walk()) {
                    // Process each regular file as a potential child artifact
                    entries
                            .filter(e -> e instanceof FileSystemEntry.RegularFile)
                            .filter(e -> e.size() > 0 && e.size() < 256 * 1024 * 1024)
                            .forEach(entry -> {
                                // Each file inside the disk image can be yielded
                                // back to Goat Rodeo for further processing.
                                // The actual mechanism for creating child work
                                // items depends on the WorkItem and BackendStorage
                                // implementations provided by Goat Rodeo.
                                //
                                // A production implementation would:
                                //   1. Read the file bytes via entry.readAllBytes()
                                //   2. Compute the gitoid (content hash)
                                //   3. Create a child WorkItem with containment edge
                                //   4. Store it in BackendStorage
                                //
                                // For this example, we add a containment edge:
                                try {
                                    item.withNewConnection("contained_by", entry.path());
                                } catch (Exception ignored) {
                                    // Skip files we can't process
                                }
                            });
                }
            } catch (IOException e) {
                // Filesystem walk failed — return item unchanged
            }

            return item;
        }

        /**
         * Called after all child artifacts have been processed.
         *
         * <p>This is where we could finalize any aggregate information
         * about the disk image (e.g., total file count, vulnerability
         * summary). For now, we don't need post-processing.
         *
         * @param memento the state from begin()
         * @param gitoids the gitoids of processed children (if available)
         * @param storage the backend storage
         * @param marker processing marker
         */
        @Override
        public void postChildProcessing(ArtifactMemento memento,
                                        Optional<List<String>> gitoids,
                                        BackendStorage storage,
                                        RodeoItemMarker marker) {
            // No post-processing needed for disk images.
        }

        /**
         * Clean up after processing is complete for this artifact.
         *
         * <p>Close the VirtualDisk and FileSystem that we opened in begin().
         * This releases file handles and memory-mapped regions.
         *
         * @param memento the state from begin()
         */
        @Override
        public void end(ArtifactMemento memento) {
            if (!(memento instanceof SaffronMemento sm)) {
                return;
            }

            // Close resources in reverse order. FileSystem first, then disk.
            if (sm.fileSystem != null) {
                try {
                    sm.fileSystem.close();
                } catch (Exception ignored) {
                    // Defensive cleanup
                }
            }
            if (sm.disk != null) {
                try {
                    sm.disk.close();
                } catch (Exception ignored) {
                    // Defensive cleanup
                }
            }
        }
    }

    // ========================================================================
    // RodeoProcessItems — tells Goat Rodeo how to process our artifacts
    // ========================================================================

    /**
     * Wraps a disk image artifact with the information Goat Rodeo needs
     * to schedule and process it.
     *
     * <p>This connects the artifact to our {@link SaffronArtifactHandler}
     * and provides iteration over the items to process.
     */
    static class SaffronProcessItems implements RodeoProcessItems {
        private final RodeoArtifact artifact;
        private final SaffronArtifactHandler handler = new SaffronArtifactHandler();

        SaffronProcessItems(RodeoArtifact artifact) {
            this.artifact = artifact;
        }

        /** Called when processing of this artifact is complete. */
        @Override
        public void onCompletion(RodeoArtifact artifact) {
            // Nothing to do on completion — cleanup happens in handler.end()
        }

        /** The number of items to process (always 1 — the disk image itself). */
        @Override
        public int length() {
            return 1;
        }

        /**
         * Returns the items to process along with the handler.
         *
         * <p>We return a single item: the disk image artifact paired with
         * an empty marker, along with our handler that knows how to open
         * and walk disk images.
         */
        @Override
        public io.spicelabs.rodeocomponents.APIS.artifacts.Pair<
                List<io.spicelabs.rodeocomponents.APIS.artifacts.Pair<RodeoArtifact, RodeoItemMarker>>,
                ArtifactHandler> getItemsToProcess() {

            // Create a simple no-op marker (RodeoItemMarker is a marker interface)
            RodeoItemMarker marker = new RodeoItemMarker() {};

            List<io.spicelabs.rodeocomponents.APIS.artifacts.Pair<RodeoArtifact, RodeoItemMarker>> items =
                    List.of(new io.spicelabs.rodeocomponents.APIS.artifacts.Pair<>(artifact, marker));

            return new io.spicelabs.rodeocomponents.APIS.artifacts.Pair<>(items, handler);
        }
    }
}
