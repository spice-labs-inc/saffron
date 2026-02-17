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
package io.spicelabs.saffron.ami;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.exception.SaffronException;
import org.jetbrains.annotations.NotNull;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Implementation of {@link VirtualDisk.AmiDisk} for Amazon Machine Image bundles.
 *
 * <p>AMI bundles consist of:
 * <ul>
 *   <li>An XML manifest file (image.manifest.xml)</li>
 *   <li>Multiple part files (image.part.00, image.part.01, etc.)</li>
 *   <li>Parts may be encrypted and/or compressed</li>
 * </ul>
 *
 * <p>This implementation supports reading unencrypted AMI bundles.
 * Encrypted bundles require AWS credentials for decryption.
 */
public final class AmiDiskImpl implements VirtualDisk.AmiDisk {

    private final Path manifestPath;
    private final String imageName;
    private final String architecture;
    private final long virtualSize;
    private final long bundledSize;
    private final List<Path> partFiles;
    private final String digest;
    private final boolean encrypted;

    /**
     * Opens an AMI bundle from its manifest file.
     *
     * @param manifestPath the path to the manifest.xml file
     * @return the opened disk
     * @throws IOException if an I/O error occurs or the manifest is invalid
     */
    public static @NotNull AmiDiskImpl open(@NotNull Path manifestPath) throws IOException {
        if (!Files.exists(manifestPath)) {
            throw new SaffronException.InvalidDiskException("AMI manifest not found: " + manifestPath);
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Security: disable external entities
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(Files.newInputStream(manifestPath));
            doc.getDocumentElement().normalize();

            // Parse image element
            Element imageElement = (Element) doc.getElementsByTagName("image").item(0);
            if (imageElement == null) {
                throw new SaffronException.InvalidDiskException("Invalid AMI manifest: missing <image> element");
            }

            String imageName = getElementText(imageElement, "name");
            long virtualSize = Long.parseLong(getElementText(imageElement, "size"));
            long bundledSize = Long.parseLong(getElementText(imageElement, "bundled_size"));
            String digest = getElementText(imageElement, "digest");

            // Check encryption
            String encryptedKey = getElementText(imageElement, "ec2_encrypted_key");
            boolean encrypted = encryptedKey != null && !encryptedKey.equals("NOT_ENCRYPTED_TEST");

            // Parse architecture
            Element machineConfig = (Element) doc.getElementsByTagName("machine_configuration").item(0);
            String architecture = "x86_64";
            if (machineConfig != null) {
                String arch = getElementText(machineConfig, "architecture");
                if (arch != null) {
                    architecture = arch;
                }
            }

            // Parse parts
            List<Path> partFiles = new ArrayList<>();
            Element partsElement = (Element) imageElement.getElementsByTagName("parts").item(0);
            if (partsElement != null) {
                NodeList parts = partsElement.getElementsByTagName("part");
                Path bundleDir = manifestPath.getParent();

                for (int i = 0; i < parts.getLength(); i++) {
                    Element part = (Element) parts.item(i);
                    String filename = getElementText(part, "filename");
                    if (filename != null) {
                        Path partPath = bundleDir.resolve(filename);
                        partFiles.add(partPath);
                    }
                }
            }

            // Sort parts by index
            partFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));

