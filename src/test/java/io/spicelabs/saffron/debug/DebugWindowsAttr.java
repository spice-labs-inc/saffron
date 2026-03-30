package io.spicelabs.saffron.debug;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import java.nio.file.Path;
import java.util.stream.Stream;

public class DebugWindowsAttr {
    public static void main(String[] args) throws Exception {
        String image = "test-corpus/vhd/legacy/xp-mode/Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/Windows 95 Hard Disk.vhd";
        Path p = Path.of(image);
        
        System.out.println("=== Windows 95 - Checking file attributes ===\n");
        
        try (VirtualDisk disk = DiskReader.open(p)) {
            FileSystemMount.MountAllResult mountResult = FileSystemMount.mountAllWithDetected(disk);
            var allFs = mountResult.mounted();
            
            for (FileSystem fs : allFs) {
                try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                    walkStream.forEach(entry -> {
                        if (entry instanceof FileSystemEntry.RegularFile) {
                            var attrs = entry.attributes();
                            boolean isHidden = attrs.getOrDefault("hidden", Boolean.FALSE).equals(Boolean.TRUE);
                            boolean isSystem = attrs.getOrDefault("system", Boolean.FALSE).equals(Boolean.TRUE);
                            
                            if (isHidden || isSystem) {
                                System.out.println(entry.path() + " hidden=" + isHidden + " system=" + isSystem);
                            }
                        }
                    });
                }
            }
            
            for (FileSystem fs : allFs) {
                fs.close();
            }
        }
    }
}
