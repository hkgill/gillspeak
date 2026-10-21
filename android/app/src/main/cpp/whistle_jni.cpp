#include <jni.h>
#include <needle.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdlib>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
// The engine reads the model in place and has no unload API. Keep this read-only
// mapping for the process lifetime; unlinking a downloaded model remains safe.
void* model_mapping = MAP_FAILED;
bool loaded = false;

void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

std::string utf8(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Cannot read speech settings");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT void JNICALL
Java_dev_hkgill_gillspeak_WhistleNative_load(JNIEnv* env, jobject, jstring path) {
    try {
        if (loaded) return;
        // Defence in depth: use only the native API (no Python telemetry wrapper).
        setenv("NEEDLE_TELEMETRY", "0", 1);
        setenv("DO_NOT_TRACK", "1", 1);
        const auto file = utf8(env, path);
        const int fd = open(file.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) throw std::runtime_error("Cannot open Whistle model");
        struct stat info{};
        if (fstat(fd, &info) != 0 || info.st_size != 16919407) {
            close(fd);
            throw std::runtime_error("Invalid Whistle model size");
        }
        void* mapping = mmap(nullptr, info.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (mapping == MAP_FAILED) throw std::runtime_error("Cannot map Whistle model");
        const int result = needle_load(static_cast<const unsigned char*>(mapping), info.st_size);
        if (result < 0) {
            const std::string error = needle_last_error();
            munmap(mapping, info.st_size);
            throw std::runtime_error(error);
        }
        model_mapping = mapping;
        loaded = true;
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) fail(env, error.what());
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_hkgill_gillspeak_WhistleNative_transcribe(JNIEnv* env, jobject, jfloatArray samples, jbyteArray keywords) {
    try {
        if (!loaded) throw std::runtime_error("Whistle model is not loaded");
        const int count = env->GetArrayLength(samples);
        if (count <= 0) throw std::runtime_error("Empty recording");
        std::vector<float> pcm(count);
        env->GetFloatArrayRegion(samples, 0, count, pcm.data());
        if (env->ExceptionCheck()) return nullptr;
        const int keyword_bytes = env->GetArrayLength(keywords);
        std::string bias(keyword_bytes, '\0');
        env->GetByteArrayRegion(keywords, 0, keyword_bytes, reinterpret_cast<jbyte*>(bias.data()));
        if (env->ExceptionCheck()) return nullptr;
        // JSON includes timings. Allow ample output for long, windowed dictations.
        std::vector<char> out(1024 * 1024);
        if (needle_transcribe(pcm.data(), count, nullptr, bias.c_str(), 0, out.data(), out.size()) < 0)
            throw std::runtime_error(needle_last_error());
        const auto length = std::char_traits<char>::length(out.data());
        auto result = env->NewByteArray(static_cast<jsize>(length));
        if (result) env->SetByteArrayRegion(result, 0, length, reinterpret_cast<const jbyte*>(out.data()));
        return result;
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) fail(env, error.what());
        return nullptr;
    }
}
