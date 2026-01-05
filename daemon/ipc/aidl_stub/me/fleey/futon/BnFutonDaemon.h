/*
 * Auto-generated AIDL stub - BnFutonDaemon (Native Service Stub)
 * This is a simplified implementation for NDK Binder
 */

#ifndef AIDL_ME_FLEEY_FUTON_BN_FUTON_DAEMON_H
#define AIDL_ME_FLEEY_FUTON_BN_FUTON_DAEMON_H

#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/log.h>
#include <memory>
#include <vector>
#include <string>
#include <cstdio>
#include <cstring>

#define FUTON_TAG "futon_daemon"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, FUTON_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, FUTON_TAG, __VA_ARGS__)

// IBinder transaction constants (from android/binder_ibinder.h or frameworks/native)
namespace IBinder {
    constexpr transaction_code_t FIRST_CALL_TRANSACTION = 1;
    constexpr transaction_code_t PING_TRANSACTION = ('_' << 24) | ('P' << 16) | ('N' << 8) | 'G';  // 0x5f504e47
}

// String allocator callback for AParcel_readString
// This allocator uses std::string as the backing storage
//
// Per NDK docs and parcel.cpp source:
// - length == -1: null string, buffer parameter is nullptr (don't dereference!)
// - length > 0: allocate buffer of 'length' bytes, NDK will write UTF-8 string + null terminator
//
// CRITICAL: When length == -1, the 'buffer' parameter itself is nullptr, not a pointer to set!
inline bool StdStringAllocator(void *stringData, int32_t length, char **buffer) {
    if (!stringData) {
        LOGE("StdStringAllocator: null stringData");
        return false;
    }

    std::string *str = static_cast<std::string *>(stringData);

    LOGD("StdStringAllocator: length=%d, stringData=%p, buffer=%p", length, stringData, buffer);

    if (length < 0) {
        // Null string - buffer parameter is nullptr, just clear the string and return true
        // DO NOT dereference buffer here!
        str->clear();
        LOGD("StdStringAllocator: null string case, cleared string");
        return true;
    }

    if (!buffer) {
        LOGE("StdStringAllocator: null buffer pointer for non-null string");
        return false;
    }

    if (length == 0) {
        // Empty string - should not happen, but handle defensively
        str->clear();
        *buffer = nullptr;
        LOGD("StdStringAllocator: length=0 case");
        return true;
    }

    // length includes null terminator
    // We need a buffer of 'length' bytes for NDK to write UTF-8 string into
    str->resize(static_cast<size_t>(length));
    *buffer = &(*str)[0];
    LOGD("StdStringAllocator: resized to %d, buffer=%p", length, *buffer);
    return true;
}

// Helper function to read a string from parcel using std::string
inline binder_status_t ReadString(const AParcel *parcel, std::string *out) {
    if (!parcel) {
        LOGE("ReadString: null parcel");
        return STATUS_BAD_VALUE;
    }
    if (!out) {
        LOGE("ReadString: null out");
        return STATUS_BAD_VALUE;
    }
    out->clear();
    LOGD("ReadString: calling AParcel_readString");
    binder_status_t status = AParcel_readString(parcel, out, StdStringAllocator);
    LOGD("ReadString: status=%d, out->size()=%zu", status, out->size());
    if (status == STATUS_OK) {
        // After NDK writes, the string contains the content + null terminator
        // We need to remove the trailing null if present
        if (!out->empty() && out->back() == '\0') {
            out->pop_back();
            LOGD("ReadString: removed trailing null, new size=%zu", out->size());
        }
        LOGD("ReadString: result='%s'", out->c_str());
    }
    return status;
}

#include "DaemonStatus.h"
#include "FutonConfig.h"
#include "DetectionResult.h"
#include "SessionStatus.h"
#include "SystemStatus.h"
#include "ScreenshotResult.h"
#include "AuthenticateResult.h"
#include "CryptoHandshake.h"
#include "InputDeviceEntry.h"
#include "IStatusCallback.h"
#include "IBufferReleaseCallback.h"
#include "ipc/compat/binder_auto_utils.h"

namespace aidl::me::fleey::futon {

// Transaction codes matching IFutonDaemon.aidl (FIRST_CALL_TRANSACTION = 1)
// These MUST match the order of methods in the AIDL file exactly!
    enum class IFutonDaemonTransaction : int32_t {
        // Version & Capability (methods 0-2)
        GET_VERSION = 1,           // FIRST_CALL_TRANSACTION + 0
        GET_CAPABILITIES = 2,      // FIRST_CALL_TRANSACTION + 1
        GET_SYSTEM_STATUS = 3,     // FIRST_CALL_TRANSACTION + 2

        // Authentication (methods 3-6)
        GET_CHALLENGE = 4,         // FIRST_CALL_TRANSACTION + 3
        AUTHENTICATE = 5,          // FIRST_CALL_TRANSACTION + 4
        VERIFY_ATTESTATION = 6,    // FIRST_CALL_TRANSACTION + 5
        CHECK_SESSION = 7,         // FIRST_CALL_TRANSACTION + 6

        // Encrypted Channel (methods 7-10)
        INIT_CRYPTO_CHANNEL = 8,   // FIRST_CALL_TRANSACTION + 7
        SEND_CONTROL_MESSAGE = 9,  // FIRST_CALL_TRANSACTION + 8
        SEND_DATA_MESSAGE = 10,    // FIRST_CALL_TRANSACTION + 9
        ROTATE_CHANNEL_KEYS = 11,  // FIRST_CALL_TRANSACTION + 10

