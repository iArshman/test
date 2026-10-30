#!/bin/bash
set -euo pipefail

ABI="${1:-armv8}"
BUILD_TYPE="${2:-debug}"

if [ ! -f "gradlew" ]; then
    echo "Error: gradlew not found. Are you in the project root?" >&2
    exit 1
fi

chmod +x gradlew
chmod +x native/build-native.sh

if ! command -v node >/dev/null 2>&1; then
    echo "Error: Node.js is not installed in WSL." >&2
    echo "Please install Node.js in WSL using one of the following methods:" >&2
    echo "" >&2
    echo "Option 1: Install via nvm (recommended):" >&2
    echo "  curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.0/install.sh | bash" >&2
    echo "  source ~/.bashrc" >&2
    echo "  nvm install --lts" >&2
    echo "" >&2
    echo "Option 2: Install via apt:" >&2
    echo "  curl -fsSL https://deb.nodesource.com/setup_lts.x | sudo -E bash -" >&2
    echo "  sudo apt-get install -y nodejs" >&2
    echo "" >&2
    exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
    echo "Error: npm is not installed in WSL." >&2
    echo "Please install npm (it should come with Node.js)." >&2
    exit 1
fi

if ! command -v gcc >/dev/null 2>&1 && ! command -v clang >/dev/null 2>&1; then
    echo "Error: No C compiler found in WSL. Required for Rust build scripts." >&2
    echo "Please install build-essential:" >&2
    echo "  sudo apt-get update" >&2
    echo "  sudo apt-get install -y build-essential" >&2
    exit 1
fi

case "$ABI" in
    armv8|arm64)
        FLAVOR="armv8"
        ;;
    armv7|arm32)
        FLAVOR="armv7"
        ;;
    all)
        FLAVOR="all"
        ;;
    core)
        FLAVOR="core"
        ;;
    *)
        echo "Error: Unknown ABI '$ABI'. Valid options: armv8, armv7, all, core" >&2
        exit 1
        ;;
esac

case "$BUILD_TYPE" in
    debug|Debug|DEBUG)
        BUILD_TYPE="Debug"
        ;;
    release|Release|RELEASE)
        BUILD_TYPE="Release"
        ;;
    *)
        echo "Error: Unknown build type '$BUILD_TYPE'. Valid options: debug, release" >&2
        exit 1
        ;;
esac

TASK="assemble${FLAVOR^}${BUILD_TYPE^}"

echo "Building $FLAVOR $BUILD_TYPE..."
echo "Clearing configuration cache..."
./gradlew --stop 2>/dev/null || true
rm -rf .gradle/configuration-cache 2>/dev/null || true
echo "Running: ./gradlew --configuration-cache $TASK"

./gradlew --configuration-cache "$TASK"

echo "Build completed successfully!"
echo "APK location: app/build/outputs/apk/$FLAVOR/$BUILD_TYPE/"

