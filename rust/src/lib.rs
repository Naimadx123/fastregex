use jni::objects::{JByteBuffer, JClass, JIntArray, JLongArray, ReleaseMode};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;

use once_cell::sync::Lazy;
use parking_lot::RwLock;
use regex::bytes::Regex;
use slab::Slab;
use std::sync::Arc;

static REGEX_CACHE: Lazy<moka::sync::Cache<String, Arc<Regex>>> = Lazy::new(|| {
    // Global cache to avoid re-compiling regexes across multiple instances.
    moka::sync::Cache::builder()
        .build()
});

// A handle table mapping jlong identifiers back to their compiled regex instances.
static HANDLES: Lazy<RwLock<Slab<Arc<Regex>>>> = Lazy::new(|| RwLock::new(Slab::with_capacity(1024)));

// Converts a 1-based jlong handle into a 0-based slab index.
fn handle_to_index(handle: jlong) -> Option<usize> {
    if handle <= 0 { None } else { Some((handle as usize) - 1) }
}

fn get_regex_from_handle(handle: jlong) -> Option<Arc<Regex>> {
    let idx = handle_to_index(handle)?;
    // Use a read lock to retrieve the regex instance from the global table.
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
            // Compile and cache the regex if not already present.
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
    // Return a 1-based handle to avoid 0 being a valid handle.
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
    // Acquire a write lock to remove the instance and free up the slot.
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
    direct_buf: JByteBuffer,
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

    // Access buffer metadata once to avoid redundant JNI calls.
    let base_ptr = match env.get_direct_buffer_address(&direct_buf) {
        Ok(p) => p,
        Err(_) => {
            throw_iae(&mut env, "Buffer is not a DirectByteBuffer");
            return 0;
        }
    };
    let cap = match env.get_direct_buffer_capacity(&direct_buf) {
        Ok(c) => c as usize,
        Err(_) => {
            throw_iae(&mut env, "Failed to read DirectByteBuffer capacity");
            return 0;
        }
    };

    let off_u = offset as usize;
    let ln_u = len as usize;

    // A single check that covers both negative input and potential overflow.
    if off_u > cap || ln_u > cap - off_u {
        throw_iae(&mut env, "offset/len out of bounds");
        return 0;
    }

    // SAFETY: Bounds were validated above.
    let slice = unsafe { std::slice::from_raw_parts(base_ptr.add(off_u), ln_u) };

    if re.is_match(slice) { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_me_naimad_fastregex_FastRegex_batchMatchesUtf8Direct(
    mut env: JNIEnv,
    _cls: JClass,
    handle: jlong,
    data_buf: JByteBuffer,
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

    // Pull buffer metadata once for the whole batch.
    let base_ptr = match env.get_direct_buffer_address(&data_buf) {
        Ok(p) => p,
        Err(_) => {
            throw_iae(&mut env, "dataBuf is not a DirectByteBuffer");
            return;
        }
    };
    let cap = match env.get_direct_buffer_capacity(&data_buf) {
        Ok(c) => c as usize,
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

    // SAFETY: data_buf is a DirectByteBuffer, so base_ptr and cap remain valid for the call.
    let data: &[u8] = unsafe { std::slice::from_raw_parts(base_ptr, cap) };

    unsafe {
        let offsets_auto = match env.get_array_elements(&offsets, ReleaseMode::NoCopyBack) {
            Ok(a) => a,
            Err(_) => {
                throw_iae(&mut env, "Failed to get offsets array elements");
                return;
            }
        };
        let lengths_auto = match env.get_array_elements(&lengths, ReleaseMode::NoCopyBack) {
            Ok(a) => a,
            Err(_) => {
                throw_iae(&mut env, "Failed to get lengths array elements");
                return;
            }
        };
        let mut out_bits_auto = match env.get_array_elements(&out_bits, ReleaseMode::CopyBack) {
            Ok(a) => a,
            Err(_) => {
                throw_iae(&mut env, "Failed to get outBits array elements");
                return;
            }
        };

        // Accessing as slices helps the compiler optimize and potentially vectorize the loop.
        let offsets_slice = &*offsets_auto;
        let lengths_slice = &*lengths_auto;
        let out_bits_slice = &mut *out_bits_auto;

        // Process matches in 64-bit chunks to match the long[] bitset layout.
        for (word_idx, (off_chunk, len_chunk)) in offsets_slice.chunks(64).zip(lengths_slice.chunks(64)).enumerate() {
            let mut word = 0i64;
            for (bit_idx, (&off, &ln)) in off_chunk.iter().zip(len_chunk.iter()).enumerate() {
                let off_u = off as usize;
                let ln_u = ln as usize;

                if off_u <= cap && ln_u <= cap - off_u {
                    // SAFETY: Values are validated to be within data bounds.
                    let slice = data.get_unchecked(off_u..off_u + ln_u);
                    if re.is_match(slice) {
                        word |= 1i64 << bit_idx;
                    }
                }
            }
            // SAFETY: word_idx is guaranteed to be within bounds.
            *out_bits_slice.get_unchecked_mut(word_idx) = word;
        }
    }
}