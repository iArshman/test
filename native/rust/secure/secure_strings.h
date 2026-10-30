// Minimal C interface to expose obfuscated keyword lists to Rust (FFI)
#pragma once
#include <stddef.h>

// Returns a pointer to a NUL-separated blob of decoded strings and sets total length in bytes.
// The memory is owned by the C module and valid for the lifetime of the process.
const char* ss_get_detection_blob(size_t* out_len);
const char* ss_get_hermod_dup(size_t* out_len);
const char* ss_get_risk_block_blob(size_t* out_len);

