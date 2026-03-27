package io.spicelabs.saffron.debug;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class CountHidden {
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
                AtomicLong hiddenFiles = new AtomicLong(0);
                AtomicLong systemFiles = new AtomicLong(0);
                AtomicLong hiddenSystemFiles = new AtomicLong(0);
                
                for (FileSystem fs : allFs) {
                    try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                        walkStream.forEach(entry -> {
                            if (entry instanceof FileSystemEntry.RegularFile) {
                                totalFiles.incrementAndGet();
                                var attrs = entry.attributes();
                                boolean isHidden = attrs.getOrDefault("hidden", Boolean.FALSE).equals(Boolean.TRUE);
                                boolean isSystem = attrs.getOrDefault("system", Boolean.FALSE).equals(Boolean.TRUE);
                                
                                if (isHidden) hiddenFiles.incrementAndGet();
                                if (isSystem) systemFiles.incrementAndGet();
                                if (isHidden || isSystem) hiddenSystemFiles.incrementAndGet();
                            }
                        });
                    }
                }
                
                System.out.println("Total files: " + totalFiles.get());
                System.out.println("Hidden files: " + hiddenFiles.get());
                System.out.println("System files: " + systemFiles.get());
                System.out.println("Hidden OR System: " + hiddenSystemFiles.get());
                System.out.println("Expected: " + expectedFiles[i]);
                System.out.println("Total - Hidden/System: " + (totalFiles.get() - hiddenSystemFiles.get()));
                System.out.println("Difference from expected: " + (totalFiles.get() - hiddenSystemFiles.get() - expectedFiles[i]));
                
                for (FileSystem fs : allFs) {
                    fs.close();
                }
            }
        }
    }
}
