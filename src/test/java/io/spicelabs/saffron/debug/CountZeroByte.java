package io.spicelabs.saffron.debug;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class CountZeroByte {
    public static void main(String[] args) throws Exception {
        String[] images = {
            "test-corpus/vhd/legacy/xp-mode/Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/Windows 95 Hard Disk.vhd",
            "test-corpus/vhd/legacy/xp-mode/Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/Windows 98 Plus! Hard Disk.vhd",
            "test-corpus/vmdk/legacy/windows-me.vmdk"
        };
        
        String[] names = {"Win95", "Win98", "WinME"};
        long[] expectedFiles = {853, 4733, 4974};
        
        for (int i = 0; i < images.length; i++) {
            String image = images[i];
            String name = names[i];
            Path p = Path.of(image);
            
            if (!java.nio.file.Files.exists(p)) {
                System.out.println("SKIP: " + name);
                continue;
            }
            
            System.out.println("\n=== " + name + " ===");
            
            try (VirtualDisk disk = DiskReader.open(p)) {
                FileSystemMount.MountAllResult mountResult = FileSystemMount.mountAllWithDetected(disk);
                var allFs = mountResult.mounted();
                
                AtomicLong totalFiles = new AtomicLong(0);
                AtomicLong zeroByteFiles = new AtomicLong(0);
                AtomicLong nonZeroFiles = new AtomicLong(0);
                
                for (FileSystem fs : allFs) {
                    try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                        walkStream.forEach(entry -> {
                            if (entry instanceof FileSystemEntry.RegularFile) {
                                totalFiles.incrementAndGet();
                                if (entry.size() == 0) {
                                    zeroByteFiles.incrementAndGet();
                                } else {
                                    nonZeroFiles.incrementAndGet();
                                }
                            }
                        });
                    }
                }
                
                System.out.println("Total files: " + totalFiles.get());
                System.out.println("Zero-byte files: " + zeroByteFiles.get());
                System.out.println("Non-zero files: " + nonZeroFiles.get());
                System.out.println("Expected: " + expectedFiles[i]);
                System.out.println("Non-zero matches expected: " + (nonZeroFiles.get() == expectedFiles[i]));
                
                for (FileSystem fs : allFs) {
                    fs.close();
                }
            }
        }
    }
}
