#!/bin/bash

# Start all 9 NATS servers across 3 clusters with 3 nodes each.
# Each cluster has full JetStream support with R3 streams via the
# three nodes per cluster. The clusters are connected via gateways
# forming a supercluster.
#
# With 9 servers (odd number), the supercluster can reach consensus
# for the meta group leader.

# Set the NATS server version to use (defaults to 2.12.2)
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

VERSION=${VERSION:-2.12.2}

# If VERSION is just major.minor (e.g., 2.11), find the patch version in bin/
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  # No patch version provided, look for it in bin/
  matching_version=$(ls -1 "$SCRIPT_DIR/bin/" 2>/dev/null | grep "^${VERSION}\." | sort -V | tail -1)
  if [ -z "$matching_version" ]; then
    echo "No version found in bin/ matching ${VERSION}.*, fetching latest ${VERSION}.x from GitHub..."
    matching_version=$(curl -sf "https://api.github.com/repos/nats-io/nats-server/releases" | \
      grep '"tag_name"' | grep "\"v${VERSION}\." | head -1 | \
      sed 's/.*"v\([^"]*\)".*/\1/')
    if [ -z "$matching_version" ]; then
      echo "Error: Could not find a release matching ${VERSION}.x on GitHub"
      exit 1
    fi
    echo "Found: ${matching_version}"
  fi
  VERSION="$matching_version"
fi

NATS_SERVER="$SCRIPT_DIR/bin/${VERSION}/nats-server"

# Detect OS and architecture
detect_os_arch() {
  local os arch

  # Detect OS
  case "$(uname -s)" in
    Darwin*)
      os="darwin"
      ;;
    Linux*)
      os="linux"
      ;;
    *)
      echo "Error: Unsupported OS: $(uname -s)"
      echo "For Windows, use start.ps1"
      exit 1
      ;;
  esac

  # Detect architecture
  case "$(uname -m)" in
    x86_64)
      arch="amd64"
      ;;
    aarch64|arm64)
      arch="arm64"
      ;;
    *)
      echo "Error: Unsupported architecture: $(uname -m)"
      exit 1
      ;;
  esac

  echo "${os}-${arch}"
}

