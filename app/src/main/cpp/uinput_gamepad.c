// Real Linux uinput virtual gamepad + force-feedback backend for GamepadInjectorService.
// Runs inside the Shizuku shell-UID process (/dev/uinput is only writable there, not from
// the main app's UID — confirmed live in an earlier session).
//
// Button/axis codes verified against the real kernel uapi header
// (torvalds/linux include/uapi/linux/input-event-codes.h) rather than assumed:
//   BTN_A=BTN_SOUTH=0x130 BTN_B=BTN_EAST=0x131 BTN_X=BTN_NORTH=0x133 BTN_Y=BTN_WEST=0x134
//   BTN_TL=0x136 BTN_TR=0x137 BTN_START=0x13b BTN_MODE=0x13c BTN_THUMBL=0x13d BTN_THUMBR=0x13e
//   ABS_X=0 ABS_Y=1 ABS_Z=2 ABS_RZ=5 ABS_GAS=9 ABS_BRAKE=0xa ABS_HAT0X=0x10 ABS_HAT0Y=0x11
// Live-verified on the Shield: our vendor/product (0x045e/0x02fd) matches a REAL Xbox
// controller .kl file already on the device (/system/usr/keylayout/Vendor_045e_Product_02fd.kl,
// pulled and read directly), not the Generic.kl fallback assumed when this was written. That
// file confirms GAS/BRAKE->RTRIGGER/LTRIGGER as guessed below, but also revealed a real bug:
// it maps View/Select to raw KEY_BACK (158), NOT BTN_SELECT (0x13a) — sending 0x13a produced
// an event this .kl file has no rule for, so View never became a KeyEvent RetroArch could see.
// KEY_BACK is in the low keyboard-code range, not the BTN_* gamepad range, hence the separate
// UI_SET_KEYBIT below.
//
// uinput force-feedback upload/erase/play protocol is the standard documented one from
// include/uapi/linux/uinput.h's own header comment (EV_UINPUT/UI_FF_UPLOAD/UI_FF_ERASE,
// UI_BEGIN_FF_UPLOAD/UI_END_FF_UPLOAD ioctls) — verified against that header directly.

#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <android/log.h>

#define LOG_TAG "GipUinput"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define MAX_FF_EFFECTS 16

typedef struct {
    int in_use;
    unsigned short strong;
    unsigned short weak;
    unsigned short duration_ms; // ff_effect.replay.length — 0 in the real protocol means
                                 // "play until explicitly stopped", not "no duration"
} ff_slot_t;

static ff_slot_t g_effects[MAX_FF_EFFECTS];

static void write_event(int fd, unsigned short type, unsigned short code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) < 0) {
        LOGE("write_event(type=%u code=%u) failed: %s", type, code, strerror(errno));
    }
}

JNIEXPORT jint JNICALL
Java_com_vanzetta_gipbridge_GamepadInjectorService_nativeOpenUinput(JNIEnv *env, jobject thiz, jstring jname) {
    (void) thiz;
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);

    // Deliberately blocking (no O_NONBLOCK): nativePollFF() below relies on read() blocking
    // until the kernel has an FF upload/erase/play event, instead of a busy-poll loop.
    int fd = open("/dev/uinput", O_RDWR);
    if (fd < 0) {
        LOGE("open /dev/uinput failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_FF);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    static const int buttons[] = {
        BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR,
        BTN_THUMBL, BTN_THUMBR, KEY_BACK, BTN_START, BTN_MODE,
    };
    for (size_t i = 0; i < sizeof(buttons) / sizeof(buttons[0]); i++) {
        ioctl(fd, UI_SET_KEYBIT, buttons[i]);
    }

    static const int absAxes[] = { ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y };
    for (size_t i = 0; i < sizeof(absAxes) / sizeof(absAxes[0]); i++) {
        ioctl(fd, UI_SET_ABSBIT, absAxes[i]);
    }

    ioctl(fd, UI_SET_FFBIT, FF_RUMBLE);

    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    strncpy(dev.name, name, UINPUT_MAX_NAME_SIZE - 1);
    dev.id.bustype = BUS_USB;
    dev.id.vendor = 0x045e;  // Xbox-style vendor — matches what apps expect from a GIP pad
    dev.id.product = 0x02fd;
    dev.id.version = 1;
    dev.ff_effects_max = MAX_FF_EFFECTS;

    dev.absmin[ABS_X] = -32768; dev.absmax[ABS_X] = 32767;
    dev.absmin[ABS_Y] = -32768; dev.absmax[ABS_Y] = 32767;
    dev.absmin[ABS_Z] = -32768; dev.absmax[ABS_Z] = 32767;
    dev.absmin[ABS_RZ] = -32768; dev.absmax[ABS_RZ] = 32767;
    dev.absmin[ABS_GAS] = 0; dev.absmax[ABS_GAS] = 1023;
    dev.absmin[ABS_BRAKE] = 0; dev.absmax[ABS_BRAKE] = 1023;
    dev.absmin[ABS_HAT0X] = -1; dev.absmax[ABS_HAT0X] = 1;
    dev.absmin[ABS_HAT0Y] = -1; dev.absmax[ABS_HAT0Y] = 1;

    if (write(fd, &dev, sizeof(dev)) < 0) {
        LOGE("write uinput_user_dev failed: %s", strerror(errno));
        close(fd);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: %s", strerror(errno));
        close(fd);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }

    (*env)->ReleaseStringUTFChars(env, jname, name);
    memset(g_effects, 0, sizeof(g_effects));
    LOGI("uinput gamepad created, fd=%d", fd);
    return fd;
}

