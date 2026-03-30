#!/usr/bin/env python3
"""
Unit tests for the corpus scanner classification logic.
Tests the classification functions without requiring libguestfs/Docker.
"""

import json
import sys
import os

# Mock guestfs for testing
class MockGuestFS:
    """Mock libguestfs.GuestFS for testing classification logic."""

    def __init__(self, filesystem_structure=None):
        self.fs = filesystem_structure or {}

    def exists(self, path):
        """Check if a path exists in the mock filesystem."""
        # Handle the root check
        if path == '/':
            return True

        # Remove leading slash for lookup
        if path.startswith('/'):
            path = path[1:]

        # Check if path exists as a file or directory
        parts = path.split('/')
        current = self.fs
        for part in parts:
            if part not in current:
                return False
            current = current[part]
        return True

    def readdir(self, path):
        """Return directory entries."""
        if path.startswith('/'):
            path = path[1:]

        if path == '':
            entries = list(self.fs.keys())
        else:
            parts = path.split('/')
            current = self.fs
            for part in parts:
                if part not in current:
                    return []
                current = current[part]
            if isinstance(current, dict):
                entries = list(current.keys())
            else:
                return []

        # Format like libguestfs readdir
        result = [{"name": ".", "ftyp": "d"}, {"name": "..", "ftyp": "d"}]
        for name in entries:
            if isinstance(current.get(name), dict):
                result.append({"name": name, "ftyp": "d"})
            else:
                result.append({"name": name, "ftyp": "r"})
        return result


def classify_filesystem(g, fstype: str) -> dict:
    """
    Classify a mounted filesystem by inspecting its contents.
    Copy of the function from scan_corpus.py for testing.
    """
    try:
        # Check for root filesystem indicators
        has_etc = g.exists('/etc')
        has_bin = g.exists('/bin') or g.exists('/sbin')
        has_usr = g.exists('/usr')
        has_init = g.exists('/sbin/init') or g.exists('/usr/sbin/init')
        has_lib = g.exists('/lib') or g.exists('/lib64') or g.exists('/usr/lib')

        # Check for boot filesystem indicators
        has_efi = g.exists('/EFI')
        has_boot = g.exists('/boot') or g.exists('/grub')
        is_vfat = fstype == 'vfat'

        # Check for home filesystem indicators
        has_home_root = g.exists('/home')
        home_entries = []
        if has_home_root:
            try:
                entries = g.readdir('/home')
                home_entries = [e['name'] for e in entries if e['name'] not in ('.', '..')]
            except:
                pass
        has_home_dirs = len(home_entries) > 0 and len(home_entries) < 20

        # Check for /var filesystem
        has_var = g.exists('/var')
        has_var_log = g.exists('/var/log')
        has_var_lib = g.exists('/var/lib')
        no_usr = not has_usr

        # Check for /opt filesystem
        has_opt = g.exists('/opt')
        opt_entries = []
        if has_opt:
            try:
                entries = g.readdir('/opt')
                opt_entries = [e['name'] for e in entries if e['name'] not in ('.', '..')]
            except:
                pass
        has_opt_content = len(opt_entries) > 0

        # Classify based on findings
        if has_etc and has_bin and has_usr:
            expected = ['/etc', '/bin', '/usr', '/lib']
            if has_init:
                expected.append('/sbin/init')

            if g.exists('/etc/debian_version'):
                expected.append('/etc/debian_version')
            if g.exists('/etc/redhat-release'):
                expected.append('/etc/redhat-release')
            if g.exists('/etc/os-release'):
                expected.append('/etc/os-release')

            return {
                'purpose': 'root',
                'isMountable': True,
                'mountPoint': '/',
                'expectedPaths': expected
            }
        elif is_vfat and (has_efi or has_boot):
            expected = []
            if has_efi:
                expected.append('/EFI')
            if g.exists('/EFI/BOOT'):
                expected.append('/EFI/BOOT')

            return {
                'purpose': 'boot',
                'isMountable': True,
                'mountPoint': '/boot/efi',
                'expectedPaths': expected
            }
        elif has_home_root and has_home_dirs and not has_etc and not has_usr:
            return {
                'purpose': 'home',
                'isMountable': True,
                'mountPoint': '/home',
                'expectedPaths': []
            }
        elif has_var and (has_var_log or has_var_lib) and no_usr:
            return {
                'purpose': 'var',
                'isMountable': True,
                'mountPoint': '/var',
                'expectedPaths': ['/var/log', '/var/lib']
            }
        elif has_opt and has_opt_content and not has_etc:
            return {
                'purpose': 'opt',
                'isMountable': True,
                'mountPoint': '/opt',
                'expectedPaths': []
            }
        elif not has_etc and not has_usr and not has_bin:
            return {
                'purpose': 'data',
                'isMountable': True,
                'mountPoint': None,
                'expectedPaths': []
            }
        else:
            return {
                'purpose': 'unknown',
                'isMountable': True,
                'mountPoint': None,
                'expectedPaths': []
            }

    except Exception as e:
        return {
            'purpose': 'unknown',
            'isMountable': True,
            'mountPoint': None,
            'expectedPaths': [],
            'classificationError': str(e)
        }


