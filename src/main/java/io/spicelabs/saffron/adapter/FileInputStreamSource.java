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
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.adapter;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * An {@link InputStreamSource} backed by a file on the filesystem.
 *
 * <p>This implementation supports efficient random access via
 * {@link FileChannel#position(long)}.
 *
 * <p>Example usage:
 * <pre>{@code
 * Path path = Paths.get("/path/to/disk.qcow2");
 * try (InputStreamSource source = new FileInputStreamSource(path)) {
 *     try (InputStream is = source.openStream(1024)) {
 *         // Read from offset 1024
 *     }
 * }
 * }</pre>
 */
public final class FileInputStreamSource implements InputStreamSource {

    private final Path path;

    /**
     * Creates a new file-backed input stream source.
     *
     * @param path the path to the file
     * @throws IllegalArgumentException if path is null
     */
    public FileInputStreamSource(@NotNull Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }
        this.path = path;
    }

    @Override
    public @NotNull InputStream openStream() throws IOException {
        return Files.newInputStream(path, StandardOpenOption.READ);
    }

    @Override
    public @NotNull InputStream openStream(long offset) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset cannot be negative: " + offset);
        }
        if (offset == 0) {
            return openStream();
        }

        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            channel.position(offset);
            return new ChannelInputStream(channel);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public long size() throws IOException {
        return Files.size(path);
    }

    @Override
    public @NotNull String getDescription() {
        return path.toString();
    }

    @Override
    public boolean supportsRandomAccess() {
        return true;
    }

    /**
     * Returns the path to the underlying file.
     *
     * @return the file path
     */
    public @NotNull Path getPath() {
        return path;
    }

    /**
     * An InputStream that wraps a FileChannel and closes it when the stream is closed.
     */
    private static class ChannelInputStream extends InputStream {
        private final FileChannel channel;
        private final InputStream delegate;

        ChannelInputStream(FileChannel channel) {
            this.channel = channel;
            this.delegate = Channels.newInputStream(channel);
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            return delegate.read(b);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public long skip(long n) throws IOException {
            if (n <= 0) {
                return 0;
            }
            long currentPos = channel.position();
            long newPos = Math.min(currentPos + n, channel.size());
            channel.position(newPos);
            return newPos - currentPos;
        }

        @Override
        public int available() throws IOException {
            long remaining = channel.size() - channel.position();
            return (int) Math.min(remaining, Integer.MAX_VALUE);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
