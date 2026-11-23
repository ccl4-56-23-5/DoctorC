#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <stdbool.h>
#include <string.h>
#include <sys/stat.h>

static bool read_path(JNIEnv *env, jbyteArray bytes, char path[PATH_MAX]) {
    jsize size = (*env)->GetArrayLength(env, bytes);
    if (size <= 0 || size >= PATH_MAX) return false;
    (*env)->GetByteArrayRegion(env, bytes, 0, size, (jbyte *)path);
    if ((*env)->ExceptionCheck(env) || memchr(path, '\0', (size_t)size) != NULL) return false;
    path[size] = '\0';
    return true;
}

JNIEXPORT jlongArray JNICALL
Java_com_wirelessalien_zipxtract_lan_NativeFileTimes_read(JNIEnv *env, jobject self, jbyteArray bytes) {
    (void)self;
    char path[PATH_MAX];
    struct stat file;
    if (!read_path(env, bytes, path) || lstat(path, &file) != 0) return NULL;
    if (!S_ISREG(file.st_mode) && !S_ISDIR(file.st_mode)) return NULL;

    const jlong values[] = {file.st_mtim.tv_sec, file.st_mtim.tv_nsec,
                           file.st_atim.tv_sec, file.st_atim.tv_nsec};
    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_wirelessalien_zipxtract_lan_NativeFileTimes_write(JNIEnv *env, jobject self, jbyteArray bytes,
        jlong modified_seconds, jlong modified_nanos, jlong accessed_seconds, jlong accessed_nanos) {
    (void)self;
    char path[PATH_MAX];
    if (!read_path(env, bytes, path)) return EINVAL;
    if ((jlong)(time_t)modified_seconds != modified_seconds ||
            (accessed_nanos >= 0 && (jlong)(time_t)accessed_seconds != accessed_seconds)) return EOVERFLOW;
    if (modified_nanos < 0 || modified_nanos >= 1000000000 ||
            accessed_nanos < -1 || accessed_nanos >= 1000000000) return EINVAL;

    const struct timespec times[2] = {
        { .tv_sec = (time_t)accessed_seconds, .tv_nsec = accessed_nanos < 0 ? UTIME_OMIT : (long)accessed_nanos },
        { .tv_sec = (time_t)modified_seconds, .tv_nsec = (long)modified_nanos }
    };
    return utimensat(AT_FDCWD, path, times, AT_SYMLINK_NOFOLLOW) == 0 ? 0 : errno;
}
