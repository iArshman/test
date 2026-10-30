
extern crate libc;

use jni::sys::{jboolean, JNI_FALSE, JNI_TRUE};
use goldberg::goldberg_string;
use std::{thread, time};
use log::warn;
use std::fs;
use std::process;
use crate::CHECKSUMS;
use std::ffi::CString;
use std::str;

fn check_for_debugger() {
    loop {
        let status = fs::read_to_string("/proc/self/status").unwrap_or_default();
        if !status.contains("TracerPid:\t0") {
            if should_abort_on_tamper() {
                process::abort();
            } else {
                warn!("TracerPid detected; skipping abort under hooked environment");
                return;
            }
        }

        let maps = fs::read_to_string("/proc/self/maps").unwrap_or_default();
        if maps.contains("frida") || maps.contains("gumjs") || maps.contains("gdb") {
            if should_abort_on_tamper() {
                process::abort();
            } else {
                warn!("Debugger markers detected; skipping abort under hooked environment");
                return;
            }
        }

        thread::sleep(time::Duration::from_secs(5));
    }
}

pub fn start_anti_debug_thread() {
    thread::spawn(move || {
        check_for_debugger();
    });
    thread::spawn(move || {
        verify_library_checksum();
    });
}

fn get_abi() -> Option<String> {
    let key = CString::new("ro.product.cpu.abi").unwrap();
    let mut value = [0 as libc::c_char; 92];
    let len = unsafe { libc::__system_property_get(key.as_ptr(), value.as_mut_ptr()) };
    if len > 0 {
        let value_slice = unsafe { std::slice::from_raw_parts(value.as_ptr() as *const u8, len as usize) };
        let value_str = str::from_utf8(value_slice).unwrap();
        Some(value_str.to_string())
    } else {
        None
    }
}

fn verify_library_checksum() {
    loop {
        if let Some(path) = get_library_path() {
            if let Ok(data) = fs::read(path) {
                let checksum = crc32fast::hash(&data);
                if let Some(abi) = get_abi() {
                    if let Some(expected_checksum) = CHECKSUMS.lock().unwrap().get(&abi) {
                        if checksum != *expected_checksum as u32 {
                            if should_abort_on_tamper() {
                                process::abort();
                            } else {
                                warn!("Checksum mismatch; skipping abort under hooked environment");
                                return;
                            }
                        }
                    }
                }
            }
        }
        thread::sleep(time::Duration::from_secs(60));
    }
}

#[allow(dead_code)]
pub fn verify_key(key: &str) -> bool {
    // Transformed key verification
    let transformed_key = key.chars().rev().collect::<String>();
    let expected_key = goldberg_string!("qd84R1hT6p7Mk").chars().rev().collect::<String>();
    transformed_key == expected_key
}

// this function will be called from the JNI, it will verify the key and if it's correct, it will return true
// otherwise it will return false
#[allow(dead_code)]
pub fn jni_verify_key(key: &str) -> jboolean {
    if verify_key(key) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
fn get_library_path() -> Option<String> {
    let maps = std::fs::read_to_string("/proc/self/maps").ok()?;
    for line in maps.lines() {
        if line.ends_with("libpurrfect.so") {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() > 5 {
                return Some(parts[5].to_string());
            }
        }
    }
    None
}

fn should_abort_on_tamper() -> bool {
    let maps = fs::read_to_string("/proc/self/maps").unwrap_or_default();
    let markers = ["lspatch", "lsposed", "xposed", "zygisk", "riru"];
    !markers.iter().any(|marker| maps.contains(marker))
}
