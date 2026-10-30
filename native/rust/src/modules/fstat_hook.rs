
use std::fs;

use nix::libc;

use crate::{config::{self, native_config}, def_hook, dobby_hook_sym};

def_hook!(
    fstat_hook,
    i32, 
    |fd: i32, statbuf: *mut libc::stat| {
        if let Ok(link) = fs::read_link("/proc/self/fd/".to_owned() + &fd.to_string()) {
            let link_str = link.to_string_lossy();
            let config = native_config();
            if config.disable_metrics && link_str.contains("files/blizzardv2/queues") {
                if libc::unlink((link_str.to_string() + "\0").as_ptr()) == -1 {
                    warn!("Failed to unlink {}", link_str);
                }
                return -1;
            }
    
            if config.disable_bitmoji {
                if link_str.contains("com.snap.file_manager_4_SCContent") {
                    return -1;
                }
                if link_str.contains("/files/file_manager/") {
                    let lower = link_str.to_lowercase();
                    if lower.contains("bitmoji") {
                        return -1;
                    }
                }
            }
        }

        fstat_hook_original.unwrap()(fd, statbuf)
    }
);

pub fn init() {
    let config = config::native_config();
    if config.disable_metrics || config.disable_bitmoji {
        dobby_hook_sym!("libc.so", "fstat", fstat_hook);
    }
}