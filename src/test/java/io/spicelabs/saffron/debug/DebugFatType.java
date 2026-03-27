package io.spicelabs.saffron.debug;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemMount;
import java.nio.file.Path;
import java.util.Map;

public class DebugFatType {
    public static void main(String[] args) throws Exception {
        String[] images = {
            "test-corpus/vhd/legacy/xp-mode/Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/Windows 95 Hard Disk.vhd",
            "test-corpus/vhd/legacy/xp-mode/Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/Windows 98 Plus! Hard Disk.vhd",
            "test-corpus/vmdk/legacy/windows-me.vmdk"
        };
        
        String[] names = {"Win95", "Win98", "WinME"};
        
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
                
                for (FileSystem fs : allFs) {
                    System.out.println("Type: " + fs.type());
                    Map<String, String> meta = fs.metadata();
                    for (Map.Entry<String, String> e : meta.entrySet()) {
                        System.out.println("  " + e.getKey() + ": " + e.getValue());
                    }
                }
                
                for (FileSystem fs : allFs) {
                    fs.close();
                }
            }
        }
    }
}
