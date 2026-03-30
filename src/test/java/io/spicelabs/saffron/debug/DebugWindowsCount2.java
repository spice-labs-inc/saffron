package io.spicelabs.saffron.debug;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class DebugWindowsCount2 {
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
                System.out.println("SKIP: " + image);
                continue;
            }
            
            System.out.println("\n=== " + name + " ===");
            System.out.println("Expected files: " + expectedFiles[i]);
            
            try (VirtualDisk disk = DiskReader.open(p)) {
                FileSystemMount.MountAllResult mountResult = FileSystemMount.mountAllWithDetected(disk);
                var allFs = mountResult.mounted();
                
                AtomicLong actualFiles = new AtomicLong(0);
                AtomicLong actualDirs = new AtomicLong(0);
                List<String> allFiles = new ArrayList<>();
                
                for (FileSystem fs : allFs) {
                    try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                        walkStream.forEach(entry -> {
                            if (entry instanceof FileSystemEntry.RegularFile) {
                                actualFiles.incrementAndGet();
                                allFiles.add(entry.path());
                            } else if (entry instanceof FileSystemEntry.Directory) {
                                actualDirs.incrementAndGet();
                            }
                        });
                    }
                }
                
                Collections.sort(allFiles);
                System.out.println("Actual files: " + actualFiles.get());
                System.out.println("Actual dirs: " + actualDirs.get());
                System.out.println("Difference: " + (actualFiles.get() - expectedFiles[i]));
                
                System.out.println("\nAll files:");
                for (String f : allFiles) {
                    System.out.println("  " + f);
                }
                
                for (FileSystem fs : allFs) {
                    fs.close();
                }
            }
        }
    }
}
