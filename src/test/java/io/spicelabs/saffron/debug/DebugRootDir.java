package io.spicelabs.saffron.debug;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class DebugRootDir {
    public static void main(String[] args) throws Exception {
        String image = "test-corpus/vhd/legacy/xp-mode/Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/Windows 95 Hard Disk.vhd";
        Path p = Path.of(image);
        
        System.out.println("=== Windows 95 - Root Directory Contents ===\n");
        
        try (VirtualDisk disk = DiskReader.open(p)) {
            FileSystemMount.MountAllResult mountResult = FileSystemMount.mountAllWithDetected(disk);
            var allFs = mountResult.mounted();
            
            for (FileSystem fs : allFs) {
                System.out.println("Filesystem: " + fs.type());
                FileSystemEntry.Directory root = fs.root();
                System.out.println("Root path: " + root.path());
                
                List<FileSystemEntry> entries = new ArrayList<>();
                try (Stream<FileSystemEntry> list = root.list()) {
                    list.forEach(entries::add);
                }
                
                System.out.println("Root entries count: " + entries.size());
                System.out.println("\nRoot entries:");
                
                int files = 0, dirs = 0;
                for (FileSystemEntry e : entries) {
                    String type = e instanceof FileSystemEntry.RegularFile ? "FILE" : 
                                  e instanceof FileSystemEntry.Directory ? "DIR" : "OTHER";
                    if (e instanceof FileSystemEntry.RegularFile) files++;
                    if (e instanceof FileSystemEntry.Directory) dirs++;
                    
                    var attrs = e.attributes();
                    boolean isHidden = attrs.getOrDefault("hidden", Boolean.FALSE).equals(Boolean.TRUE);
                    boolean isSystem = attrs.getOrDefault("system", Boolean.FALSE).equals(Boolean.TRUE);
                    
                    System.out.println("  " + type + " " + e.name() + 
                        " size=" + e.size() + " hidden=" + isHidden + " system=" + isSystem);
                }
                
                System.out.println("\nFiles in root: " + files);
                System.out.println("Dirs in root: " + dirs);
            }
            
            for (FileSystem fs : allFs) {
                fs.close();
            }
        }
    }
}