JNIEXPORT void JNICALL
Java_com_vanzetta_gipbridge_GamepadInjectorService_nativeCloseUinput(JNIEnv *env, jobject thiz, jint fd) {
    (void) env; (void) thiz;
    if (fd >= 0) {
        ioctl(fd, UI_DEV_DESTROY);
        close(fd);
    }
}

JNIEXPORT void JNICALL
Java_com_vanzetta_gipbridge_GamepadInjectorService_nativeSendKey(JNIEnv *env, jobject thiz, jint fd, jint code, jboolean down) {
    (void) env; (void) thiz;
    write_event(fd, EV_KEY, (unsigned short) code, down ? 1 : 0);
    write_event(fd, EV_SYN, SYN_REPORT, 0);
}

JNIEXPORT void JNICALL
Java_com_vanzetta_gipbridge_GamepadInjectorService_nativeSendAxes(
        JNIEnv *env, jobject thiz, jint fd,
        jint x, jint y, jint z, jint rz, jint gas, jint brake, jint hatX, jint hatY) {
    (void) env; (void) thiz;
    write_event(fd, EV_ABS, ABS_X, x);
    write_event(fd, EV_ABS, ABS_Y, y);
    write_event(fd, EV_ABS, ABS_Z, z);
    write_event(fd, EV_ABS, ABS_RZ, rz);
    write_event(fd, EV_ABS, ABS_GAS, gas);
    write_event(fd, EV_ABS, ABS_BRAKE, brake);
    write_event(fd, EV_ABS, ABS_HAT0X, hatX);
    write_event(fd, EV_ABS, ABS_HAT0Y, hatY);
    write_event(fd, EV_SYN, SYN_REPORT, 0);
}

// Blocks on read() until a rumble-relevant kernel event arrives. Packs the result into a
// long since JNI has no easy multi-out-param primitive return:
//   bits 48-49: type (0=none 1=play 2=stop)   bits 32-47: effect id
//   bits 16-31: strong magnitude (u16)          bits 0-15: weak magnitude (u16)
// Returns -1 on read failure/closed fd so the caller can back off and retry/exit.
JNIEXPORT jlong JNICALL
Java_com_vanzetta_gipbridge_GamepadInjectorService_nativePollFF(JNIEnv *env, jobject thiz, jint fd) {
    (void) env; (void) thiz;
    struct input_event ev;
    ssize_t n = read(fd, &ev, sizeof(ev));
    if (n != (ssize_t) sizeof(ev)) {
        return -1;
    }

    if (ev.type == EV_UINPUT && ev.code == UI_FF_UPLOAD) {
        struct uinput_ff_upload upload;
        memset(&upload, 0, sizeof(upload));
        upload.request_id = (unsigned int) ev.value;
        ioctl(fd, UI_BEGIN_FF_UPLOAD, &upload);

        int id = upload.effect.id;
        if (upload.effect.type == FF_RUMBLE && id >= 0 && id < MAX_FF_EFFECTS) {
            g_effects[id].in_use = 1;
            g_effects[id].strong = upload.effect.u.rumble.strong_magnitude;
            g_effects[id].weak = upload.effect.u.rumble.weak_magnitude;
            g_effects[id].duration_ms = upload.effect.replay.length;
        }

        upload.retval = 0;
        ioctl(fd, UI_END_FF_UPLOAD, &upload);
        return 0;
    }

    if (ev.type == EV_UINPUT && ev.code == UI_FF_ERASE) {
        struct uinput_ff_erase erase;
        memset(&erase, 0, sizeof(erase));
        erase.request_id = (unsigned int) ev.value;
        ioctl(fd, UI_BEGIN_FF_ERASE, &erase);
        int id = (int) erase.effect_id;
        if (id >= 0 && id < MAX_FF_EFFECTS) g_effects[id].in_use = 0;
        erase.retval = 0;
        ioctl(fd, UI_END_FF_ERASE, &erase);
        return 0;
    }

    if (ev.type == EV_FF) {
        int id = ev.code;
        if (id < 0 || id >= MAX_FF_EFFECTS || !g_effects[id].in_use) return 0;
        // type is 1 (play) or 2 (stop) — never 0 — so a real event can never collide with the
        // "nothing happened" 0 sentinel this function returns elsewhere.
        long long type = ev.value != 0 ? 1 : 2;
        long long strong = g_effects[id].strong;
        long long weak = g_effects[id].weak;
        long long duration = g_effects[id].duration_ms;
        return (type << 60) | (strong << 38) | (weak << 22) | (duration << 6);
    }

    return 0;
}
