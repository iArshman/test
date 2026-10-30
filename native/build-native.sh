#!/usr/bin/env bash
set -euo pipefail
set -x

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
RUST_DIR="$SCRIPT_DIR/rust"
TOOLCHAIN="${RUST_TOOLCHAIN:-}"
OMVLL_VERSION="${OMVLL_VERSION:-1.4.1}"
OMVLL_LINUX_ASSET="${OMVLL_LINUX_ASSET:-omvll_v1-4-1_linux_2025-10-01T09.33.59.tar.gz}"

if [ ! -d "$RUST_DIR" ]; then
  echo "Unable to locate Rust sources under $RUST_DIR" >&2
  exit 1
fi

HOST_TAG=""
HOST_LIB_SUBDIR=""
NDK_TOOLCHAIN_DIR=""
NDK_LIB_DIR=""

detect_host_tag() {
  local os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  local arch="$(uname -m)"
  case "$os" in
    linux*)
      HOST_TAG="linux-x86_64"
      HOST_LIB_SUBDIR="lib64"
      ;;
    darwin*)
      if [[ "$arch" == "arm64" || "$arch" == "aarch64" ]]; then
        HOST_TAG="darwin-arm64"
      else
        HOST_TAG="darwin-x86_64"
      fi
      HOST_LIB_SUBDIR="lib"
      ;;
    msys*|mingw*|cygwin*)
      HOST_TAG="windows-x86_64"
      HOST_LIB_SUBDIR="lib"
      ;;
    *)
      echo "Unsupported host platform: $os ($arch)" >&2
      exit 1
      ;;
  esac
}

ensure_ndk_for_host() {
  local try_home="$1"
  local prebuilt_root="$try_home/toolchains/llvm/prebuilt"
  local requested_tag="$HOST_TAG"
  local -a candidate_tags=("$requested_tag")
  if [[ "$requested_tag" == "darwin-arm64" ]]; then
    candidate_tags+=("darwin-x86_64")
  fi

  local candidate_tag
  for candidate_tag in "${candidate_tags[@]}"; do
    local bin_dir="$prebuilt_root/$candidate_tag/bin"
    local lib_root="$prebuilt_root/$candidate_tag"
    local lib_dir="$lib_root/$HOST_LIB_SUBDIR"
    if [ ! -d "$lib_dir" ]; then
      if [ -d "$lib_root/lib64" ]; then
        lib_dir="$lib_root/lib64"
      elif [ -d "$lib_root/lib" ]; then
        lib_dir="$lib_root/lib"
      fi
    fi
    if [ -d "$bin_dir" ] && [ -d "$lib_dir" ]; then
      HOST_TAG="$candidate_tag"
      ANDROID_NDK_HOME="$try_home"
      NDK_TOOLCHAIN_DIR="$bin_dir"
      NDK_LIB_DIR="$lib_dir"
      if [ "$candidate_tag" != "$requested_tag" ]; then
        echo "Falling back to NDK host toolchain: $candidate_tag (requested $requested_tag)" >&2
      fi
      echo "$bin_dir"
      return 0
    fi
  done
  return 1
}

provision_linux_ndk() {
  local tmp_ndk="${ANDROID_NDK_TMP:-$HOME/.cache/purrfect-ndk}"
  local extracted_dir="$tmp_ndk/android-ndk-r28b"
  if [ -d "$extracted_dir/toolchains/llvm/prebuilt" ]; then
    if ensure_ndk_for_host "$extracted_dir"; then
      ANDROID_NDK_HOME="$extracted_dir"
      return
    fi
  fi
  rm -rf "$tmp_ndk"
  mkdir -p "$tmp_ndk"
  curl -fsSL https://dl.google.com/android/repository/android-ndk-r28b-linux.zip -o "$tmp_ndk/ndk.zip"
  unzip -oq "$tmp_ndk/ndk.zip" -d "$tmp_ndk"
  ANDROID_NDK_HOME="$(find "$tmp_ndk" -maxdepth 1 -type d -name 'android-ndk-r*' | head -n1)"
  # Permissions are preserved during extraction; no additional chmod required.
}