        // Callback Registration (methods 11-14)
        REGISTER_STATUS_CALLBACK = 12,           // FIRST_CALL_TRANSACTION + 11
        UNREGISTER_STATUS_CALLBACK = 13,         // FIRST_CALL_TRANSACTION + 12
        REGISTER_BUFFER_RELEASE_CALLBACK = 14,   // FIRST_CALL_TRANSACTION + 13
        UNREGISTER_BUFFER_RELEASE_CALLBACK = 15, // FIRST_CALL_TRANSACTION + 14

        // Configuration (methods 15-16)
        CONFIGURE = 16,            // FIRST_CALL_TRANSACTION + 15
        CONFIGURE_HOT_PATH = 17,   // FIRST_CALL_TRANSACTION + 16

        // Input Device Discovery (method 17)
        LIST_INPUT_DEVICES = 18,   // FIRST_CALL_TRANSACTION + 17

        // Perception (methods 18-20)
        GET_SCREENSHOT = 19,       // FIRST_CALL_TRANSACTION + 18
        RELEASE_SCREENSHOT = 20,   // FIRST_CALL_TRANSACTION + 19
        REQUEST_PERCEPTION = 21,   // FIRST_CALL_TRANSACTION + 20

        // Input Injection (methods 21-32)
        TAP = 22,                  // FIRST_CALL_TRANSACTION + 21
        LONG_PRESS = 23,           // FIRST_CALL_TRANSACTION + 22
        DOUBLE_TAP = 24,           // FIRST_CALL_TRANSACTION + 23
        SWIPE = 25,                // FIRST_CALL_TRANSACTION + 24
        SCROLL = 26,               // FIRST_CALL_TRANSACTION + 25
        PINCH = 27,                // FIRST_CALL_TRANSACTION + 26
        MULTI_TOUCH = 28,          // FIRST_CALL_TRANSACTION + 27
        INPUT_TEXT = 29,           // FIRST_CALL_TRANSACTION + 28
        PRESS_KEY = 30,            // FIRST_CALL_TRANSACTION + 29

        // System Actions (methods 33-38)
        PRESS_BACK = 31,           // FIRST_CALL_TRANSACTION + 30
        PRESS_HOME = 32,           // FIRST_CALL_TRANSACTION + 31
        PRESS_RECENTS = 33,        // FIRST_CALL_TRANSACTION + 32
        OPEN_NOTIFICATIONS = 34,   // FIRST_CALL_TRANSACTION + 33
        OPEN_QUICK_SETTINGS = 35,  // FIRST_CALL_TRANSACTION + 34
        LAUNCH_APP = 36,           // FIRST_CALL_TRANSACTION + 35
        LAUNCH_ACTIVITY = 37,      // FIRST_CALL_TRANSACTION + 36

        // Utility Actions (methods 39-42)
        WAIT = 38,                 // FIRST_CALL_TRANSACTION + 37
        SAVE_SCREENSHOT = 39,      // FIRST_CALL_TRANSACTION + 38
        REQUEST_INTERVENTION = 40, // FIRST_CALL_TRANSACTION + 39
        CALL = 41,                 // FIRST_CALL_TRANSACTION + 40

        // Automation Control (methods 43-45)
        START_HOT_PATH = 42,       // FIRST_CALL_TRANSACTION + 41
        STOP_AUTOMATION = 43,      // FIRST_CALL_TRANSACTION + 42
        EXECUTE_TASK = 44,         // FIRST_CALL_TRANSACTION + 43

        // Model Management (methods 46-47)
        RELOAD_MODELS = 45,        // FIRST_CALL_TRANSACTION + 44
        GET_MODEL_STATUS = 46,     // FIRST_CALL_TRANSACTION + 45

        // Debug APIs (methods 48-50)
        DEBUG_INJECT_TAP = 47,     // FIRST_CALL_TRANSACTION + 46
        DEBUG_INJECT_SWIPE = 48,   // FIRST_CALL_TRANSACTION + 47
        DEBUG_RUN_DETECTION = 49,  // FIRST_CALL_TRANSACTION + 48

        // Legacy Compatibility (method 51)
        GET_SCREENSHOT_BYTES = 50, // FIRST_CALL_TRANSACTION + 49
    };

// Capability flags
    enum class DaemonCapability : int32_t {
        NONE = 0,
        SCREEN_CAPTURE = 1 << 0,
        INPUT_INJECTION = 1 << 1,
        OBJECT_DETECTION = 1 << 2,
        OCR = 1 << 3,
        HOT_PATH = 1 << 4,
        DEBUG_STREAM = 1 << 5,
    };

    inline int32_t operator|(DaemonCapability a, DaemonCapability b) {
        return static_cast<int32_t>(a) | static_cast<int32_t>(b);
    }

    inline int32_t operator|(int32_t a, DaemonCapability b) {
        return a | static_cast<int32_t>(b);
    }

    class BnFutonDaemon {
    public:
        BnFutonDaemon();

        virtual ~BnFutonDaemon();

        // ========== Version & Capability ==========
        virtual ndk::ScopedAStatus getVersion(int32_t *_aidl_return) = 0;

        virtual ndk::ScopedAStatus getCapabilities(int32_t *_aidl_return) = 0;

        virtual ndk::ScopedAStatus getSystemStatus(SystemStatus *_aidl_return) = 0;

