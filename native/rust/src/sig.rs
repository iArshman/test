use std::{fs::File, io::{Read, Seek, SeekFrom}, os::unix::io::AsRawFd, sync::Mutex};

use nix::libc;
use procfs::process::MMPermissions;

use crate::mapped_lib::MappedLib;


static SIGNATURE_CACHE: Mutex<Vec<(String, Vec<usize>)>> = Mutex::new(Vec::new());

pub fn add_signatures(signatures: Vec<(String, Vec<usize>)>) {
    SIGNATURE_CACHE.lock().unwrap().extend(signatures);
}

pub fn get_signatures() -> Vec<(String, Vec<usize>)> {
    SIGNATURE_CACHE.lock().unwrap().clone()
}

fn read_region_bytes(start: usize, size: usize) -> Option<Vec<u8>> {
    let file = File::open("/proc/self/mem").ok();
    if let Some(file) = file {
        let fd = file.as_raw_fd();
        let mut buffer = vec![0u8; size];
        let mut offset = 0usize;

        while offset < size {
            let read = unsafe {
                libc::pread(
                    fd,
                    buffer[offset..].as_mut_ptr() as *mut libc::c_void,
                    (size - offset) as libc::size_t,
                    (start + offset) as libc::off_t,
                )
            };
            if read < 0 {
                debug!(
                    "Failed to read /proc/self/mem at {:#x}: {}",
                    start,
                    std::io::Error::last_os_error()
                );
                return None;
            }
            if read == 0 {
                break;
            }
            offset += read as usize;
        }

        if offset == size {
            return Some(buffer);
        }
        debug!("Short read from /proc/self/mem at {:#x}: {} < {}", start, offset, size);
    }

    None
}

fn read_region_bytes_from_file(region: &crate::mapped_lib::MappedRegion) -> Option<Vec<u8>> {
    let size = (region.end - region.start) as usize;
    let mut file = match File::open(&region.path) {
        Ok(file) => file,
        Err(error) => {
            warn!("Failed to open mapped file {:?}: {}", region.path, error);
            return None;
        }
    };
    if let Err(error) = file.seek(SeekFrom::Start(region.offset)) {
        warn!(
            "Failed to seek mapped file {:?} to {:#x}: {}",
            region.path,
            region.offset,
            error
        );
        return None;
    }
    let mut buffer = vec![0u8; size];
    if let Err(error) = file.read_exact(&mut buffer) {
        warn!(
            "Failed to read mapped file {:?} at {:#x} for {} bytes: {}",
            region.path,
            region.offset,
            size,
            error
        );
        return None;
    }
    Some(buffer)
}

pub fn find_signatures(module_base: usize, bytes_buffer: &[u8], pattern: &str, once: bool) -> Vec<usize> {
    let mut results = Vec::new();
    let mut bytes = Vec::new();
    let mut mask = Vec::new();
    let mut i = 0;

    if let Some(cache) = SIGNATURE_CACHE.lock().unwrap().iter().find(|(sig, _)| sig == pattern) {
        return cache.1.clone().into_iter().map(|offset| module_base + offset).collect();
    }

    while i < pattern.len() {
        if pattern.chars().nth(i).unwrap() == '?' {
            bytes.push(0);
            mask.push('?');
        } else {
            bytes.push(u8::from_str_radix(&pattern[i..i+2], 16).unwrap());
            mask.push('x');
        }
        i += 3;
    }

    let mut i = 0;
    let size = bytes_buffer.len().saturating_sub(bytes.len());
    while i < size {
        let mut found = true;
        let mut j = 0;

        while j < bytes.len() {
            if mask[j] == '?' || bytes[j] == bytes_buffer[i + j] {
                j += 1;
                continue;
            }
            found = false;
            break;
        }
        if found {
            if once {
                SIGNATURE_CACHE.lock().unwrap().push((pattern.to_string(), vec![i]));
                return vec![module_base + i];
            }
            results.push(module_base + i);
        }
        i += 1;
    }

    SIGNATURE_CACHE.lock().unwrap().push((pattern.to_string(), results.clone()));
    results
}

pub fn find_signature_executable(mapped_lib: &MappedLib, pattern: &str) -> Option<usize> {
    let executable_regions = mapped_lib.regions.iter().filter(|region| {
        region.perms.contains(MMPermissions::EXECUTE)
    }).collect::<Vec<_>>();

    for region in executable_regions {
        let size = (region.end - region.start) as usize;
        let module_base = region.start as usize;

        if size > 0 {
            let bytes_buffer = match read_region_bytes(module_base, size) {
                Some(buffer) => buffer,
                None => match read_region_bytes_from_file(region) {
                    Some(buffer) => {
                        debug!(
                            "Read executable region from mapped file {:?} offset {:#x} after /proc/self/mem fallback: {:#x} - {:#x}",
                            region.path,
                            region.offset,
                            region.start,
                            region.end
                        );
                        buffer
                    }
                    None => {
                        warn!("Unable to read executable region: {:#x} - {:#x}", region.start, region.end);
                        continue;
                    }
                },
            };
            let results = find_signatures(module_base, &bytes_buffer, pattern, true);

            if results.is_empty() {
                warn!("Signature not found in region: {:#x} - {:#x}", region.start, region.end);
            } else {
                debug!("Found {} results in region: {:#x} - {:#x}", results.len(), region.start, region.end);
                return Some(results[0]);
            }
        }
    }

    None
}

pub fn find_signature(mapped_lib: &MappedLib, _arm64_pattern: &str, _arm64_offset: i64, _arm32_pattern: &str, _arm32_offset: i64) -> Option<usize> {
    #[cfg(target_arch = "aarch64")]
    {
        return find_signature_executable(mapped_lib, _arm64_pattern).map(|address| (address as i64 + _arm64_offset) as usize);
    }
    #[cfg(target_arch = "arm")]
    {
        return find_signature_executable(mapped_lib, _arm32_pattern).map(|address| (address as i64 + _arm32_offset) as usize);
    }
}