ensure_omvll_bundle() {
  local archive_url="${OMVLL_ARCHIVE_URL:-https://github.com/open-obfuscator/o-mvll/releases/download/${OMVLL_VERSION}/${OMVLL_LINUX_ASSET}}"
  OMVLL_CACHE_DIR="$SCRIPT_DIR/.omvll/$OMVLL_VERSION"
  OMVLL_PLUGIN_PATH="$OMVLL_CACHE_DIR/omvll-ndk.so"
  local tmp_tar=""

  download_omvll_bundle() {
    rm -rf "$OMVLL_CACHE_DIR"
    mkdir -p "$OMVLL_CACHE_DIR"
    tmp_tar=$(mktemp)
    curl -fL "$archive_url" -o "$tmp_tar"
    tar -xzf "$tmp_tar" -C "$OMVLL_CACHE_DIR"
    rm -f "$tmp_tar"
  }

  if [ ! -f "$OMVLL_PLUGIN_PATH" ]; then
    echo "Downloading O-MVLL bundle from $archive_url"
    download_omvll_bundle
  fi

  if [ ! -f "$OMVLL_PLUGIN_PATH" ]; then
    local found_plugin
    found_plugin=$(find "$OMVLL_CACHE_DIR" -type f -name 'omvll-ndk.so' | head -n1 || true)
    if [ -n "$found_plugin" ] && [ -f "$found_plugin" ]; then
      OMVLL_PLUGIN_PATH="$found_plugin"
    else
      echo "Failed to locate O-MVLL plugin under $OMVLL_PLUGIN_PATH" >&2
      echo "Contents:" >&2
      ls -la "$OMVLL_CACHE_DIR" >&2 || true
      exit 1
    fi
  fi

  if [ -z "${OMVLL_PYTHONPATH:-}" ]; then
    local py_root
    py_root=$(find "$OMVLL_CACHE_DIR" -maxdepth 1 -type d -name 'Python-*' | head -n1 || true)
    if [ -z "$py_root" ] || [ ! -d "$py_root/Lib" ]; then
      echo "OMVLL cache is incomplete. Re-downloading bundle..." >&2
      download_omvll_bundle
      py_root=$(find "$OMVLL_CACHE_DIR" -maxdepth 1 -type d -name 'Python-*' | head -n1 || true)
      if [ -z "$py_root" ] || [ ! -d "$py_root/Lib" ]; then
        echo "Unable to determine Python stdlib directory inside the O-MVLL bundle" >&2
        echo "OMVLL_CACHE_DIR: $OMVLL_CACHE_DIR" >&2
        echo "Contents:" >&2
        ls -la "$OMVLL_CACHE_DIR" >&2 || true
        exit 1
      fi
    fi
    export OMVLL_PYTHONPATH="$py_root/Lib"
  fi

  local plugin_tmp="${OMVLL_PLUGIN_TMP:-/tmp/purrfect-omvll}"
  local plugin_target="$plugin_tmp/$OMVLL_VERSION/omvll-ndk.so"
  mkdir -p "$(dirname "$plugin_target")"
  cp -f "$OMVLL_PLUGIN_PATH" "$plugin_target"
  OMVLL_PLUGIN_PATH="$plugin_target"
  echo "OMVLL plugin path: $OMVLL_PLUGIN_PATH"

  export OMVLL_HOME="$OMVLL_CACHE_DIR"
}

append_rustflags() {
  local var_name="$1"
  local current_value="${!var_name:-}"
  local addition="-Clink-arg=-fpass-plugin=$OMVLL_PLUGIN_PATH"
  if [[ "$current_value" == *"$addition"* ]]; then
    return
  fi
  if [ -n "$current_value" ]; then
    export "$var_name"="$current_value $addition"
  else
    export "$var_name"="$addition"
  fi
}


