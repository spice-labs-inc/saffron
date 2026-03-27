package io.spicelabs.saffron.debug;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class DebugExtraFiles {
    public static void main(String[] args) throws Exception {
        String image = "test-corpus/vhd/legacy/xp-mode/Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/Windows 95 Hard Disk.vhd";
        Path p = Path.of(image);
        
        System.out.println("=== Windows 95 - Looking for unusual files ===\n");
        
        try (VirtualDisk disk = DiskReader.open(p)) {
            FileSystemMount.MountAllResult mountResult = FileSystemMount.mountAllWithDetected(disk);
            var allFs = mountResult.mounted();
            
            for (FileSystem fs : allFs) {
                List<FileSystemEntry> files = new ArrayList<>();
                try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                    walkStream.forEach(entry -> {
                        if (entry instanceof FileSystemEntry.RegularFile) {
                            files.add(entry);
                        }
                    });
                }
                
                // Sort by path
                files.sort((a, b) -> a.path().compareTo(b.path()));
                
                System.out.println("Total files: " + files.size());
                System.out.println("\nFiles with unusual characteristics:");
                
                for (FileSystemEntry f : files) {
                    String name = f.name();
                    long size = f.size();
                    var attrs = f.attributes();
                    boolean isHidden = attrs.getOrDefault("hidden", Boolean.FALSE).equals(Boolean.TRUE);
                    boolean isSystem = attrs.getOrDefault("system", Boolean.FALSE).equals(Boolean.TRUE);
                    boolean isReadOnly = attrs.getOrDefault("readonly", Boolean.FALSE).equals(Boolean.TRUE);
                    
                    // Check for unusual characteristics
                    if (size == 0 || name.startsWith(".") || name.endsWith(".") || 
                        name.contains("?") || name.contains("*") ||
                        (!isHidden && !isSystem && size == 0)) {
                        System.out.println(f.path() + " size=" + size + 
                            " hidden=" + isHidden + " system=" + isSystem + 
                            " readonly=" + isReadOnly);
                    }
                }
            }
            
            for (FileSystem fs : allFs) {
                fs.close();
            }
        }
    }
}