        // ========== Authentication ==========
        virtual ndk::ScopedAStatus getChallenge(std::vector<uint8_t> *_aidl_return) = 0;

        virtual ndk::ScopedAStatus authenticate(
                const std::vector<uint8_t> &signature,
                const std::string &instanceId,
                AuthenticateResult *_aidl_return) = 0;

        virtual ndk::ScopedAStatus verifyAttestation(
                const std::vector<std::vector<uint8_t>> &attestationChain) = 0;

        virtual ndk::ScopedAStatus checkSession(
                const std::string &instanceId,
                SessionStatus *_aidl_return) = 0;

        // ========== Encrypted Channel ==========
        virtual ndk::ScopedAStatus initCryptoChannel(
                const std::vector<uint8_t> &clientDhPublic,
                CryptoHandshake *_aidl_return) = 0;

        virtual ndk::ScopedAStatus sendControlMessage(
                const std::vector<uint8_t> &encryptedMessage,
                std::vector<uint8_t> *_aidl_return) = 0;

        virtual ndk::ScopedAStatus sendDataMessage(
                const std::vector<uint8_t> &encryptedData,
                std::vector<uint8_t> *_aidl_return) = 0;

        virtual ndk::ScopedAStatus rotateChannelKeys(
                CryptoHandshake *_aidl_return) = 0;

        // ========== Callback Registration ==========
        virtual ndk::ScopedAStatus registerStatusCallback(
                const std::shared_ptr<IStatusCallback> &callback) = 0;

        virtual ndk::ScopedAStatus unregisterStatusCallback(
                const std::shared_ptr<IStatusCallback> &callback) = 0;

        virtual ndk::ScopedAStatus registerBufferReleaseCallback(
                const std::shared_ptr<IBufferReleaseCallback> &callback) = 0;

        virtual ndk::ScopedAStatus unregisterBufferReleaseCallback(
                const std::shared_ptr<IBufferReleaseCallback> &callback) = 0;

        // ========== Configuration ==========
        virtual ndk::ScopedAStatus configure(const FutonConfig &config) = 0;

        virtual ndk::ScopedAStatus configureHotPath(const std::string &jsonRules) = 0;

        // ========== Perception ==========
        virtual ndk::ScopedAStatus getScreenshot(ScreenshotResult *_aidl_return) = 0;

        virtual ndk::ScopedAStatus releaseScreenshot(int32_t bufferId) = 0;

        virtual ndk::ScopedAStatus requestPerception(
                std::vector<DetectionResult> *_aidl_return) = 0;

        // ========== Input Injection ==========
        virtual ndk::ScopedAStatus tap(int32_t x, int32_t y) = 0;

        virtual ndk::ScopedAStatus longPress(int32_t x, int32_t y, int32_t durationMs) = 0;

        virtual ndk::ScopedAStatus doubleTap(int32_t x, int32_t y) = 0;

        virtual ndk::ScopedAStatus swipe(
                int32_t x1, int32_t y1,
                int32_t x2, int32_t y2,
                int32_t durationMs) = 0;

        virtual ndk::ScopedAStatus scroll(
                int32_t x, int32_t y,
                int32_t direction, int32_t distance) = 0;

        virtual ndk::ScopedAStatus pinch(
                int32_t centerX, int32_t centerY,
                int32_t startDistance, int32_t endDistance,
                int32_t durationMs) = 0;

        virtual ndk::ScopedAStatus multiTouch(
                const std::vector<int32_t> &xs,
                const std::vector<int32_t> &ys,
                const std::vector<int32_t> &actions) = 0;

        virtual ndk::ScopedAStatus inputText(const std::string &text) = 0;

        virtual ndk::ScopedAStatus pressKey(int32_t keyCode) = 0;

        // ========== System Actions ==========
        virtual ndk::ScopedAStatus pressBack() = 0;

        virtual ndk::ScopedAStatus pressHome() = 0;

        virtual ndk::ScopedAStatus pressRecents() = 0;

        virtual ndk::ScopedAStatus openNotifications() = 0;

        virtual ndk::ScopedAStatus openQuickSettings() = 0;

        virtual ndk::ScopedAStatus launchApp(const std::string &packageName) = 0;

        virtual ndk::ScopedAStatus launchActivity(
                const std::string &packageName,
                const std::string &activityName) = 0;

        // ========== Utility Actions ==========
        virtual ndk::ScopedAStatus wait(int32_t durationMs) = 0;

        virtual ndk::ScopedAStatus saveScreenshot(const std::string &filePath) = 0;

        virtual ndk::ScopedAStatus requestIntervention(
                const std::string &reason,
                const std::string &actionHint) = 0;

        virtual ndk::ScopedAStatus call(
                const std::string &command,
                const std::string &argsJson,
                std::string *_aidl_return) = 0;

        // ========== Automation Control ==========
        virtual ndk::ScopedAStatus startHotPath() = 0;

        virtual ndk::ScopedAStatus stopAutomation() = 0;

        virtual ndk::ScopedAStatus executeTask(
                const std::string &taskJson,
                int64_t *_aidl_return) = 0;

        // ========== Debug APIs ==========
        virtual ndk::ScopedAStatus debugInjectTap(int32_t x, int32_t y) = 0;

        virtual ndk::ScopedAStatus debugInjectSwipe(
                int32_t x1, int32_t y1,
                int32_t x2, int32_t y2,
                int32_t durationMs) = 0;