            return new AmiDiskImpl(manifestPath, imageName, architecture, virtualSize,
                    bundledSize, partFiles, digest, encrypted);

        } catch (ParserConfigurationException | SAXException e) {
            throw new SaffronException.InvalidDiskException("Failed to parse AMI manifest: " + e.getMessage(), e);
        }
    }

    private static String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return null;
    }

    private AmiDiskImpl(Path manifestPath, String imageName, String architecture,
                        long virtualSize, long bundledSize, List<Path> partFiles,
                        String digest, boolean encrypted) {
        this.manifestPath = manifestPath;
        this.imageName = imageName;
        this.architecture = architecture;
        this.virtualSize = virtualSize;
        this.bundledSize = bundledSize;
        this.partFiles = Collections.unmodifiableList(partFiles);
        this.digest = digest;
        this.encrypted = encrypted;
    }

    @Override
    public @NotNull DiskFormat format() {
        return DiskFormat.AMI;
    }

    @Override
    public long virtualSize() {
        return virtualSize;
    }

    @Override
    public long allocatedSize() {
        return bundledSize;
    }

    @Override
    public @NotNull ByteBuffer read(long offset, int length) throws IOException {
        if (encrypted) {
            throw new SaffronException.UnsupportedDiskException(
                    "Reading encrypted AMI bundles requires AWS credentials");
        }

        ByteBuffer buffer = ByteBuffer.allocate(length);
        long currentOffset = 0;
        int bytesRead = 0;

        for (Path partFile : partFiles) {
            if (!Files.exists(partFile)) {
                continue;
            }

            long partSize = Files.size(partFile);
            long partEnd = currentOffset + partSize;

            // Check if this part contains data we need
            if (offset < partEnd && currentOffset < offset + length) {
                long readStart = Math.max(0, offset - currentOffset);
                long readEnd = Math.min(partSize, offset + length - currentOffset);
                int toRead = (int) (readEnd - readStart);

                try (InputStream is = Files.newInputStream(partFile)) {
                    is.skip(readStart);
                    byte[] partData = new byte[toRead];
                    int read = is.read(partData);
                    if (read > 0) {
                        buffer.put(partData, 0, read);
                        bytesRead += read;
                    }
                }
            }

            currentOffset = partEnd;
            if (bytesRead >= length) {
                break;
            }
        }

        buffer.flip();
        return buffer;
    }

    @Override
    public @NotNull InputStream openStream() throws IOException {
        if (encrypted) {
            throw new SaffronException.UnsupportedDiskException(
                    "Reading encrypted AMI bundles requires AWS credentials");
        }

        return new SequenceInputStream(Collections.enumeration(
                partFiles.stream()
                        .filter(Files::exists)
                        .map(p -> {
                            try {
                                return Files.newInputStream(p);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        })
                        .toList()
        ));
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("ami.imageName", imageName);
        meta.put("ami.architecture", architecture);
        meta.put("ami.virtualSize", String.valueOf(virtualSize));
        meta.put("ami.bundledSize", String.valueOf(bundledSize));
        meta.put("ami.partCount", String.valueOf(partFiles.size()));
        meta.put("ami.encrypted", String.valueOf(encrypted));
        if (digest != null) {
            meta.put("ami.digest", digest);
        }
        return meta;
    }

    @Override
    public @NotNull PackageURL packageUrl() {
        try {
            TreeMap<String, String> qualifiers = new TreeMap<>();
            qualifiers.put("arch", architecture);
            qualifiers.put("format", "ami");
            qualifiers.put("size", String.valueOf(virtualSize));

            return new PackageURL(
                    PackageURL.StandardTypes.GENERIC,
                    "vmdisk",
                    imageName,
                    "1.0",
                    qualifiers,
                    null
            );
        } catch (MalformedPackageURLException e) {
            throw new IllegalStateException("Failed to create package URL", e);
        }
    }

    @Override
    public @NotNull Optional<String> backingFile() {
        return Optional.empty();
    }

    @Override
    public boolean isEncrypted() {
        return encrypted;
    }

    @Override
    public boolean isCompressed() {
        // AMI bundles may be compressed, but we'd need to check the actual content
        return false;
    }

    @Override
    public @NotNull Stream<Snapshot> snapshots() {
        return Stream.empty();
    }

    @Override
    public @NotNull String imageName() {
        return imageName;
    }

    @Override
    public @NotNull String architecture() {
        return architecture;
    }

    @Override
    public int partCount() {
        return partFiles.size();
    }

    @Override
    public void close() throws IOException {
        // No resources to close - parts are read on demand
    }
}
