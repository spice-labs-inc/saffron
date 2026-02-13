#!/bin/bash
# =============================================================================
# Saffron Corpus Manifest Signing Script
# =============================================================================
# Signs the corpus manifest with GPG to prevent tampering.
#
# Usage:
#   ./scripts/sign-manifest.sh [options]
#
# Options:
#   --corpus-dir DIR    Corpus directory (default: ./test-corpus)
#   --key-id KEY        GPG key ID to use for signing
#   --verify            Only verify existing signature
#   --help              Show this help message
#
# The signature is created as a detached signature: manifest.json.sig
# =============================================================================

set -euo pipefail

# Resolve project root from script location
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Configuration
CORPUS_DIR="${CORPUS_DIR:-$PROJECT_ROOT/test-corpus}"
MANIFEST_FILE="$CORPUS_DIR/manifest.json"
SIGNATURE_FILE="$MANIFEST_FILE.sig"
KEY_ID=""
VERIFY_ONLY=false

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --corpus-dir)
            CORPUS_DIR="$2"
            MANIFEST_FILE="$CORPUS_DIR/manifest.json"
            SIGNATURE_FILE="$MANIFEST_FILE.sig"
            shift 2
            ;;
        --key-id)
            KEY_ID="$2"
            shift 2
            ;;
        --verify)
            VERIFY_ONLY=true
            shift
            ;;
        --help)
            head -20 "$0" | tail -18
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Check prerequisites
check_prerequisites() {
    if ! command -v gpg &> /dev/null; then
        log_error "GPG is required but not installed"
        log_error "Install with: sudo apt-get install gnupg"
        exit 1
    fi

    if [ ! -f "$MANIFEST_FILE" ]; then
        log_error "Manifest not found: $MANIFEST_FILE"
        exit 1
    fi
}

# Verify existing signature
verify_signature() {
    log_info "Verifying manifest signature..."

    if [ ! -f "$SIGNATURE_FILE" ]; then
        log_error "Signature file not found: $SIGNATURE_FILE"
        return 1
    fi

    if gpg --verify "$SIGNATURE_FILE" "$MANIFEST_FILE" 2>&1; then
        log_info "Signature is VALID"
        return 0
    else
        log_error "Signature is INVALID or untrusted"
        return 1
    fi
}

# List available keys
list_keys() {
    log_info "Available signing keys:"
    gpg --list-secret-keys --keyid-format LONG 2>/dev/null | grep -A1 "^sec" || true
}

# Create signature
sign_manifest() {
    log_info "Signing manifest: $MANIFEST_FILE"

    # Remove old signature
    rm -f "$SIGNATURE_FILE"

    # Build GPG command
    local gpg_args=(--detach-sign --armor --output "$SIGNATURE_FILE")

    if [ -n "$KEY_ID" ]; then
        gpg_args+=(--local-user "$KEY_ID")
        log_info "Using key: $KEY_ID"
    else
        log_info "Using default GPG key"
    fi

    # Sign
    if gpg "${gpg_args[@]}" "$MANIFEST_FILE"; then
        log_info "Signature created: $SIGNATURE_FILE"

        # Verify the new signature
        log_info "Verifying new signature..."
        if gpg --verify "$SIGNATURE_FILE" "$MANIFEST_FILE" 2>&1; then
            log_info "Signature verified successfully"
        else
            log_error "Signature verification failed!"
            rm -f "$SIGNATURE_FILE"
            exit 1
        fi
    else
        log_error "Failed to create signature"
        exit 1
    fi
}

# Show signature info
show_signature_info() {
    if [ -f "$SIGNATURE_FILE" ]; then
        echo ""
        echo "=== Signature Information ==="
        gpg --verify --verbose "$SIGNATURE_FILE" "$MANIFEST_FILE" 2>&1 | head -10 || true
    fi
}

# Generate a new key if none exists
check_or_generate_key() {
    local key_count
    key_count=$(gpg --list-secret-keys 2>/dev/null | grep -c "^sec" || echo 0)

    if [ "$key_count" -eq 0 ]; then
        log_warn "No GPG keys found"
        echo ""
        echo "To create a new key for corpus signing, run:"
        echo ""
        echo "  gpg --quick-generate-key 'Saffron Corpus Signing <saffron@spicelabs.io>' ed25519 sign never"
        echo ""
        echo "Then export the public key for distribution:"
        echo ""
        echo "  gpg --armor --export 'Saffron Corpus Signing' > saffron-corpus-signing.asc"
        echo ""
        exit 1
    fi
}

# Main
main() {
    log_info "=== Saffron Corpus Manifest Signing ==="

    check_prerequisites

    if [ "$VERIFY_ONLY" = true ]; then
        if verify_signature; then
            exit 0
        else
            exit 1
        fi
    fi

    check_or_generate_key

    if [ -z "$KEY_ID" ]; then
        list_keys
        echo ""
        log_info "Using default key (first available)"
        log_info "Use --key-id to specify a different key"
        echo ""
    fi

    sign_manifest
    show_signature_info

    echo ""
    log_info "Done! The manifest is now signed."
    log_info "Distribute the public key to allow verification:"
    log_info "  gpg --armor --export [KEY_ID] > saffron-corpus-signing.asc"
}

main
