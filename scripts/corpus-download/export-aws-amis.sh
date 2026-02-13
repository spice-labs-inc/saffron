#!/bin/bash
# Export AWS AMIs for Saffron test corpus
# Requires: AWS CLI configured with credentials
#
# This script exports AWS public AMIs to S3 and downloads them.
# You need:
#   1. An AWS account with IAM permissions for VM Import/Export
#   2. An S3 bucket for exports
#   3. AWS CLI configured: aws configure
#   4. The vmimport service role set up
#
# Setup VM Import role (one-time):
#   https://docs.aws.amazon.com/vm-import/latest/userguide/required-permissions.html
#
# Usage:
#   export AWS_REGION=us-east-1
#   export AWS_S3_BUCKET=my-saffron-exports
#   ./export-aws-amis.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORPUS_BASE="${CORPUS_BASE:-/home/dpp/tmp/vmreader/saffron/test-corpus}"

# Check required environment variables
if [[ -z "${AWS_REGION:-}" ]]; then
    echo "ERROR: AWS_REGION environment variable not set"
    echo "Usage: AWS_REGION=us-east-1 AWS_S3_BUCKET=my-bucket $0"
    exit 1
fi

if [[ -z "${AWS_S3_BUCKET:-}" ]]; then
    echo "ERROR: AWS_S3_BUCKET environment variable not set"
    exit 1
fi

# Check for AWS CLI
if ! command -v aws &> /dev/null; then
    echo "ERROR: AWS CLI is required but not installed."
    echo "Install from: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"
    exit 1
fi

# Verify AWS credentials
if ! aws sts get-caller-identity &>/dev/null; then
    echo "ERROR: AWS credentials not configured or invalid."
    echo "Run: aws configure"
    exit 1
fi

# Create target directories
mkdir -p "$CORPUS_BASE/vmdk/cloud/aws"
mkdir -p "$CORPUS_BASE/vhd/cloud/aws"
mkdir -p "$CORPUS_BASE/raw/cloud/aws"

# Define AMIs to export
# Format: "ami-id|name|format"
# Note: AMI IDs are region-specific - these are for us-east-1
# You can find current AMIs with: aws ec2 describe-images --owners amazon --filters "Name=name,Values=*"
declare -a IMAGES=(
    # Amazon Linux
    "resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64|amazon-linux-2023|vmdk"
    "resolve:ssm:/aws/service/ami-amazon-linux-latest/amzn2-ami-hvm-x86_64-gp2|amazon-linux-2|vmdk"

    # Ubuntu (Canonical)
    "resolve:ssm:/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id|ubuntu-2404-aws|vmdk"
    "resolve:ssm:/aws/service/canonical/ubuntu/server/22.04/stable/current/amd64/hvm/ebs-gp2/ami-id|ubuntu-2204-aws|vmdk"
    "resolve:ssm:/aws/service/canonical/ubuntu/server/20.04/stable/current/amd64/hvm/ebs-gp2/ami-id|ubuntu-2004-aws|vmdk"

    # Debian
    "resolve:ssm:/aws/service/debian/release/12/latest/amd64|debian-12-aws|vmdk"
)

resolve_ami_id() {
    local ami_spec="$1"

    if [[ "$ami_spec" == resolve:ssm:* ]]; then
        local ssm_param="${ami_spec#resolve:ssm:}"
        aws ssm get-parameter --name "$ssm_param" --query "Parameter.Value" --output text 2>/dev/null || echo ""
    else
        echo "$ami_spec"
    fi
}

