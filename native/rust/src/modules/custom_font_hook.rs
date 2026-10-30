use std::{cell::Cell, ffi::{CStr, CString}};
use nix::libc::{self, c_uint};
use crate::{config, def_hook, dobby_hook_sym};

thread_local! {
    static FONT_REDIRECT_IN_PROGRESS: Cell<bool> = const { Cell::new(false) };
}

fn should_redirect_font(pathname: &str) -> bool {
    let normalized = pathname.replace('\\', "/");
    let file_name = normalized.rsplit('/').next().unwrap_or(&normalized).to_ascii_lowercase();
    let is_font_file = file_name.ends_with(".ttf")
        || file_name.ends_with(".ttc")
        || file_name.ends_with(".otf");
    let is_system_font_path = normalized.starts_with("/system/fonts/")
        || normalized.starts_with("/product/fonts/")
        || normalized.starts_with("/system_ext/fonts/")
        || normalized.starts_with("/vendor/fonts/");

    is_system_font_path && is_font_file && (
        file_name.contains("emoji")
            || file_name == "noto_color_emoji.ttf"
            || file_name == "samsungcoloremoji.ttf"
            || file_name == "coloremojifont.ttf"
            || file_name == "coloros_color_emoji.ttf"
    )
}

fn open_custom_font_fd(flags: i32, mode: c_uint) -> Option<i32> {
    let font_path = config::native_config().custom_emoji_font_path.clone()?;

    match CString::new(font_path.clone()) {
        Ok(c_font_path) => {
            let fd = FONT_REDIRECT_IN_PROGRESS.with(|guard| {
                let was_active = guard.replace(true);
                let fd = unsafe { libc::openat(libc::AT_FDCWD, c_font_path.as_ptr() as *const libc::c_char, flags, mode) };
                guard.set(was_active);
                fd
            });
            if fd >= 0 {
                debug!("redirected emoji font open to {}", font_path);
                Some(fd)
            } else {
                if config::native_config().debug_font_redirect {
                    panic!("Failed to open custom emoji font: {}", font_path);
                }
                debug!("failed to open custom emoji font path (fd={}): {}", fd, font_path);
                None
            }
        }
        Err(_) => {
            warn!("custom emoji font path contains null byte, using fallback system font");
            None
        }
    }
}

def_hook!(
    open_hook,
    i32,
    |path: *const u8, flags: i32, mode: c_uint| {
        if FONT_REDIRECT_IN_PROGRESS.with(|guard| guard.get()) {
            return open_hook_original.unwrap()(path, flags, mode);
        }

        if !path.is_null() {
            if let Ok(pathname) = unsafe { CStr::from_ptr(path as *const libc::c_char) }.to_str() {
                if should_redirect_font(pathname) {
                    if let Some(fd) = open_custom_font_fd(flags, mode) {
                        return fd;
                    }
                }
            }
        }

        open_hook_original.unwrap()(path, flags, mode)
    }
);

def_hook!(
    openat_hook,
    i32,
    |dirfd: i32, path: *const u8, flags: i32, mode: c_uint| {
        if FONT_REDIRECT_IN_PROGRESS.with(|guard| guard.get()) {
            return openat_hook_original.unwrap()(dirfd, path, flags, mode);
        }

        if !path.is_null() {
            if let Ok(pathname) = unsafe { CStr::from_ptr(path as *const libc::c_char) }.to_str() {
                if should_redirect_font(pathname) {
                    if let Some(fd) = open_custom_font_fd(flags, mode) {
                        return fd;
                    }
                }
            }
        }

        openat_hook_original.unwrap()(dirfd, path, flags, mode)
    }
);

pub fn init() {
    if config::native_config().custom_emoji_font_path.is_none() {
        return;
    }

    dobby_hook_sym!("libc.so", "open", open_hook);
    dobby_hook_sym!("libc.so", "openat", openat_hook);
}
