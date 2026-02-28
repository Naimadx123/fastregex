use jni::objects::{JByteBuffer, JClass, JIntArray, JLongArray, ReleaseMode};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;

use once_cell::sync::Lazy;
use parking_lot::RwLock;
use regex::bytes::Regex;
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

    // Optimization: Pull address and capacity once to minimize JNI calls.
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

    // Fast unsigned bounds check covering both negative values and overflows.
    if off_u > cap || ln_u > cap - off_u {
        throw_iae(&mut env, "offset/len out of bounds");
        return 0;
    }

    // Optimization: Direct pointer addition for slice creation to avoid extra slicing overhead.
    // Safety: direct_buf is a DirectByteBuffer, base_ptr is its starting address, cap is its capacity.
    // off_u and ln_u are validated to be within [0, cap].
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

    // Optimization: Pull address and capacity once.
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

    // Optimization: Create the data slice once for the whole batch.
    // Safety: data_buf is a DirectByteBuffer, base_ptr is its starting address, cap is its capacity.
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

        // Use slices for faster access and to enable compiler optimizations like auto-vectorization.
        let offsets_slice = &*offsets_auto;
        let lengths_slice = &*lengths_auto;
        let out_bits_slice = &mut *out_bits_auto;

        // Optimization: Use chunks(64) to iterate over bitset words, simplifying loops and bit logic.
        // Using zip() for offsets and lengths allows the compiler to optimize access patterns.
        for (word_idx, (off_chunk, len_chunk)) in offsets_slice.chunks(64).zip(lengths_slice.chunks(64)).enumerate() {
            let mut word = 0i64;
            for (bit_idx, (&off, &ln)) in off_chunk.iter().zip(len_chunk.iter()).enumerate() {
                // Optimization: Fast unsigned bounds check covers negative values and overflow.
                let off_u = off as usize;
                let ln_u = ln as usize;

                if off_u <= cap && ln_u <= cap - off_u {
                    // Safety: off_u and ln_u are within data bounds.
                    let slice = data.get_unchecked(off_u..off_u + ln_u);
                    if re.is_match(slice) {
                        word |= 1i64 << bit_idx;
                    }
                }
            }
            // word_idx < needed_words <= out_bits_slice.len() is guaranteed.
            *out_bits_slice.get_unchecked_mut(word_idx) = word;
        }
    }
}