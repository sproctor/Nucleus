#include <jni.h>
#include <windows.h>
#include <string>

// ---- Global state ----
static JavaVM* g_jvm = nullptr;
static jobject g_bridgeRef = nullptr;  // Global ref to NativeWindowsHotKeyBridge
static jmethodID g_onHotKeyMethod = nullptr;
static DWORD g_threadId = 0;
static HANDLE g_thread = nullptr;
static volatile bool g_running = false;

// Custom WM message to signal register/unregister/shutdown
#define WM_HOTKEY_REGISTER   (WM_USER + 100)
#define WM_HOTKEY_UNREGISTER (WM_USER + 101)
#define WM_HOTKEY_SHUTDOWN   (WM_USER + 102)

struct HotKeyRequest {
    jlong id;
    int modifiers;
    int keyCode;
};

static JNIEnv* attachCurrentThread() {
    JNIEnv* env = nullptr;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
    }
    return env;
}

static void fireHotKey(jlong id, int keyCode, int modifiers) {
    JNIEnv* env = attachCurrentThread();
    if (env && g_bridgeRef && g_onHotKeyMethod) {
        env->CallStaticVoidMethod(
            env->GetObjectClass(g_bridgeRef),
            g_onHotKeyMethod,
            id,
            static_cast<jint>(keyCode),
            static_cast<jint>(modifiers)
        );
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
}

static DWORD WINAPI messageLoopThread(LPVOID) {
    // Create a message queue for this thread
    MSG msg;
    PeekMessage(&msg, nullptr, 0, 0, PM_NOREMOVE);

    g_running = true;

    while (GetMessage(&msg, nullptr, 0, 0)) {
        if (msg.message == WM_HOTKEY) {
            jlong id = static_cast<jlong>(msg.wParam);
            int mods = LOWORD(msg.lParam);
            int vk = HIWORD(msg.lParam);
            fireHotKey(id, vk, mods);
        } else if (msg.message == WM_HOTKEY_REGISTER) {
            auto* req = reinterpret_cast<HotKeyRequest*>(msg.lParam);
            if (req) {
                RegisterHotKey(nullptr, static_cast<int>(req->id), req->modifiers, req->keyCode);
                delete req;
            }
        } else if (msg.message == WM_HOTKEY_UNREGISTER) {
            jlong id = static_cast<jlong>(msg.wParam);
            UnregisterHotKey(nullptr, static_cast<int>(id));
        } else if (msg.message == WM_HOTKEY_SHUTDOWN) {
            break;
        }
    }

    g_running = false;

    JNIEnv* env = attachCurrentThread();
    if (env && g_bridgeRef) {
        env->DeleteGlobalRef(g_bridgeRef);
        g_bridgeRef = nullptr;
    }
    g_jvm->DetachCurrentThread();

    return 0;
}

// ---- JNI exports ----

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_globalhotkey_windows_NativeWindowsHotKeyBridge_nativeInit(
    JNIEnv* env, jclass clazz
) {
    if (g_running) return nullptr; // Already initialized

    // Cache the bridge class and callback method
    g_bridgeRef = env->NewGlobalRef(clazz);
    g_onHotKeyMethod = env->GetStaticMethodID(clazz, "onHotKey", "(JII)V");
    if (!g_onHotKeyMethod) {
        env->DeleteGlobalRef(g_bridgeRef);
        g_bridgeRef = nullptr;
        return env->NewStringUTF("Failed to find onHotKey callback method");
    }

    // Start message loop thread
    g_thread = CreateThread(nullptr, 0, messageLoopThread, nullptr, 0, &g_threadId);
    if (!g_thread) {
        env->DeleteGlobalRef(g_bridgeRef);
        g_bridgeRef = nullptr;
        return env->NewStringUTF("Failed to create message loop thread");
    }

    // Wait for the message queue to be created
    int retries = 100;
    while (!g_running && retries-- > 0) {
        Sleep(10);
    }

    return nullptr; // success
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_globalhotkey_windows_NativeWindowsHotKeyBridge_nativeRegister(
    JNIEnv* env, jclass, jlong id, jint modifiers, jint keyCode
) {
    if (!g_running || g_threadId == 0) {
        return env->NewStringUTF("Not initialized");
    }

    auto* req = new HotKeyRequest{id, static_cast<int>(modifiers), static_cast<int>(keyCode)};
    if (!PostThreadMessage(g_threadId, WM_HOTKEY_REGISTER, 0, reinterpret_cast<LPARAM>(req))) {
        delete req;
        return env->NewStringUTF("Failed to post register message to hotkey thread");
    }

    return nullptr; // success
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_nucleus_globalhotkey_windows_NativeWindowsHotKeyBridge_nativeUnregister(
    JNIEnv* env, jclass, jlong id
) {
    if (!g_running || g_threadId == 0) {
        return env->NewStringUTF("Not initialized");
    }

    if (!PostThreadMessage(g_threadId, WM_HOTKEY_UNREGISTER, static_cast<WPARAM>(id), 0)) {
        return env->NewStringUTF("Failed to post unregister message to hotkey thread");
    }

    return nullptr; // success
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_globalhotkey_windows_NativeWindowsHotKeyBridge_nativeShutdown(
    JNIEnv*, jclass
) {
    if (!g_running || g_threadId == 0) return;

    PostThreadMessage(g_threadId, WM_HOTKEY_SHUTDOWN, 0, 0);

    if (g_thread) {
        WaitForSingleObject(g_thread, 5000);
        CloseHandle(g_thread);
        g_thread = nullptr;
    }

    g_threadId = 0;
}

} // extern "C"