        virtual ndk::ScopedAStatus debugRunDetection(
                std::vector<DetectionResult> *_aidl_return) = 0;

        // ========== Legacy Compatibility (API < 34) ==========
        virtual ndk::ScopedAStatus getScreenshotBytes(
                std::vector<uint8_t> *_aidl_return) = 0;

        // ========== Model Management ==========
        virtual ndk::ScopedAStatus reloadModels(bool *_aidl_return) = 0;

        virtual ndk::ScopedAStatus getModelStatus(std::string *_aidl_return) = 0;

        // ========== Input Device Discovery ==========
        virtual ndk::ScopedAStatus listInputDevices(
                std::vector<InputDeviceEntry> *_aidl_return) = 0;

        ndk::SpAIBinder asBinder();

        static const char *descriptor;

        // Get caller UID for authentication
        uid_t getCallingUid() const;

        // Get caller PID for security verification
        pid_t getCallingPid() const;

    protected:
        AIBinder *binder_ = nullptr;
        AIBinder_Class *class_ = nullptr;

        static binder_status_t onTransact(
                AIBinder *binder,
                transaction_code_t code,
                const AParcel *in,
                AParcel *out);

        static void *onCreate(void *args);

        static void onDestroy(void *userData);

        void createBinder();

    private:
        // Helper to read string from parcel
        static std::string readString(const AParcel *parcel);

        // Helper to write string to parcel
        static binder_status_t writeString(AParcel *parcel, const std::string &str);

        // Helper to read byte array from parcel
        static std::vector<uint8_t> readByteArray(const AParcel *parcel);

        // Helper to write byte array to parcel
        static binder_status_t writeByteArray(AParcel *parcel, const std::vector<uint8_t> &data);

        // Helper to read int32 array from parcel
        static std::vector<int32_t> readInt32Array(const AParcel *parcel);
    };

    inline const char *BnFutonDaemon::descriptor = "me.fleey.futon.IFutonDaemon";

// Implementation inline to avoid separate .cpp file
    inline BnFutonDaemon::BnFutonDaemon() {
        createBinder();
    }

    inline BnFutonDaemon::~BnFutonDaemon() {
        if (binder_) {
            AIBinder_decStrong(binder_);
            binder_ = nullptr;
        }
    }

    inline void *BnFutonDaemon::onCreate(void *args) {
        return args;
    }

    inline void BnFutonDaemon::onDestroy(void * /*userData*/) {
        // Instance destroyed
    }

    inline uid_t BnFutonDaemon::getCallingUid() const {
        return AIBinder_getCallingUid();
    }

    inline pid_t BnFutonDaemon::getCallingPid() const {
        return AIBinder_getCallingPid();
    }

    inline std::string BnFutonDaemon::readString(const AParcel *parcel) {
        std::string result;
        binder_status_t status = ReadString(parcel, &result);
        if (status != STATUS_OK) {
            LOGE("readString: failed with status=%d", status);
            return "";
        }
        return result;
    }

    inline binder_status_t BnFutonDaemon::writeString(AParcel *parcel, const std::string &str) {
        return AParcel_writeString(parcel, str.c_str(), static_cast<int32_t>(str.length()));
    }

    inline std::vector<uint8_t> BnFutonDaemon::readByteArray(const AParcel *parcel) {
        std::vector<uint8_t> data;
        binder_status_t status = AParcel_readByteArray(parcel, &data,
                                                       [](void *arrayData, int32_t length,
                                                          int8_t **outBuffer) -> bool {
                                                           if (length < 0) {
                                                               *outBuffer = nullptr;
                                                               return true;  // null array is valid
                                                           }
                                                           auto *vec = static_cast<std::vector<uint8_t> *>(arrayData);
                                                           vec->resize(length);
                                                           *outBuffer = reinterpret_cast<int8_t *>(vec->data());
                                                           return true;
                                                       });
        if (status != STATUS_OK) {
            return {};
        }
        return data;
    }

    inline binder_status_t BnFutonDaemon::writeByteArray(
            AParcel *parcel,
            const std::vector<uint8_t> &data
    ) {
        return AParcel_writeByteArray(
                parcel,
                reinterpret_cast<const int8_t *>(data.data()),
                static_cast<int32_t>(data.size())
        );
    }

// Java Parcel.createIntArray() reads: int32 length, then int32[length]
    inline std::vector<int32_t> BnFutonDaemon::readInt32Array(const AParcel *parcel) {
        std::vector<int32_t> data;
        binder_status_t status = AParcel_readInt32Array(parcel, &data,
                                                        [](void *arrayData, int32_t length,
                                                           int32_t **outBuffer) -> bool {
                                                            if (length < 0) {
                                                                *outBuffer = nullptr;
                                                                return true;
                                                            }
                                                            auto *vec = static_cast<std::vector<int32_t> *>(arrayData);
                                                            vec->resize(length);
                                                            *outBuffer = vec->data();
                                                            return true;
                                                        });
        if (status != STATUS_OK) {
            return {};
        }
        return data;
    }

// Helper to skip interface token written by Java AIDL Proxy
    inline binder_status_t skipInterfaceToken(const AParcel *parcel) {
        int32_t pos = AParcel_getDataPosition(parcel);
        LOGD("skipInterfaceToken: pos=%d (interface token already consumed by NDK)", pos);
        // NDK Binder already consumed the interface token - nothing to do
        return STATUS_OK;
    }