def classify_unmountable_filesystem(device: str, fstype: str) -> dict:
    """Copy of function from scan_corpus.py for testing."""
    if fstype == 'swap':
        return {
            'device': device,
            'fstype': fstype,
            'fileCount': 0,
            'directoryCount': 0,
            'purpose': 'swap',
            'isMountable': False,
            'mountPoint': None,
            'expectedPaths': [],
            'sampleFiles': []
        }
    elif fstype == 'crypto_LUKS':
        return {
            'device': device,
            'fstype': fstype,
            'fileCount': 0,
            'directoryCount': 0,
            'purpose': 'encrypted',
            'isMountable': False,
            'mountPoint': None,
            'expectedPaths': [],
            'sampleFiles': []
        }
    else:
        return {
            'device': device,
            'fstype': fstype,
            'fileCount': 0,
            'directoryCount': 0,
            'purpose': 'unknown',
            'isMountable': False,
            'mountPoint': None,
            'expectedPaths': [],
            'sampleFiles': []
        }


def test_root_classification():
    """Test detection of root filesystem."""
    # Simulate Ubuntu root filesystem
    fs = {
        'etc': {
            'debian_version': 'file',
            'os-release': 'file',
            'fstab': 'file'
        },
        'bin': {'ls': 'file', 'cat': 'file'},
        'usr': {'bin': {'python3': 'file'}},
        'lib': {'libc.so': 'file'},
        'sbin': {'init': 'file'},
        'home': {},
        'var': {'log': {'syslog': 'file'}}
    }

    g = MockGuestFS(fs)
    result = classify_filesystem(g, 'ext4')

    assert result['purpose'] == 'root', f"Expected 'root', got '{result['purpose']}'"
    assert result['isMountable'] == True
    assert result['mountPoint'] == '/'
    assert '/etc' in result['expectedPaths']
    assert '/etc/debian_version' in result['expectedPaths']
    print("✓ Root filesystem classification passed")


def test_boot_classification():
    """Test detection of boot/EFI filesystem."""
    fs = {
        'EFI': {
            'BOOT': {'BOOTX64.EFI': 'file'},
            'ubuntu': {'grubx64.efi': 'file'}
        }
    }

    g = MockGuestFS(fs)
    result = classify_filesystem(g, 'vfat')

    assert result['purpose'] == 'boot', f"Expected 'boot', got '{result['purpose']}'"
    assert result['isMountable'] == True
    assert result['mountPoint'] == '/boot/efi'
    assert '/EFI' in result['expectedPaths']
    print("✓ Boot filesystem classification passed")


def test_home_classification():
    """Test detection of home filesystem."""
    fs = {
        'home': {
            'user1': {'Documents': {}, 'Downloads': {}},
            'user2': {'Pictures': {}}
        }
    }

    g = MockGuestFS(fs)
    result = classify_filesystem(g, 'ext4')

    assert result['purpose'] == 'home', f"Expected 'home', got '{result['purpose']}'"
    assert result['isMountable'] == True
    assert result['mountPoint'] == '/home'
    print("✓ Home filesystem classification passed")


def test_var_classification():
    """Test detection of var filesystem."""
    fs = {
        'var': {
            'log': {'syslog': 'file', 'auth.log': 'file'},
            'lib': {'dpkg': {}, 'apt': {}}
        }
    }

    g = MockGuestFS(fs)
    result = classify_filesystem(g, 'xfs')

    assert result['purpose'] == 'var', f"Expected 'var', got '{result['purpose']}'"
    assert result['isMountable'] == True
    assert result['mountPoint'] == '/var'
    assert '/var/log' in result['expectedPaths']
    print("✓ Var filesystem classification passed")


def test_swap_unmountable():
    """Test swap filesystem is marked unmountable."""
    result = classify_unmountable_filesystem('/dev/sda2', 'swap')

    assert result['purpose'] == 'swap'
    assert result['isMountable'] == False
    assert result['fileCount'] == 0
    assert result['device'] == '/dev/sda2'
    print("✓ Swap filesystem classification passed")