export_ami() {
    local ami_spec="$1"
    local output_name="$2"
    local format="$3"

    local target_dir=""
    local ext=""

    case "$format" in
        vmdk)
            target_dir="$CORPUS_BASE/vmdk/cloud/aws"
            ext="vmdk"
            ;;
        vhd)
            target_dir="$CORPUS_BASE/vhd/cloud/aws"
            ext="vhd"
            ;;
        raw)
            target_dir="$CORPUS_BASE/raw/cloud/aws"
            ext="raw"
            ;;
        *)
            echo "Unknown format: $format"
            return 1
            ;;
    esac

    local target_file="$target_dir/${output_name}.${ext}"
    local s3_prefix="saffron-exports/${output_name}"

    if [[ -f "$target_file" ]]; then
        echo "SKIP: $output_name.$ext (already exists locally)"
        return 0
    fi

    # Resolve AMI ID
    local ami_id
    ami_id=$(resolve_ami_id "$ami_spec")

    if [[ -z "$ami_id" ]]; then
        echo "FAILED: Could not resolve AMI ID for $ami_spec"
        return 1
    fi

    echo "Exporting: $ami_id -> $output_name.$ext"

    # Create an export task
    # Note: This requires the AMI to be owned by you or be a public AMI you've copied
    # For public AMIs, you first need to copy them to your account

    # Check if AMI is owned by us or if we need to copy it
    local ami_owner
    ami_owner=$(aws ec2 describe-images --image-ids "$ami_id" --query "Images[0].OwnerId" --output text 2>/dev/null) || {
        echo "  FAILED: Could not describe AMI"
        return 1
    }

    local account_id
    account_id=$(aws sts get-caller-identity --query "Account" --output text)

    local export_ami_id="$ami_id"

    if [[ "$ami_owner" != "$account_id" ]]; then
        echo "  AMI owned by $ami_owner, copying to our account..."

        # Copy the AMI to our account
        local copy_ami_id
        copy_ami_id=$(aws ec2 copy-image \
            --source-region "$AWS_REGION" \
            --source-image-id "$ami_id" \
            --name "saffron-copy-${output_name}" \
            --description "Saffron test corpus copy" \
            --query "ImageId" --output text 2>/dev/null) || {
            echo "  FAILED: Could not copy AMI"
            return 1
        }

        echo "  Copy initiated: $copy_ami_id"
        echo "  Waiting for copy to complete (this may take several minutes)..."

        aws ec2 wait image-available --image-ids "$copy_ami_id" 2>/dev/null || {
            echo "  FAILED: AMI copy did not complete"
            return 1
        }

        export_ami_id="$copy_ami_id"
    fi

    # Create export task
    echo "  Creating export task..."

    local task_id
    task_id=$(aws ec2 create-instance-export-task \
        --instance-id "PLACEHOLDER" \
        --target-environment vmware \
        --export-to-s3-task "DiskImageFormat=${format^^},S3Bucket=$AWS_S3_BUCKET,S3Prefix=$s3_prefix" \
        --query "ExportTask.ExportTaskId" --output text 2>/dev/null) || {

        # Alternative: Use VM Import/Export API directly
        # For AMIs, we need to create an instance, stop it, then export
        echo "  Note: Direct AMI export requires creating a temporary instance"
        echo "  Creating temporary instance from AMI..."

        # Launch a small instance
        local instance_id
        instance_id=$(aws ec2 run-instances \
            --image-id "$export_ami_id" \
            --instance-type t3.micro \
            --count 1 \
            --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=saffron-export-temp}]" \
            --query "Instances[0].InstanceId" --output text 2>/dev/null) || {
            echo "  FAILED: Could not launch instance"
            return 1
        }

        echo "  Instance launched: $instance_id"
        echo "  Waiting for instance to be running..."

        aws ec2 wait instance-running --instance-ids "$instance_id" 2>/dev/null

        echo "  Stopping instance..."
        aws ec2 stop-instances --instance-ids "$instance_id" --output none 2>/dev/null
        aws ec2 wait instance-stopped --instance-ids "$instance_id" 2>/dev/null

        echo "  Creating export task..."
        task_id=$(aws ec2 create-instance-export-task \
            --instance-id "$instance_id" \
            --target-environment vmware \
            --export-to-s3-task "DiskImageFormat=${format^^},S3Bucket=$AWS_S3_BUCKET,S3Prefix=$s3_prefix" \
            --query "ExportTask.ExportTaskId" --output text 2>/dev/null) || {
            echo "  FAILED: Could not create export task"
            # Cleanup instance
            aws ec2 terminate-instances --instance-ids "$instance_id" --output none 2>/dev/null || true
            return 1
        }

        echo "  Export task created: $task_id"
        echo "  Terminating temporary instance..."
        aws ec2 terminate-instances --instance-ids "$instance_id" --output none 2>/dev/null || true
    }

    if [[ -n "$task_id" ]]; then
        echo "  Export task: $task_id"
        echo "  Monitor with: aws ec2 describe-export-tasks --export-task-ids $task_id"
        echo ""
        echo "  After export completes, download with:"
        echo "    aws s3 cp s3://$AWS_S3_BUCKET/${s3_prefix}/ $target_dir/ --recursive"
        return 0
    else
        echo "  FAILED: Could not create export task"
        return 1
    fi
}