    inline void writeNoException(AParcel *parcel) {
        AParcel_writeInt32(parcel, 0);  // 0 = no exception
    }

    inline void writeServiceSpecificException(AParcel *parcel, int32_t errorCode,
                                              const char *message = nullptr) {
        // Exception code -8 = EX_SERVICE_SPECIFIC
        AParcel_writeInt32(parcel, -8);
        if (message && strlen(message) > 0) {
            AParcel_writeString(parcel, message, static_cast<int32_t>(strlen(message)));
        } else {
            char defaultMsg[64];
            snprintf(defaultMsg, sizeof(defaultMsg), "Service error (code=%d)", errorCode);
            AParcel_writeString(parcel, defaultMsg, static_cast<int32_t>(strlen(defaultMsg)));
        }
        AParcel_writeInt32(parcel, errorCode);
    }

    inline binder_status_t handleStatusAndReturn(AParcel *out, const ndk::ScopedAStatus &status,
                                                 const char *methodName = nullptr) {
        if (!status.isOk()) {
            char msg[256];
            const char *method = methodName ? methodName : "unknown";
            const char *statusMsg = status.getMessage();
            int32_t errorCode = status.getServiceSpecificError();

            // If errorCode is 0 but status is not OK, it's a different type of error
            // Use the exception code as a fallback
            if (errorCode == 0) {
                errorCode = static_cast<int32_t>(status.getExceptionCode());
                if (errorCode == 0) {
                    errorCode = -999;  // Unknown error
                }
            }

            if (statusMsg && strlen(statusMsg) > 0) {
                snprintf(msg, sizeof(msg), "%s: %s", method, statusMsg);
            } else {
                snprintf(msg, sizeof(msg), "%s failed (code=%d, exception=%d)",
                         method, status.getServiceSpecificError(),
                         static_cast<int32_t>(status.getExceptionCode()));
            }
            writeServiceSpecificException(out, errorCode, msg);
        }
        return STATUS_OK;  // Always return OK, error is in the parcel
    }


    template<typename T>
    inline binder_status_t writeTypedObject(AParcel *parcel, const T &obj) {

        binder_status_t status = AParcel_writeInt32(parcel, 1);
        if (status != STATUS_OK) return status;

        return obj.writeToParcel(parcel);
    }


    template<typename T>
    inline binder_status_t readTypedObject(const AParcel *parcel, T *obj) {

        int32_t nullMarker = 0;
        binder_status_t status = AParcel_readInt32(parcel, &nullMarker);
        if (status != STATUS_OK) return status;

        if (nullMarker == 0) {

            return STATUS_OK;
        }

        return obj->readFromParcel(parcel);
    }