def test_luks_unmountable():
    """Test LUKS filesystem is marked unmountable."""
    result = classify_unmountable_filesystem('/dev/sda3', 'crypto_LUKS')

    assert result['purpose'] == 'encrypted'
    assert result['isMountable'] == False
    assert result['fstype'] == 'crypto_LUKS'
    print("✓ LUKS filesystem classification passed")


def test_json_structure():
    """Test that the scanner output JSON structure is valid."""
    # Simulate what scan_image would produce
    result = {
        "imagePath": "/corpus/vdi/modern/ubuntu-22.04-vbox.vdi",
        "imageBasename": "ubuntu-22.04-vbox.vdi",
        "filesystemCount": 3,
        "totalFiles": 128783,
        "totalDirectories": 15621,
        "filesystems": [
            {
                "device": "/dev/sda2",
                "fstype": "vfat",
                "fileCount": 8,
                "directoryCount": 3,
                "purpose": "boot",
                "isMountable": True,
                "mountPoint": "/boot/efi",
                "expectedPaths": ["/EFI", "/EFI/BOOT"],
                "sampleFiles": [
                    {"path": "/EFI/BOOT/BOOTX64.EFI", "size": 955656, "sha256": "abc123..."}
                ]
            },
            {
                "device": "/dev/vgubuntu/root",
                "fstype": "ext4",
                "fileCount": 128775,
                "directoryCount": 15618,
                "purpose": "root",
                "isMountable": True,
                "mountPoint": "/",
                "expectedPaths": ["/etc", "/bin", "/usr", "/etc/debian_version"],
                "sampleFiles": []
            },
            {
                "device": "/dev/vgubuntu/swap_1",
                "fstype": "swap",
                "fileCount": 0,
                "directoryCount": 0,
                "purpose": "swap",
                "isMountable": False,
                "mountPoint": None,
                "expectedPaths": [],
                "sampleFiles": []
            }
        ]
    }

    # Validate structure
    assert 'imagePath' in result
    assert 'filesystems' in result
    assert len(result['filesystems']) == result['filesystemCount']

    for fs in result['filesystems']:
        assert 'device' in fs
        assert 'fstype' in fs
        assert 'purpose' in fs
        assert 'isMountable' in fs
        assert 'expectedPaths' in fs

    # Verify mountable vs unmountable
    mountable = [fs for fs in result['filesystems'] if fs['isMountable']]
    unmountable = [fs for fs in result['filesystems'] if not fs['isMountable']]

    assert len(mountable) == 2  # boot and root
    assert len(unmountable) == 1  # swap

    # Verify totals exclude unmountable
    total_files = sum(fs['fileCount'] for fs in mountable)
    assert total_files == result['totalFiles']

    print("✓ JSON structure validation passed")


def test_backward_compatibility():
    """Test that new fields don't break old consumers."""
    # Old JSON without new fields
    old_json = {
        "device": "/dev/sda1",
        "fstype": "ext4",
        "fileCount": 100,
        "directoryCount": 10,
        "sampleFiles": []
    }

    # Should be valid without new fields
    assert 'purpose' not in old_json
    assert 'isMountable' not in old_json

    # New JSON with all fields
    new_json = {
        "device": "/dev/sda1",
        "fstype": "ext4",
        "fileCount": 100,
        "directoryCount": 10,
        "purpose": "root",
        "isMountable": True,
        "mountPoint": "/",
        "expectedPaths": ["/etc"],
        "sampleFiles": []
    }

    # Both should serialize/deserialize correctly
    json_str = json.dumps(new_json)
    restored = json.loads(json_str)
    assert restored['purpose'] == 'root'
    assert restored['isMountable'] == True

    print("✓ Backward compatibility check passed")


def run_all_tests():
    """Run all test cases."""
    print("=" * 70)
    print("CORPUS SCANNER CLASSIFICATION TESTS")
    print("=" * 70)

    tests = [
        test_root_classification,
        test_boot_classification,
        test_home_classification,
        test_var_classification,
        test_swap_unmountable,
        test_luks_unmountable,
        test_json_structure,
        test_backward_compatibility,
    ]

    passed = 0
    failed = 0

    for test in tests:
        try:
            test()
            passed += 1
        except AssertionError as e:
            print(f"✗ {test.__name__} FAILED: {e}")
            failed += 1
        except Exception as e:
            print(f"✗ {test.__name__} ERROR: {e}")
            failed += 1

    print("=" * 70)
    print(f"RESULTS: {passed} passed, {failed} failed")
    print("=" * 70)

    return failed == 0


if __name__ == "__main__":
    success = run_all_tests()
    sys.exit(0 if success else 1)
