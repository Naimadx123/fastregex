use jni::objects::{JByteBuffer, JClass, JObject, JIntArray, JLongArray};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;

use once_cell::sync::Lazy;
use parking_lot::RwLock;
use regex::Regex;
use slab::Slab;
use std::sync::Arc;

static REGEX_CACHE: Lazy<moka::sync::Cache<String, Arc<Regex>>> = Lazy::new(|| {
    moka::sync::Cache::builder()
        .build()
});

static HANDLES: Lazy<RwLock<Slab<Arc<Regex>>>> = Lazy::new(|| RwLock::new(Slab::with_capacity(1024)));

fn handle_to_index(handle: jlong) -> Option<usize> {
    if handle <= 0 { None } else { Some((handle as usize) - 1) }
}

fn get_regex_from_handle(handle: jlong) -> Option<Arc<Regex>> {
    let idx = handle_to_index(handle)?;
    let table = HANDLES.read();
    table.get(idx).cloned()
}

fn throw_iae(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/IllegalArgumentException", msg);
}

#[no_mangle]
pub extern "system" fn Java_me_naimad_fastregex_FastRegex_compile(
    mut env: JNIEnv,
    _cls: JClass,
    pattern_obj: jni::objects::JString,
) -> jlong {
    let pattern: String = match env.get_string(&pattern_obj) {
        Ok(s) => s.into(),
        Err(_) => {
            throw_iae(&mut env, "Failed to read pattern string");
            return 0;
        }
    };

    let re_arc: Arc<Regex> = match REGEX_CACHE.get(&pattern) {
        Some(v) => v,
        None => {
            let compiled = match Regex::new(&pattern) {
                Ok(r) => Arc::new(r),
                Err(e) => {
                    throw_iae(&mut env, &format!("Invalid regex: {e}"));
                    return 0;
                }
            };
            REGEX_CACHE.insert(pattern, compiled.clone());
            compiled
        }
    };

    let mut table = HANDLES.write();
    let idx = table.insert(re_arc);
    (idx as jlong) + 1
}

#[no_mangle]
pub extern "system" fn Java_me_naimad_fastregex_FastRegex_release(
    mut env: JNIEnv,
    _cls: JClass,
    handle: jlong,
) {
    let Some(idx) = handle_to_index(handle) else {
        throw_iae(&mut env, "Invalid handle");
        return;
    };
    let mut table = HANDLES.write();
    if table.contains(idx) {
        table.remove(idx);
    }
}

#[no_mangle]
pub extern "system" fn Java_me_naimad_fastregex_FastRegex_matchesUtf8Direct(
    mut env: JNIEnv,
    _cls: JClass,
    handle: jlong,
    direct_buf: JObject,
    offset: jint,
    len: jint,
) -> jboolean {
    let re = match get_regex_from_handle(handle) {
        Some(r) => r,
        None => {
            throw_iae(&mut env, "Unknown/expired handle");
            return 0;
        }
    };

    let base_ptr = match env.get_direct_buffer_address(<&JByteBuffer>::from(&direct_buf)) {
        Ok(p) => p,
        Err(_) => {
            throw_iae(&mut env, "Buffer is not a DirectByteBuffer");
            return 0;
        }
    };
    let cap = match env.get_direct_buffer_capacity(<&JByteBuffer>::from(&direct_buf)) {
        Ok(c) => c,
        Err(_) => {
            throw_iae(&mut env, "Failed to read DirectByteBuffer capacity");
            return 0;
        }
    };

    let off = offset as isize;
    let ln = len as isize;
    if offset < 0 || len < 0 || off + ln > cap as isize {
        throw_iae(&mut env, "offset/len out of bounds");
        return 0;
    }

    let slice = unsafe {
        std::slice::from_raw_parts(base_ptr.add(offset as usize) as *const u8, len as usize)
    };

    let text = match std::str::from_utf8(slice) {
        Ok(s) => s,
        Err(_) => {
            return 0;
        }
    };

    if re.is_match(text) { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_me_naimad_fastregex_FastRegex_batchMatchesUtf8Direct(
    mut env: JNIEnv,
    _cls: JClass,
    handle: jlong,
    data_buf: JObject,
    offsets: JIntArray,
    lengths: JIntArray,
    out_bits: JLongArray,
) {
    let re = match get_regex_from_handle(handle) {
        Some(r) => r,
        None => {
            throw_iae(&mut env, "Unknown/expired handle");
            return;
        }
    };

    let base_ptr = match env.get_direct_buffer_address(<&JByteBuffer>::from(&data_buf)) {
        Ok(p) => p,
        Err(_) => {
            throw_iae(&mut env, "dataBuf is not a DirectByteBuffer");
            return;
        }
    };
    let cap = match env.get_direct_buffer_capacity(<&JByteBuffer>::from(&data_buf)) {
        Ok(c) => c,
        Err(_) => {
            throw_iae(&mut env, "Failed to read dataBuf capacity");
            return;
        }
    };

    let n = match env.get_array_length(&offsets) {
        Ok(v) => v as usize,
        Err(_) => {
            throw_iae(&mut env, "Failed to read offsets length");
            return;
        }
    };

    let n_len = match env.get_array_length(&lengths) {
        Ok(v) => v as usize,
        Err(_) => {
            throw_iae(&mut env, "Failed to read lengths length");
            return;
        }
    };

    if n != n_len {
        throw_iae(&mut env, "offsets.length != lengths.length");
        return;
    }

    let out_len = match env.get_array_length(&out_bits) {
        Ok(v) => v as usize,
        Err(_) => {
            throw_iae(&mut env, "Failed to read outBits length");
            return;
        }
    };

    let needed_words = (n + 63) / 64;
    if out_len < needed_words {
        throw_iae(&mut env, "outBits too small");
        return;
    }

    {
        const CHUNK: usize = 256;
        let zeros = [0i64; CHUNK];
        let mut i = 0usize;
        while i < needed_words {
            let take = (needed_words - i).min(CHUNK);
            if env.set_long_array_region(&out_bits, i as i32, &zeros[..take]).is_err() {
                throw_iae(&mut env, "Failed to clear outBits");
                return;
            }
            i += take;
        }
    }

    const CHUNK: usize = 256;
    let mut off_buf = [0i32; CHUNK];
    let mut len_buf = [0i32; CHUNK];

    let mut base_index = 0usize;
    while base_index < n {
        let take = (n - base_index).min(CHUNK);

        if env.get_int_array_region(&offsets, base_index as i32, &mut off_buf[..take]).is_err() {
            throw_iae(&mut env, "Failed to read offsets");
            return;
        }
        if env.get_int_array_region(&lengths, base_index as i32, &mut len_buf[..take]).is_err() {
            throw_iae(&mut env, "Failed to read lengths");
            return;
        }

        for j in 0..take {
            let idx = base_index + j;
            let off = off_buf[j];
            let ln = len_buf[j];

            if off < 0 || ln < 0 || (off as isize) + (ln as isize) > cap as isize {
                continue;
            }

            let slice = unsafe {
                std::slice::from_raw_parts(base_ptr.add(off as usize) as *const u8, ln as usize)
            };

            let Ok(text) = std::str::from_utf8(slice) else {
                continue;
            };

            if re.is_match(text) {
                let word_index = idx / 64;
                let bit_index = idx % 64;

                let mut word = [0i64; 1];
                if env.get_long_array_region(&out_bits, word_index as i32, &mut word).is_ok() {
                    word[0] |= 1i64 << bit_index;
                    let _ = env.set_long_array_region(&out_bits, word_index as i32, &word);
                }
            }
        }

        base_index += take;
    }
}