    inline binder_status_t BnFutonDaemon::onTransact(
            AIBinder *binder,
            transaction_code_t code,
            const AParcel *in,
            AParcel *out
    ) {
        void *userData = AIBinder_getUserData(binder);
        BnFutonDaemon *impl = static_cast<BnFutonDaemon *>(userData);
        if (!impl) {
            return STATUS_UNEXPECTED_NULL;
        }

        LOGD("onTransact: code=%d", code);

        // Handle special system transactions (ping, etc.)
        if (code == static_cast<transaction_code_t>(IBinder::PING_TRANSACTION)) {
            LOGD("onTransact: PING_TRANSACTION, returning OK");
            return STATUS_OK;
        }

        if (code >= static_cast<transaction_code_t>(IBinder::FIRST_CALL_TRANSACTION)) {
            binder_status_t tokenStatus = skipInterfaceToken(in);
            if (tokenStatus != STATUS_OK) {
                LOGD("onTransact: skipInterfaceToken returned status=%d for code=%d (may be OK for empty parcel)",
                     tokenStatus, code);
                // Don't fail - continue anyway, the parcel might be empty or in a different format
            }
        }

        switch (static_cast<IFutonDaemonTransaction>(code)) {
            // ========== Version & Capability (1-2) ==========
            case IFutonDaemonTransaction::GET_VERSION: {
                int32_t result = 0;
                auto status = impl->getVersion(&result);
                if (status.isOk()) {
                    writeNoException(out);
                    AParcel_writeInt32(out, result);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::GET_CAPABILITIES: {
                int32_t result = 0;
                auto status = impl->getCapabilities(&result);
                if (status.isOk()) {
                    writeNoException(out);
                    AParcel_writeInt32(out, result);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::GET_SYSTEM_STATUS: {
                SystemStatus result;
                auto status = impl->getSystemStatus(&result);
                if (status.isOk()) {
                    writeNoException(out);
                    writeTypedObject(out, result);
                }
                return handleStatusAndReturn(out, status);
            }

                // ========== Authentication (4-7) ==========
            case IFutonDaemonTransaction::GET_CHALLENGE: {
                std::vector<uint8_t> result;
                auto status = impl->getChallenge(&result);
                if (status.isOk()) {
                    writeNoException(out);
                    writeByteArray(out, result);
                } else {
                    writeServiceSpecificException(out, status.getServiceSpecificError(),
                                                  status.getMessage() ? status.getMessage()
                                                                      : "getChallenge failed");
                }
                return STATUS_OK;
            }

            case IFutonDaemonTransaction::AUTHENTICATE: {
                std::vector<uint8_t> signature = readByteArray(in);
                std::string instanceId = readString(in);
                AuthenticateResult result;
                auto status = impl->authenticate(signature, instanceId, &result);
                if (status.isOk()) {
                    writeNoException(out);
                    writeTypedObject(out, result);
                } else {
                    writeServiceSpecificException(out, status.getServiceSpecificError(),
                                                  status.getMessage() ? status.getMessage()
                                                                      : "authenticate failed");
                }
                return STATUS_OK;
            }

            case IFutonDaemonTransaction::VERIFY_ATTESTATION: {
                int32_t chainSize = 0;
                AParcel_readInt32(in, &chainSize);
                std::vector<std::vector<uint8_t>> chain;
                for (int32_t i = 0; i < chainSize; ++i) {
                    chain.push_back(readByteArray(in));
                }
                auto status = impl->verifyAttestation(chain);
                if (status.isOk()) {
                    writeNoException(out);
                } else {
                    writeServiceSpecificException(out, status.getServiceSpecificError(),
                                                  status.getMessage() ? status.getMessage()
                                                                      : "verifyAttestation failed");
                }
                return STATUS_OK;
            }

            case IFutonDaemonTransaction::CHECK_SESSION: {
                std::string instanceId = readString(in);
                SessionStatus result;
                auto status = impl->checkSession(instanceId, &result);
                if (status.isOk()) {
                    writeNoException(out);
                    writeTypedObject(out, result);
                } else {
                    writeServiceSpecificException(out, status.getServiceSpecificError(),
                                                  status.getMessage() ? status.getMessage()
                                                                      : "checkSession failed");
                }
                return STATUS_OK;
            }

                // ========== Encrypted Channel (7-10) ==========
            case IFutonDaemonTransaction::INIT_CRYPTO_CHANNEL: {
                std::vector<uint8_t> clientDhPublic = readByteArray(in);
                CryptoHandshake result;
                auto status = impl->initCryptoChannel(clientDhPublic, &result);
                if (status.isOk()) {
                    writeNoException(out);
                    writeTypedObject(out, result);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::SEND_CONTROL_MESSAGE: {
                std::vector<uint8_t> encryptedMessage = readByteArray(in);
                std::vector<uint8_t> result;
                auto status = impl->sendControlMessage(encryptedMessage, &result);
                if (status.isOk()) {
                    writeNoException(out);
                    writeByteArray(out, result);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::SEND_DATA_MESSAGE: {
                std::vector<uint8_t> encryptedData = readByteArray(in);
                std::vector<uint8_t> result;
                auto status = impl->sendDataMessage(encryptedData, &result);
                if (status.isOk()) {
                    writeNoException(out);
                    writeByteArray(out, result);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::ROTATE_CHANNEL_KEYS: {
                CryptoHandshake result;
                auto status = impl->rotateChannelKeys(&result);
                if (status.isOk()) {
                    writeNoException(out);
                    writeTypedObject(out, result);
                }
                return handleStatusAndReturn(out, status);
            }

                // ========== Callback Registration (11-14) ==========
            case IFutonDaemonTransaction::REGISTER_STATUS_CALLBACK: {
                // Read callback binder from parcel
                AIBinder *callbackBinder = nullptr;
                if (AParcel_readStrongBinder(in, &callbackBinder) != STATUS_OK || !callbackBinder) {
                    return STATUS_BAD_VALUE;
                }
                auto callback = IStatusCallback::fromBinder(ndk::SpAIBinder(callbackBinder));
                auto status = impl->registerStatusCallback(callback);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::UNREGISTER_STATUS_CALLBACK: {
                AIBinder *callbackBinder = nullptr;
                if (AParcel_readStrongBinder(in, &callbackBinder) != STATUS_OK || !callbackBinder) {
                    return STATUS_BAD_VALUE;
                }
                auto callback = IStatusCallback::fromBinder(ndk::SpAIBinder(callbackBinder));
                auto status = impl->unregisterStatusCallback(callback);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::REGISTER_BUFFER_RELEASE_CALLBACK: {
                AIBinder *callbackBinder = nullptr;
                if (AParcel_readStrongBinder(in, &callbackBinder) != STATUS_OK || !callbackBinder) {
                    return STATUS_BAD_VALUE;
                }
                auto callback = IBufferReleaseCallback::fromBinder(ndk::SpAIBinder(callbackBinder));
                auto status = impl->registerBufferReleaseCallback(callback);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::UNREGISTER_BUFFER_RELEASE_CALLBACK: {
                AIBinder *callbackBinder = nullptr;
                if (AParcel_readStrongBinder(in, &callbackBinder) != STATUS_OK || !callbackBinder) {
                    return STATUS_BAD_VALUE;
                }
                auto callback = IBufferReleaseCallback::fromBinder(ndk::SpAIBinder(callbackBinder));
                auto status = impl->unregisterBufferReleaseCallback(callback);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

                // ========== Configuration (15-16) ==========
            case IFutonDaemonTransaction::CONFIGURE: {
                FutonConfig config;
                // Read typed object (null marker + parcelable)
                readTypedObject(in, &config);
                auto status = impl->configure(config);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::CONFIGURE_HOT_PATH: {
                std::string jsonRules = readString(in);
                auto status = impl->configureHotPath(jsonRules);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

                // ========== Perception (17-19) ==========
            case IFutonDaemonTransaction::GET_SCREENSHOT: {
                LOGD("GET_SCREENSHOT: starting");
                ScreenshotResult result;
                auto status = impl->getScreenshot(&result);
                LOGD("GET_SCREENSHOT: impl returned, isOk=%d, bufferId=%d, buffer=%p, %dx%d",
                     status.isOk(), result.bufferId, result.buffer, result.width, result.height);
                if (status.isOk()) {
                    writeNoException(out);
                    binder_status_t writeStatus = writeTypedObject(out, result);
                    LOGD("GET_SCREENSHOT: writeTypedObject returned %d", writeStatus);
                    if (writeStatus != STATUS_OK) {
                        LOGE("GET_SCREENSHOT: failed to write result to parcel: %d", writeStatus);
                        return writeStatus;
                    }
                }
                return handleStatusAndReturn(out, status, "getScreenshot");
            }

            case IFutonDaemonTransaction::RELEASE_SCREENSHOT: {
                int32_t bufferId = 0;
                AParcel_readInt32(in, &bufferId);
                auto status = impl->releaseScreenshot(bufferId);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status, "releaseScreenshot");
            }

            case IFutonDaemonTransaction::REQUEST_PERCEPTION: {
                LOGD("REQUEST_PERCEPTION: starting");
                std::vector<DetectionResult> results;
                auto status = impl->requestPerception(&results);
                LOGD("REQUEST_PERCEPTION: impl returned, isOk=%d, results.size=%zu",
                     status.isOk(), results.size());
                if (status.isOk()) {
                    writeNoException(out);
                    // writeTypedArray: write size, then each element with null marker
                    AParcel_writeInt32(out, static_cast<int32_t>(results.size()));
                    for (const auto &r: results) {
                        writeTypedObject(out, r);
                    }
                } else {
                    // Return service-specific error instead of STATUS_FAILED_TRANSACTION
                    // STATUS_FAILED_TRANSACTION causes DeadObjectException on Java side
                    writeServiceSpecificException(out, status.getServiceSpecificError(),
                                                  status.getMessage() ? status.getMessage()
                                                                      : "requestPerception failed");
                }
                LOGD("REQUEST_PERCEPTION: done");
                return STATUS_OK;  // Always return STATUS_OK, error is in the parcel
            }

                // ========== Input Injection (20-29) ==========
            case IFutonDaemonTransaction::TAP: {
                int32_t x = 0, y = 0;
                AParcel_readInt32(in, &x);
                AParcel_readInt32(in, &y);
                auto status = impl->tap(x, y);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::LONG_PRESS: {
                int32_t x = 0, y = 0, durationMs = 0;
                AParcel_readInt32(in, &x);
                AParcel_readInt32(in, &y);
                AParcel_readInt32(in, &durationMs);
                auto status = impl->longPress(x, y, durationMs);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::DOUBLE_TAP: {
                int32_t x = 0, y = 0;
                AParcel_readInt32(in, &x);
                AParcel_readInt32(in, &y);
                auto status = impl->doubleTap(x, y);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::SWIPE: {
                int32_t x1 = 0, y1 = 0, x2 = 0, y2 = 0, durationMs = 0;
                AParcel_readInt32(in, &x1);
                AParcel_readInt32(in, &y1);
                AParcel_readInt32(in, &x2);
                AParcel_readInt32(in, &y2);
                AParcel_readInt32(in, &durationMs);
                auto status = impl->swipe(x1, y1, x2, y2, durationMs);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::SCROLL: {
                int32_t x = 0, y = 0, direction = 0, distance = 0;
                AParcel_readInt32(in, &x);
                AParcel_readInt32(in, &y);
                AParcel_readInt32(in, &direction);
                AParcel_readInt32(in, &distance);
                auto status = impl->scroll(x, y, direction, distance);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::PINCH: {
                int32_t centerX = 0, centerY = 0, startDist = 0, endDist = 0, durationMs = 0;
                AParcel_readInt32(in, &centerX);
                AParcel_readInt32(in, &centerY);
                AParcel_readInt32(in, &startDist);
                AParcel_readInt32(in, &endDist);
                AParcel_readInt32(in, &durationMs);
                auto status = impl->pinch(centerX, centerY, startDist, endDist, durationMs);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::MULTI_TOUCH: {
                std::vector<int32_t> xs = readInt32Array(in);
                std::vector<int32_t> ys = readInt32Array(in);
                std::vector<int32_t> actions = readInt32Array(in);
                auto status = impl->multiTouch(xs, ys, actions);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::INPUT_TEXT: {
                std::string text = readString(in);
                auto status = impl->inputText(text);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::PRESS_KEY: {
                int32_t keyCode = 0;
                AParcel_readInt32(in, &keyCode);
                auto status = impl->pressKey(keyCode);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

                // ========== System Actions (30-36) ==========
            case IFutonDaemonTransaction::PRESS_BACK: {
                auto status = impl->pressBack();
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::PRESS_HOME: {
                auto status = impl->pressHome();
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::PRESS_RECENTS: {
                auto status = impl->pressRecents();
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::OPEN_NOTIFICATIONS: {
                auto status = impl->openNotifications();
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::OPEN_QUICK_SETTINGS: {
                auto status = impl->openQuickSettings();
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::LAUNCH_APP: {
                std::string packageName = readString(in);
                auto status = impl->launchApp(packageName);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::LAUNCH_ACTIVITY: {
                std::string packageName = readString(in);
                std::string activityName = readString(in);
                auto status = impl->launchActivity(packageName, activityName);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

                // ========== Utility Actions (37-38) ==========
            case IFutonDaemonTransaction::WAIT: {
                int32_t durationMs = 0;
                AParcel_readInt32(in, &durationMs);
                auto status = impl->wait(durationMs);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::SAVE_SCREENSHOT: {
                std::string filePath = readString(in);
                auto status = impl->saveScreenshot(filePath);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::REQUEST_INTERVENTION: {
                std::string reason = readString(in);
                std::string actionHint = readString(in);
                auto status = impl->requestIntervention(reason, actionHint);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::CALL: {
                std::string command = readString(in);
                std::string argsJson = readString(in);
                std::string result;
                auto status = impl->call(command, argsJson, &result);
                if (status.isOk()) {
                    writeNoException(out);
                    writeString(out, result);
                }
                return handleStatusAndReturn(out, status);
            }

                // ========== Automation Control (39-41) ==========
            case IFutonDaemonTransaction::START_HOT_PATH: {
                auto status = impl->startHotPath();
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::STOP_AUTOMATION: {
                auto status = impl->stopAutomation();
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::EXECUTE_TASK: {
                std::string taskJson = readString(in);
                int64_t result = 0;
                auto status = impl->executeTask(taskJson, &result);
                if (status.isOk()) {
                    writeNoException(out);
                    AParcel_writeInt64(out, result);
                }
                return handleStatusAndReturn(out, status);
            }

                // ========== Debug APIs (28-30) ==========
            case IFutonDaemonTransaction::DEBUG_INJECT_TAP: {
                int32_t x = 0, y = 0;
                AParcel_readInt32(in, &x);
                AParcel_readInt32(in, &y);
                auto status = impl->debugInjectTap(x, y);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::DEBUG_INJECT_SWIPE: {
                int32_t x1 = 0, y1 = 0, x2 = 0, y2 = 0, durationMs = 0;
                AParcel_readInt32(in, &x1);
                AParcel_readInt32(in, &y1);
                AParcel_readInt32(in, &x2);
                AParcel_readInt32(in, &y2);
                AParcel_readInt32(in, &durationMs);
                auto status = impl->debugInjectSwipe(x1, y1, x2, y2, durationMs);
                if (status.isOk()) {
                    writeNoException(out);
                }
                return handleStatusAndReturn(out, status);
            }

            case IFutonDaemonTransaction::DEBUG_RUN_DETECTION: {
                std::vector<DetectionResult> results;
                auto status = impl->debugRunDetection(&results);
                if (status.isOk()) {
                    writeNoException(out);
                    // writeTypedArray: write size, then each element with null marker
                    AParcel_writeInt32(out, static_cast<int32_t>(results.size()));
                    for (const auto &r: results) {
                        writeTypedObject(out, r);
                    }
                }
                return handleStatusAndReturn(out, status);
            }

                // ========== Model Management ==========
            case IFutonDaemonTransaction::RELOAD_MODELS: {
                bool result = false;
                auto status = impl->reloadModels(&result);
                if (status.isOk()) {
                    writeNoException(out);
                    AParcel_writeInt32(out, result ? 1 : 0);
                } else {
                    writeServiceSpecificException(out, status.getServiceSpecificError(),
                                                  status.getMessage() ? status.getMessage()
                                                                      : "reloadModels failed");
                }
                return STATUS_OK;
            }

            case IFutonDaemonTransaction::GET_MODEL_STATUS: {
                std::string result;
                auto status = impl->getModelStatus(&result);
                if (status.isOk()) {
                    writeNoException(out);
                    writeString(out, result);
                } else {
                    writeServiceSpecificException(out, status.getServiceSpecificError(),
                                                  status.getMessage() ? status.getMessage()
                                                                      : "getModelStatus failed");
                }
                return STATUS_OK;
            }

                // ========== Input Device Discovery ==========
            case IFutonDaemonTransaction::LIST_INPUT_DEVICES: {
                std::vector<InputDeviceEntry> results;
                auto status = impl->listInputDevices(&results);
                if (status.isOk()) {
                    writeNoException(out);
                    // writeTypedArray: write size, then each element with null marker
                    AParcel_writeInt32(out, static_cast<int32_t>(results.size()));
                    for (const auto &r: results) {
                        writeTypedObject(out, r);
                    }
                }
                return handleStatusAndReturn(out, status);
            }

                // ========== Legacy Compatibility (API < 34) ==========
            case IFutonDaemonTransaction::GET_SCREENSHOT_BYTES: {
                std::vector<uint8_t> result;
                auto status = impl->getScreenshotBytes(&result);
                if (status.isOk()) {
                    writeNoException(out);
                    // Write byte array: size followed by raw bytes
                    AParcel_writeInt32(out, static_cast<int32_t>(result.size()));
                    if (!result.empty()) {
                        AParcel_writeByteArray(out, reinterpret_cast<const int8_t*>(result.data()),
                                               static_cast<int32_t>(result.size()));
                    }
                }
                return handleStatusAndReturn(out, status);
            }

            default:
                return STATUS_UNKNOWN_TRANSACTION;
        }
    }

    inline void BnFutonDaemon::createBinder() {
        class_ = AIBinder_Class_define(
                descriptor,
                onCreate,
                onDestroy,
                onTransact);

        if (class_) {
            binder_ = AIBinder_new(class_, this);
            if (binder_) {
                AIBinder_incStrong(binder_);
            }
        }
    }

    inline ndk::SpAIBinder BnFutonDaemon::asBinder() {
        return ndk::SpAIBinder(binder_);
    }

} // namespace aidl::me::fleey::futon

#endif // AIDL_ME_FLEEY_FUTON_BN_FUTON_DAEMON_H