# Detect host platform early so we know which NDK prebuilt we need
detect_host_tag

# Default toolchain selection (favor GNU toolchain on Windows to avoid MSVC link.exe requirement)
if [[ -z "$TOOLCHAIN" ]]; then
  if [[ "$HOST_TAG" == windows-* ]]; then
    TOOLCHAIN="stable-x86_64-pc-windows-gnu"
  else
    TOOLCHAIN="stable"
  fi
fi

# OMVLL can be disabled for environments where the pass plugin is unstable.
USE_OMVLL="${USE_OMVLL:-}"
IS_WSL=false
if grep -qi microsoft /proc/version 2>/dev/null; then
  IS_WSL=true
fi

# Default to disabling OMVLL on macOS CI where LLVM pass plugin loading is unstable.
if [ -z "$USE_OMVLL" ]; then
  if [[ "$HOST_TAG" == darwin-* && "${CI:-}" == "true" ]]; then
    USE_OMVLL=false
    echo "Disabling OMVLL on macOS CI to avoid LLVM pass plugin crashes." >&2
  else
    USE_OMVLL=true
  fi
fi

if [[ "$USE_OMVLL" == "true" && "$HOST_TAG" == windows-* && "$IS_WSL" != true ]]; then
  echo "OMVLL requires a Linux/WSL environment. Please run the build via WSL (e.g., set BASH_PATH=C:\\Windows\\System32\\bash.exe)." >&2
  exit 1
fi

# Install rustup if it is not already available
if ! command -v rustup >/dev/null 2>&1; then
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain "$TOOLCHAIN"
fi

# Add cargo to PATH when rustup installed the toolchain locally
if [ -f "$HOME/.cargo/env" ]; then
  # shellcheck source=/dev/null
  source "$HOME/.cargo/env"
fi

# Ensure the requested toolchain (default: stable) is installed
rustup toolchain install "$TOOLCHAIN" --profile minimal

if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
  if ensure_ndk_for_host "$ANDROID_NDK_HOME"; then
    :
  fi
fi

if [ -z "$NDK_TOOLCHAIN_DIR" ]; then
  if [[ "$HOST_TAG" != linux-* ]]; then
    echo "ANDROID_NDK_HOME must point to a valid NDK for $HOST_TAG" >&2
    exit 1
  fi
  echo "Provisioning Linux NDK r28b inside container..." >&2
  provision_linux_ndk
ensure_ndk_for_host "$ANDROID_NDK_HOME"
fi

if [ -z "$NDK_TOOLCHAIN_DIR" ]; then
  echo "ERROR: Could not find a $HOST_TAG NDK toolchain bin directory." >&2
  exit 1
fi