# Download and extract NATS server binary
download_nats_server() {
  local version=$1
  local os_arch=$2
  local bin_dir="$SCRIPT_DIR/bin/${version}"

  local file_ext="tar.gz"
  local release_name="nats-server-v${version}-${os_arch}.${file_ext}"
  local download_url="https://github.com/nats-io/nats-server/releases/download/v${version}/${release_name}"

  echo "Downloading NATS server v${version} for ${os_arch}..."
  echo "URL: $download_url"

  mkdir -p "$bin_dir"
  local temp_file=$(mktemp)

  # Download the release
  if ! curl -L --fail --silent --show-error -o "$temp_file" "$download_url"; then
    rm -f "$temp_file"
    echo "Error: Failed to download NATS server from $download_url"
    exit 1
  fi

  # Extract the binary
  # Extract to a temp directory first, then move the binary
  local temp_extract=$(mktemp -d)
  if ! tar -xzf "$temp_file" -C "$temp_extract"; then
    rm -rf "$temp_extract" "$temp_file"
    echo "Error: Failed to extract NATS server binary"
    exit 1
  fi

  # Find and move the nats-server binary (it's in a subdirectory)
  if ! mv "$temp_extract"/*/nats-server "$bin_dir/"; then
    rm -rf "$temp_extract" "$temp_file"
    echo "Error: Failed to locate or move nats-server binary from archive"
    exit 1
  fi
  rm -rf "$temp_extract"

  rm -f "$temp_file"

  chmod +x "$bin_dir/nats-server"

  echo "Successfully downloaded and extracted NATS server v${version}"
}

# Check if binary exists, download if necessary
if [ ! -f "$NATS_SERVER" ]; then
  os_arch=$(detect_os_arch)
  download_nats_server "$VERSION" "$os_arch"
fi

echo "Using NATS server version: $VERSION"

mkdir -p "$SCRIPT_DIR/logs"

# Start all servers in the background
echo "Starting Cluster 1 nodes..."
$NATS_SERVER -c "$SCRIPT_DIR/conf/c1-n1.conf" > "$SCRIPT_DIR/logs/c1-n1.log" 2>&1 &
$NATS_SERVER -c "$SCRIPT_DIR/conf/c1-n2.conf" > "$SCRIPT_DIR/logs/c1-n2.log" 2>&1 &
$NATS_SERVER -c "$SCRIPT_DIR/conf/c1-n3.conf" > "$SCRIPT_DIR/logs/c1-n3.log" 2>&1 &

echo "Starting Cluster 2 nodes..."
$NATS_SERVER -c "$SCRIPT_DIR/conf/c2-n1.conf" > "$SCRIPT_DIR/logs/c2-n1.log" 2>&1 &
$NATS_SERVER -c "$SCRIPT_DIR/conf/c2-n2.conf" > "$SCRIPT_DIR/logs/c2-n2.log" 2>&1 &
$NATS_SERVER -c "$SCRIPT_DIR/conf/c2-n3.conf" > "$SCRIPT_DIR/logs/c2-n3.log" 2>&1 &

echo "Starting Cluster 3 nodes..."
$NATS_SERVER -c "$SCRIPT_DIR/conf/c3-n1.conf" > "$SCRIPT_DIR/logs/c3-n1.log" 2>&1 &
$NATS_SERVER -c "$SCRIPT_DIR/conf/c3-n2.conf" > "$SCRIPT_DIR/logs/c3-n2.log" 2>&1 &
$NATS_SERVER -c "$SCRIPT_DIR/conf/c3-n3.conf" > "$SCRIPT_DIR/logs/c3-n3.log" 2>&1 &

# Cluster 1
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:8222/healthz > /dev/null
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:8223/healthz > /dev/null
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:8224/healthz > /dev/null
echo "Cluster 1 nodes are healthy."

# Cluster 2
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:8225/healthz > /dev/null
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:8226/healthz > /dev/null
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:8227/healthz > /dev/null
echo "Cluster 2 nodes are healthy."

# Cluster 3
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:8228/healthz > /dev/null
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:8229/healthz > /dev/null
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:8230/healthz > /dev/null
echo "Cluster 3 nodes are healthy."

echo "Starting Cluster 2 leafnodes..."
$NATS_SERVER -c "$SCRIPT_DIR/conf/c1-l1.conf" > "$SCRIPT_DIR/logs/c1-l1.log" 2>&1 &
$NATS_SERVER -c "$SCRIPT_DIR/conf/c1-l2.conf" > "$SCRIPT_DIR/logs/c1-l2.log" 2>&1 &

# Cluster 2 leafnodes
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:9000/healthz > /dev/null
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:9001/healthz > /dev/null
echo "Cluster 2 leafnodes are healthy."

echo "Starting Cluster 5 leafnodes..."
$NATS_SERVER -c "$SCRIPT_DIR/conf/c3-l1.conf" > "$SCRIPT_DIR/logs/c3-11.log" 2>&1 &
$NATS_SERVER -c "$SCRIPT_DIR/conf/c3-l2.conf" > "$SCRIPT_DIR/logs/c3-12.log" 2>&1 &
$NATS_SERVER -c "$SCRIPT_DIR/conf/c3-l3.conf" > "$SCRIPT_DIR/logs/c3-13.log" 2>&1 &
$NATS_SERVER -c "$SCRIPT_DIR/conf/c3-l4.conf" > "$SCRIPT_DIR/logs/c3-l4.log" 2>&1 &
$NATS_SERVER -c "$SCRIPT_DIR/conf/c3-l5.conf" > "$SCRIPT_DIR/logs/c3-l5.log" 2>&1 &

# Cluster 5 leafnodes
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:9002/healthz > /dev/null
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:9003/healthz > /dev/null
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:9004/healthz > /dev/null
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:9005/healthz > /dev/null
curl --fail --silent --retry 5 --retry-delay 1 http://localhost:9006/healthz > /dev/null
echo "Cluster 5 leafnodes are healthy."

echo "All servers and leafnodes are healthy and running!"
