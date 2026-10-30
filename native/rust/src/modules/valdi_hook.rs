#![allow(dead_code, unused_imports)]

use super::util::valdi_utils::{ValdiModule, ModuleTag};
use std::{collections::HashMap, ffi::c_void, sync::Mutex};
use jni::{objects::JString, JNIEnv};
use once_cell::sync::Lazy;
use crate::{common, config, def_hook, dobby_hook, dobby_hook_sym, util::get_jni_string};

static AASSET_MAP: Lazy<Mutex<HashMap<usize, Vec<u8>>>> = Lazy::new(|| Mutex::new(HashMap::new()));
static LOADER_DATA: Mutex<Option<String>> = Mutex::new(None);

def_hook!(
    aasset_get_length,
    i32,
    |arg0: *mut c_void| {
        if let Some(buffer) = AASSET_MAP.lock().unwrap().get(&(arg0 as usize)) {
            return buffer.len() as i32;
        }
        if let Some(original) = aasset_get_length_original {
            return original(arg0);
        }
        0
    }
);

def_hook!(
    aasset_get_buffer,
    *const c_void,
    |arg0: *mut c_void| {
        if let Some(buffer) = AASSET_MAP.lock().unwrap().get(&(arg0 as usize)) {
            return buffer.as_ptr() as *const c_void;
        }
        if let Some(original) = aasset_get_buffer_original {
            return original(arg0);
        }
        std::ptr::null()
    }
);

def_hook!(
    aasset_manager_open,
    *mut c_void,
    |arg0: *mut c_void, arg1: *const u8, arg2: i32| {
        let original_fn = match aasset_manager_open_original {
            Some(f) => f,
            None => return std::ptr::null_mut(),
        };

        let handle = original_fn(arg0, arg1, arg2);
        if handle.is_null() {
            return handle;
        }

        let path_cstr = unsafe { std::ffi::CStr::from_ptr(arg1 as *const std::os::raw::c_char) };
        let path = path_cstr.to_str().unwrap_or_default();
        
        // Only target compressed Valdi bridge observables
        if path.ends_with(".zst") && path.contains("bridge_observables") {
            let get_buffer_fn = match aasset_get_buffer_original {
                Some(f) => f,
                None => return handle,
            };
            let get_length_fn = match aasset_get_length_original {
                Some(f) => f,
                None => return handle,
            };

            let asset_buffer = get_buffer_fn(handle);
            let asset_length = get_length_fn(handle);
            
            if asset_buffer.is_null() || asset_length <= 0 {
                return handle;
            }

            let loader_data = match LOADER_DATA.lock().unwrap().clone() {
                Some(data) => data,
                None => {
                    warn!("Valdi loader data not yet initialized for {}", path);
                    return handle;
                }
            };

            let archive_buffer: Vec<u8> = unsafe { 
                std::slice::from_raw_parts(asset_buffer as *const u8, asset_length as usize).to_vec() 
            };
            
            let decompressed = match zstd::stream::decode_all(&archive_buffer[..]) {
                Ok(data) => data,
                Err(e) => {
                    error!("Failed to decompress Valdi archive {}: {}", path, e);
                    return handle;
                }
            };

            let valdi_module = match ValdiModule::parse(decompressed) {
                Ok(module) => module,
                Err(e) => {
                    error!("Failed to parse Valdi module {}: {}", path, e);
                    return handle;
                }
            };

            let mut tags = valdi_module.get_tags();
            let mut found = false;

            for (tag1, tag2) in tags.iter_mut() {
                let name = tag1.to_string().unwrap_or_default();
                if name.ends_with("src/utils/converter.js") {
                    let mut hooked_content = loader_data.as_bytes().to_vec();
                    hooked_content.extend_from_slice(tag2.get_buffer());
                    *tag2 = ModuleTag::new(true, hooked_content);
                    found = true;
                    debug!("Valdi loader prepended to {}", name);
                    break;
                }
            }

            if found {
                let compressed = valdi_module.to_bytes();
                match zstd::stream::encode_all(&compressed[..], 3) {
                    Ok(compressed_data) => {
                         AASSET_MAP.lock().unwrap().insert(handle as usize, compressed_data);
                    },
                    Err(e) => error!("Failed to re-compress Valdi module: {}", e),
                }
            }
        }
        handle
    }
);

def_hook!(
    aasset_close,
    (),
    |handle: *mut c_void| {
        AASSET_MAP.lock().unwrap().remove(&(handle as usize));
        if let Some(original) = aasset_close_original {
            original(handle);
        }
    }
);

pub extern "system" fn set_valdi_loader(mut env: JNIEnv, _: *mut c_void, code: JString) {
    if let Ok(new_code) = get_jni_string(&mut env, code) {
        LOADER_DATA.lock().unwrap().replace(new_code);
    }
}

pub fn init() {
    if !config::native_config().valdi_hooks {
        return
    }

    dobby_hook_sym!("libandroid.so", "AAsset_getBuffer", aasset_get_buffer);
    dobby_hook_sym!("libandroid.so", "AAsset_getLength", aasset_get_length);
    dobby_hook_sym!("libandroid.so", "AAsset_close", aasset_close);
    dobby_hook_sym!("libandroid.so", "AAssetManager_open", aasset_manager_open);
}