# Normalize NDK paths for msys/git-bash on Windows so PATH lookups work
if [[ "$HOST_TAG" == windows-* ]]; then
  if command -v cygpath >/dev/null 2>&1; then
    NDK_TOOLCHAIN_DIR=$(cygpath -u "$NDK_TOOLCHAIN_DIR")
    NDK_LIB_DIR=$(cygpath -u "$NDK_LIB_DIR")
  else
    # best-effort slash normalization
    NDK_TOOLCHAIN_DIR=${NDK_TOOLCHAIN_DIR//\\//}
    NDK_LIB_DIR=${NDK_LIB_DIR//\\//}
  fi
fi

echo "Using NDK toolchain at: $NDK_TOOLCHAIN_DIR"
ls -l "$NDK_TOOLCHAIN_DIR/aarch64-linux-android28-clang" || true
export PATH="$NDK_TOOLCHAIN_DIR:$PATH"

if [ -z "${LD_LIBRARY_PATH:-}" ]; then
  export LD_LIBRARY_PATH="$NDK_LIB_DIR"
else
  export LD_LIBRARY_PATH="$NDK_LIB_DIR:$LD_LIBRARY_PATH"
fi

if [[ "$HOST_TAG" == darwin-* ]]; then
  if [ -z "${DYLD_LIBRARY_PATH:-}" ]; then
    export DYLD_LIBRARY_PATH="$NDK_LIB_DIR"
  else
    export DYLD_LIBRARY_PATH="$NDK_LIB_DIR:$DYLD_LIBRARY_PATH"
  fi
fi

if [[ "$USE_OMVLL" == "true" ]]; then
  ensure_omvll_bundle
  append_rustflags CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS
  append_rustflags CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_RUSTFLAGS

  if [ -z "${OMVLL_CONFIG:-}" ]; then
    export OMVLL_CONFIG="$SCRIPT_DIR/omvll_config.py"
  fi

  if [ -z "${OMVLL_PYTHONPATH:-}" ] || [ ! -d "$OMVLL_PYTHONPATH" ]; then
    echo "OMVLL_PYTHONPATH is not configured with a valid stdlib" >&2
    exit 1
  fi
else
  echo "OMVLL disabled for this build (USE_OMVLL=$USE_OMVLL)." >&2
fi

rustup target add --toolchain "$TOOLCHAIN" "$1"

HOST_CC=""
if command -v gcc >/dev/null 2>&1; then
  HOST_CC="gcc"
elif command -v clang >/dev/null 2>&1; then
  HOST_CC="clang"
elif command -v cc >/dev/null 2>&1; then
  HOST_CC="cc"
else
  echo "Error: No host C compiler found. Build scripts require gcc, clang, or cc." >&2
  echo "Install build-essential: sudo apt-get update && sudo apt-get install -y build-essential" >&2
  exit 1
fi

export CC="$HOST_CC"
export HOST_CC="$HOST_CC"

case "$1" in
  aarch64-linux-android)
    CLANG_BIN="$NDK_TOOLCHAIN_DIR/aarch64-linux-android28-clang"
    AR_BIN="$NDK_TOOLCHAIN_DIR/llvm-ar"
    if [[ "$HOST_TAG" == windows-* ]]; then
      CLANG_BIN="${CLANG_BIN}.cmd"
      AR_BIN="${AR_BIN}.exe"
    fi
    export CC_aarch64_linux_android="$CLANG_BIN"
    export AR_aarch64_linux_android="$AR_BIN"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG_BIN"
    export CFLAGS_aarch64_linux_android="-DZSTD_LEGACY_SUPPORT=0 -DZSTD_DISABLE_ASM"
    ;;
  armv7-linux-androideabi)
    CLANG_BIN="$NDK_TOOLCHAIN_DIR/armv7a-linux-androideabi28-clang"
    AR_BIN="$NDK_TOOLCHAIN_DIR/llvm-ar"
    if [[ "$HOST_TAG" == windows-* ]]; then
      CLANG_BIN="${CLANG_BIN}.cmd"
      AR_BIN="${AR_BIN}.exe"
    fi
    export CC_armv7_linux_androideabi="$CLANG_BIN"
    export AR_armv7_linux_androideabi="$AR_BIN"
    export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$CLANG_BIN"
    export CFLAGS_armv7_linux_androideabi="-DZSTD_LEGACY_SUPPORT=0 -DZSTD_DISABLE_ASM -mfloat-abi=softfp -mfpu=neon"
    ;;
  *)
    echo "Unknown target $1" >&2
    ;;
esac

cd "$RUST_DIR"

# macOS CI runners may inject DYLD override variables that break Rust/cargo
# processes with libc++abi symbol shim errors and bus error 10.
if [[ "$HOST_TAG" == darwin-* ]]; then
  unset DYLD_INSERT_LIBRARIES
  unset DYLD_LIBRARY_PATH
  unset DYLD_FRAMEWORK_PATH
  unset DYLD_ROOT_PATH
fi

rustup run "$TOOLCHAIN" cargo build --release --target "$1"
