use regex::bytes::Regex;
use std::ffi::CStr;
use std::os::raw::c_char;
use std::sync::{Arc, LazyLock};

static REGEX_CACHE: LazyLock<moka::sync::Cache<String, Arc<Regex>>> = LazyLock::new(|| {
    moka::sync::Cache::builder().build()
});

#[unsafe(no_mangle)]
pub unsafe extern "C" fn fastregex_compile(pattern: *const c_char) -> i64 {
    if pattern.is_null() {
        return 0;
    }

    let c_str = {
        // SAFETY: caller must provide a valid NUL-terminated C string.
        unsafe { CStr::from_ptr(pattern) }
    };

    let pattern_str = match c_str.to_str() {
        Ok(s) => s,
        Err(_) => return 0,
    };

    let re_arc: Arc<Regex> = if let Some(cached) = REGEX_CACHE.get(pattern_str) {
        cached
    } else {
        match Regex::new(pattern_str) {
            Ok(r) => {
                let arc = Arc::new(r);
                REGEX_CACHE.insert(pattern_str.to_string(), Arc::clone(&arc));
                arc
            }
            Err(_) => return 0,
        }
    };

    // The handle is a pointer to a Boxed Arc.
    // Each call to compile returns a new handle that must be released.
    // The handle keeps the Regex alive even if it's evicted from the cache.
    let handle = Box::into_raw(Box::new(re_arc));
    handle as i64
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn fastregex_release(handle: i64) {
    if handle == 0 {
        return;
    }
    // SAFETY: handle must be a valid pointer returned by fastregex_compile and not released yet.
    unsafe {
        let _ = Box::from_raw(handle as *mut Arc<Regex>);
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn fastregex_matches_utf8(
    handle: i64,
    data_ptr: *const u8,
    len: usize,
) -> i32 {
    if handle == 0 || data_ptr.is_null() {
        return 0;
    }

    // SAFETY: handle must be a valid pointer to an Arc<Regex>.
    let re_ptr = handle as *const Arc<Regex>;
    let re = unsafe { &**re_ptr };

    let slice = {
        // SAFETY: caller must provide a valid pointer to at least `len` bytes.
        unsafe { std::slice::from_raw_parts(data_ptr, len) }
    };

    if re.is_match(slice) { 1 } else { 0 }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn fastregex_batch_matches_utf8(
    handle: i64,
    data_ptr: *const u8,
    data_len: usize,
    offsets: *const i32,
    lengths: *const i32,
    out_bits: *mut i64,
    num: usize,
) {
    if handle == 0 || data_ptr.is_null() || offsets.is_null() || lengths.is_null() || out_bits.is_null() {
        return;
    }

    // SAFETY: handle must be a valid pointer to an Arc<Regex>.
    let re_ptr = handle as *const Arc<Regex>;
    let re = unsafe { &**re_ptr };

    let data = {
        // SAFETY: caller must provide a valid pointer to at least `data_len` bytes.
        unsafe { std::slice::from_raw_parts(data_ptr, data_len) }
    };

    let offsets_slice = {
        // SAFETY: caller must provide `num` valid i32 entries.
        unsafe { std::slice::from_raw_parts(offsets, num) }
    };

    let lengths_slice = {
        // SAFETY: caller must provide `num` valid i32 entries.
        unsafe { std::slice::from_raw_parts(lengths, num) }
    };

    let out_words = num.div_ceil(64);

    let out_bits_slice = {
        // SAFETY: caller must provide enough space for ceil(num / 64) i64 words.
        unsafe { std::slice::from_raw_parts_mut(out_bits, out_words) }
    };

    out_bits_slice.iter_mut().enumerate().for_each(|(word_idx, word_out)| {
        let mut word = 0i64;
        let start_idx = word_idx * 64;
        let end_idx = std::cmp::min(start_idx + 64, num);

        for i in start_idx..end_idx {
            let off = offsets_slice[i];
            let ln = lengths_slice[i];

            if off < 0 || ln < 0 {
                continue;
            }

            let off_u = off as usize;
            let ln_u = ln as usize;

            if off_u <= data_len && ln_u <= data_len.saturating_sub(off_u) {
                let slice = &data[off_u..off_u + ln_u];
                if re.is_match(slice) {
                    word |= 1i64 << (i - start_idx);
                }
            }
        }

        *word_out = word;
    });
}
