package io.spicelabs.saffron.debug;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class DebugWindowsCount {
    public static void main(String[] args) throws Exception {
        String[] images = {
            "test-corpus/vhd/Windows 95 Hard Disk.vhd",
            "test-corpus/vhd/Windows 98 Plus! Hard Disk.vhd", 
            "test-corpus/vmdk/windows-me.vmdk"
        };
        
        long[] expectedFiles = {853, 4733, 4974};
        
        for (int i = 0; i < images.length; i++) {
            String image = images[i];
            Path p = Path.of(image);
            
            if (!java.nio.file.Files.exists(p)) {
                System.out.println("SKIP: " + image);
                continue;
            }
            
            System.out.println("\n=== " + image + " ===");
            System.out.println("Expected files: " + expectedFiles[i]);
            
            try (VirtualDisk disk = DiskReader.open(p)) {
                FileSystemMount.MountAllResult mountResult = FileSystemMount.mountAllWithDetected(disk);
                var allFs = mountResult.mounted();
                
                AtomicLong actualFiles = new AtomicLong(0);
                AtomicLong actualDirs = new AtomicLong(0);
                
                for (FileSystem fs : allFs) {
                    try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                        walkStream.forEach(entry -> {
                            if (entry instanceof FileSystemEntry.RegularFile) {
                                actualFiles.incrementAndGet();
                                String name = entry.name();
                                // Print suspicious files
                                if (name.startsWith(".") || name.contains("$") || 
                                    name.toLowerCase().contains("recycled") ||
                                    name.toLowerCase().contains("system")) {
                                    System.out.println("  File: " + entry.path() + " (size=" + entry.size() + ")");
                                }
                            } else if (entry instanceof FileSystemEntry.Directory) {
                                actualDirs.incrementAndGet();
                            }
                        });
                    }
                }
                
                System.out.println("Actual files: " + actualFiles.get());
                System.out.println("Actual dirs: " + actualDirs.get());
                System.out.println("Difference: " + (actualFiles.get() - expectedFiles[i]));
                
                for (FileSystem fs : allFs) {
                    fs.close();
                }
            }
        }
    }
}