echo "=== AWS AMI Export ==="
echo "Region: $AWS_REGION"
echo "S3 Bucket: $AWS_S3_BUCKET"
echo "Target: $CORPUS_BASE"
echo ""
echo "NOTE: AMI exports require VM Import/Export permissions and may incur charges."
echo "      See: https://docs.aws.amazon.com/vm-import/latest/userguide/vmexport.html"
echo ""

# Alternative: Download pre-built cloud images directly
echo "=== Alternative: Direct Download (Recommended) ==="
echo ""
echo "Instead of exporting AMIs, you can download official cloud images directly:"
echo ""
echo "Amazon Linux 2023:"
echo "  These are only available as AMIs - export required"
echo ""
echo "Amazon Linux 2:"
echo "  https://cdn.amazonlinux.com/os-images/latest/kvm/amzn2-kvm-2.0.*-x86_64.xfs.gpt.qcow2"
echo ""

# Direct downloads for Amazon Linux 2 (QCOW2 available)
mkdir -p "$CORPUS_BASE/qcow2/cloud/aws"

download_amazon_linux() {
    local url="$1"
    local target="$2"

    if [[ -f "$target" ]]; then
        echo "SKIP: $(basename "$target") (already exists)"
        return 0
    fi

    echo "Downloading: $(basename "$target")"
    if curl -fSL --progress-bar -o "$target.tmp" "$url"; then
        mv "$target.tmp" "$target"
        echo "  OK: Downloaded $(du -h "$target" | cut -f1)"
        return 0
    else
        rm -f "$target.tmp"
        echo "  FAILED: Could not download"
        return 1
    fi
}

echo ""
echo "=== Downloading Amazon Linux Images ==="

# Amazon Linux 2 (QCOW2 available for direct download)
download_amazon_linux \
    "https://cdn.amazonlinux.com/os-images/2.0.20240306.2/kvm/amzn2-kvm-2.0.20240306.2-x86_64.xfs.gpt.qcow2" \
    "$CORPUS_BASE/qcow2/cloud/aws/amazon-linux-2-kvm-amd64.qcow2" || true

# Amazon Linux 2023 (QCOW2 also available)
download_amazon_linux \
    "https://cdn.amazonlinux.com/al2023/os-images/2023.4.20240319.1/kvm/al2023-kvm-2023.4.20240319.1-kernel-6.1-x86_64.xfs.gpt.qcow2" \
    "$CORPUS_BASE/qcow2/cloud/aws/amazon-linux-2023-kvm-amd64.qcow2" || true

echo ""
echo "=== Summary ==="
echo ""
echo "Downloaded Amazon Linux images:"
find "$CORPUS_BASE" -path "*/cloud/aws/*" -name "*.qcow2" -type f 2>/dev/null | sort
echo ""
echo "For full AMI exports, configure AWS permissions and run the export tasks above."
