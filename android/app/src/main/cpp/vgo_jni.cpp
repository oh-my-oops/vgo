#include "vgo_bridge.hpp"

#include <jni.h>
#include <mutex>
#include <string>
#include <vector>

namespace {

std::mutex g_mutex;
vgo_android::VgoBridge g_bridge;

// Safe UTF-16 (jstring) -> standard UTF-8 conversion (handles emoji/supplementary planes cleanly).
std::string jstringToUtf8(JNIEnv* env, jstring js) {
    if (!js) return "";
    jsize len = env->GetStringLength(js);
    if (len <= 0) return "";
    const jchar* chars = env->GetStringChars(js, nullptr);
    if (!chars) return "";
    std::string out;
    out.reserve(static_cast<size_t>(len) * 2);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + (((cp - 0xD800) << 10) | (low - 0xDC00));
                ++i;
            }
        }
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(js, chars);
    return out;
}

// Safe standard UTF-8 -> UTF-16 (jstring) conversion.
jstring utf8ToJstring(JNIEnv* env, const std::string& s) {
    std::vector<jchar> u16;
    u16.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        uint32_t cp = 0;
        if (c < 0x80) {
            cp = c;
            i += 1;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < s.size()) {
            cp = ((c & 0x1F) << 6) | (static_cast<unsigned char>(s[i + 1]) & 0x3F);
            i += 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < s.size()) {
            cp = ((c & 0x0F) << 12) |
                 ((static_cast<unsigned char>(s[i + 1]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(s[i + 2]) & 0x3F);
            i += 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < s.size()) {
            cp = ((c & 0x07) << 18) |
                 ((static_cast<unsigned char>(s[i + 1]) & 0x3F) << 12) |
                 ((static_cast<unsigned char>(s[i + 2]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(s[i + 3]) & 0x3F);
            i += 4;
        } else {
            cp = 0xFFFD;
            i += 1;
        }
        if (cp <= 0xFFFF) {
            u16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            u16.push_back(static_cast<jchar>(0xD800 | (cp >> 10)));
            u16.push_back(static_cast<jchar>(0xDC00 | (cp & 0x3FF)));
        }
    }
    return env->NewString(u16.data(), static_cast<jsize>(u16.size()));
}

jobjectArray makeSnapshotArray(JNIEnv* env, const vgo_android::Snapshot& snap) {
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(6, strCls, nullptr);
    env->SetObjectArrayElement(arr, 0, utf8ToJstring(env, snap.empty ? "1" : "0"));
    env->SetObjectArrayElement(arr, 1, utf8ToJstring(env, snap.display));
    env->SetObjectArrayElement(arr, 2, utf8ToJstring(env, snap.raw));
    env->SetObjectArrayElement(arr, 3, utf8ToJstring(env, snap.altDisplay));
    env->SetObjectArrayElement(arr, 4, utf8ToJstring(env, snap.showingRaw ? "1" : "0"));
    env->SetObjectArrayElement(arr, 5, utf8ToJstring(env, snap.corrected ? "1" : "0"));
    return arr;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeInit(
    JNIEnv* env, jobject, jstring baseDir, jstring userDir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_bridge.init(jstringToUtf8(env, baseDir), jstringToUtf8(env, userDir));
}

JNIEXPORT void JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeSetConfig(
    JNIEnv*, jobject, jboolean vniMode, jboolean oldStyleTone, jboolean wAlwaysU) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_bridge.setConfig(vniMode == JNI_TRUE, oldStyleTone == JNI_TRUE, wAlwaysU == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeSetApp(JNIEnv* env, jobject, jstring packageName) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_bridge.setApp(jstringToUtf8(env, packageName));
}

JNIEXPORT jboolean JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeIsEmpty(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_bridge.empty() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeAcceptsChar(
    JNIEnv*, jobject, jchar ch, jboolean isNumpad) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (ch > 0x7F) return JNI_FALSE;
    return g_bridge.acceptsChar(static_cast<char>(ch), isNumpad == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeKey(JNIEnv* env, jobject, jchar ch) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto snap = g_bridge.key(static_cast<char>(ch));
    return makeSnapshotArray(env, snap);
}

JNIEXPORT jboolean JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeBackspace(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_bridge.backspace() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeFlipAndLearn(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_bridge.flipAndLearn() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeFinalize(JNIEnv*, jobject, jint boundaryCodePoint) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_bridge.finalize(static_cast<uint32_t>(boundaryCodePoint));
}

JNIEXPORT jobjectArray JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeTryRevertCorrection(
    JNIEnv* env, jobject, jstring textBeforeCursor) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto res = g_bridge.tryRevertCorrection(jstringToUtf8(env, textBeforeCursor));
    if (!res.reverted) return nullptr;
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(2, strCls, nullptr);
    env->SetObjectArrayElement(arr, 0, utf8ToJstring(env, res.committedToDelete));
    env->SetObjectArrayElement(arr, 1, utf8ToJstring(env, res.replacement));
    return arr;
}

JNIEXPORT void JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeClearCanRevert(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_bridge.clearCanRevert();
}

JNIEXPORT void JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeReset(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_bridge.reset();
}

JNIEXPORT void JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeClearContext(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_bridge.clearContext();
}

JNIEXPORT jobjectArray JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeSnapshot(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return makeSnapshotArray(env, g_bridge.snapshot());
}

JNIEXPORT void JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeReloadUserFiles(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_bridge.reloadUserFiles();
}

JNIEXPORT void JNICALL
Java_dev_vgo_ime_engine_VgoNative_nativeForgetEntry(
    JNIEnv* env, jobject, jstring relativePath, jstring line) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_bridge.forgetEntry(jstringToUtf8(env, relativePath), jstringToUtf8(env, line));
}

} // extern "C"
