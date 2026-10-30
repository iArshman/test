use std::ffi::CStr;

#[allow(improper_ctypes)]
extern "C" {
    fn ss_get_detection_blob(out_len: *mut usize) -> *const u8;
    fn ss_get_hermod_dup(out_len: *mut usize) -> *const u8;
    fn ss_get_risk_block_blob(out_len: *mut usize) -> *const u8;
}

fn blob_to_vec(blob_ptr: *const u8, len: usize) -> Vec<String> {
    if blob_ptr.is_null() || len == 0 { return Vec::new(); }
    // Safety: blob is a NUL-separated set of C strings
    let slice = unsafe { std::slice::from_raw_parts(blob_ptr, len) };
    let mut result = Vec::new();
    let mut start = 0usize;
    for i in 0..slice.len() {
        if slice[i] == 0 { // NUL
            if i > start {
                let cstr = unsafe { CStr::from_bytes_with_nul_unchecked(&slice[start..=i]) };
                result.push(cstr.to_string_lossy().into_owned());
            }
            start = i + 1;
        }
    }
    result
}

pub fn get_detection_keywords() -> Vec<String> {
    let mut len: usize = 0;
    let ptr = unsafe { ss_get_detection_blob(&mut len as *mut usize) };
    blob_to_vec(ptr, len)
}

pub fn get_hermod_dup() -> String {
    let mut len: usize = 0;
    let ptr = unsafe { ss_get_hermod_dup(&mut len as *mut usize) };
    if ptr.is_null() || len == 0 { return String::new(); }
    let slice = unsafe { std::slice::from_raw_parts(ptr, len) };
    String::from_utf8_lossy(slice).into_owned()
}

pub fn get_risk_block_list() -> Vec<String> {
    let mut len: usize = 0;
    let ptr = unsafe { ss_get_risk_block_blob(&mut len as *mut usize) };
    blob_to_vec(ptr, len)
}

