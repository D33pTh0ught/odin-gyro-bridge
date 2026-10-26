#include <android/log.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <sstream>
#include <stdexcept>
#include <string>

#include "bridge_config.h"
#include "input_io.h"
#include "motion.h"

namespace {
using namespace gyrobridge;

struct Bridge {
    JNIEnv* env;
    jclass java;
    Fd input, mouse, pad;
    std::array<input_absinfo, ABS_CNT> ranges{};
    std::array<int, ABS_CNT> physical{};
    std::array<int, KEY_CNT> keys{}, forwarded{};
    MotionEngine engine;
    MotionOutput gyro;
    int socket, activationType = EV_KEY, activationCode = BTN_TL;
    bool hold = false, grab = false, started = false, consume = false, suspended = false,
         assigning = false;
    double lastGyro = 0, lastCommand = seconds(), lastStatus = 0, outX = 0, outY = 0;
    explicit Bridge(JNIEnv* e, int s) : env(e), socket(s) {
        java = env->FindClass("dev/gyrobridge/BridgeMain");
        input_id identity{};
        char name[256]{};
        std::string path;
        for (int i = 0; i < 64; ++i) {
            auto candidate = "/dev/input/event" + std::to_string(i);
            int fd = open(candidate.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
            if (fd < 0)
                continue;
            input_id id{};
            if (ioctl(fd, EVIOCGID, &id) < 0 || ioctl(fd, EVIOCGNAME(sizeof(name)), name) < 0) {
                close(fd);
                continue;
            }
            name[sizeof(name) - 1] = '\0';
            // Scope interception to AYN's built-in pad. Never grab touchscreen/power keys.
            if (id.vendor == 0x2020 && id.product == 0x0112 &&
                std::string(name) != "GyroBridge Controller") {
                input.v = fd;
                path = candidate;
                identity = id;
                break;
            }
            close(fd);
        }
        require(input.v >= 0,
                "AYN controller 2020:0112 not found; try Xbox controller style in Odin settings");
        readState();
        auto findController =
            env->GetStaticMethodID(java, "findControllerId", "(IILjava/lang/String;)I");
        auto title = env->NewStringUTF(name);
        int id = env->CallStaticIntMethod(java, findController, identity.vendor, identity.product,
                                          title);
        env->DeleteLocalRef(title);
        checkJava();
        sendLine(socket, "READY " + path + " | " + name + " | Android id " + std::to_string(id) +
                             " | stock uid " + std::to_string(getuid()));
    }
    void checkJava() {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            throw std::runtime_error("Android input device registration failed");
        }
    }
    void readState() {
        for (int axis : controllerAxes) {
            checkIo(ioctl(input.v, EVIOCGABS(axis), &ranges[axis]) == 0, "Read controller axis");
            require(ranges[axis].maximum > ranges[axis].minimum,
                    "Controller has an invalid axis range");
            physical[axis] = ranges[axis].value;
        }
        unsigned char bits[(KEY_CNT + 7) / 8]{};
        checkIo(ioctl(input.v, EVIOCGKEY(sizeof(bits)), bits) >= 0, "Read controller keys");
        for (int i = 0; i < KEY_CNT; ++i)
            keys[i] = (bits[i / 8] >> (i % 8)) & 1;
    }
    void key(int code, int value) {
        input_event events[2]{};
        events[0].type = EV_KEY;
        events[0].code = code;
        events[0].value = value;
        events[1].type = EV_SYN;
        events[1].code = SYN_REPORT;
        writeInputEvents(pad.v, events, 2);
        forwarded[code] = value != 0;
    }
    float normal(int a) const {
        const auto& r = ranges[a];
        if (r.maximum <= r.minimum)
            return 0;
        float value = float(physical[a] - r.minimum) / float(r.maximum - r.minimum);
        return a == ABS_BRAKE || a == ABS_GAS ? std::clamp(value, 0.f, 1.f)
                                              : std::clamp(2 * value - 1, -1.f, 1.f);
    }
    void axes() {
        if (!grab)
            return;
        bool active = hold && !suspended && engine.settings.stick;
        float values[8] = {normal(ABS_X),
                           normal(ABS_Y),
                           mergeAxis(normal(ABS_Z), active ? gyro.x : 0),
                           mergeAxis(normal(ABS_RZ), active ? gyro.y : 0),
                           normal(ABS_BRAKE),
                           normal(ABS_GAS),
                           normal(ABS_HAT0X),
                           normal(ABS_HAT0Y)};
        outX = values[2];
        outY = values[3];
        input_event events[9]{};
        for (int i = 0; i < 8; ++i) {
            const auto& range = ranges[controllerAxes[i]];
            const double normalized = i == 4 || i == 5 ? values[i] : (values[i] + 1.) / 2.;
            events[i].type = EV_ABS;
            events[i].code = controllerAxes[i];
            events[i].value =
                std::lround(range.minimum + normalized * (range.maximum - range.minimum));
        }
        events[8].type = EV_SYN;
        events[8].code = SYN_REPORT;
        writeInputEvents(pad.v, events, 9);
    }
    void createPad() {
        pad.v = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
        checkIo(pad.v >= 0, "Cannot open uinput for controller");
        checkIo(ioctl(pad.v, UI_SET_EVBIT, EV_KEY) == 0 && ioctl(pad.v, UI_SET_EVBIT, EV_ABS) == 0,
                "Controller capabilities");
        unsigned char bits[(KEY_CNT + 7) / 8]{};
        checkIo(ioctl(input.v, EVIOCGBIT(EV_KEY, sizeof(bits)), bits) >= 0,
                "Read controller buttons");
        for (int k = 0; k < KEY_CNT; ++k)
            if ((bits[k / 8] >> (k % 8)) & 1)
                checkIo(ioctl(pad.v, UI_SET_KEYBIT, k) == 0, "Controller button capability");
        for (int a : controllerAxes) {
            checkIo(ioctl(pad.v, UI_SET_ABSBIT, a) == 0, "Controller axis capability");
            uinput_abs_setup setup{};
            setup.code = a;
            setup.absinfo = ranges[a];
            // Only the gyro-driven axes need an unfiltered center region.
            // Preserve the physical controller's metadata for every other axis.
            if (engine.settings.stick && (a == ABS_Z || a == ABS_RZ))
                setup.absinfo.flat = 0;
            checkIo(ioctl(pad.v, UI_ABS_SETUP, &setup) == 0, "Controller axis setup");
        }
        checkIo(ioctl(pad.v, UI_SET_PHYS, "gyrobridge/controller") == 0, "Controller location");
        uinput_setup setup{};
        snprintf(setup.name, sizeof(setup.name), "GyroBridge Controller");
        // Reuse AYN's existing Android key layout for its nonstandard trigger axes.
        setup.id.bustype = BUS_USB;
        setup.id.vendor = 0x2020;
        setup.id.product = 0x0112;
        checkIo(ioctl(pad.v, UI_DEV_SETUP, &setup) == 0 && ioctl(pad.v, UI_DEV_CREATE) == 0,
                "Controller creation");
        awaitDevice("GyroBridge Controller");
    }
    void createMouse() {
        mouse.v = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
        checkIo(mouse.v >= 0, "Cannot open /dev/uinput");
        checkIo(ioctl(mouse.v, UI_SET_EVBIT, EV_KEY) == 0 &&
                    ioctl(mouse.v, UI_SET_KEYBIT, BTN_LEFT) == 0,
                "Mouse buttons");
        checkIo(ioctl(mouse.v, UI_SET_EVBIT, EV_REL) == 0 &&
                    ioctl(mouse.v, UI_SET_RELBIT, REL_X) == 0 &&
                    ioctl(mouse.v, UI_SET_RELBIT, REL_Y) == 0,
                "Mouse axes");
        uinput_setup setup{};
        snprintf(setup.name, sizeof(setup.name), "GyroBridge Mouse");
        setup.id.bustype = BUS_USB;
        setup.id.vendor = 0x1209;
        setup.id.product = 0x4701;
        checkIo(ioctl(mouse.v, UI_DEV_SETUP, &setup) == 0 && ioctl(mouse.v, UI_DEV_CREATE) == 0,
                "Mouse creation");
        awaitDevice("GyroBridge Mouse");
    }
    void awaitDevice(const char* name) {
        auto wait = env->GetStaticMethodID(java, "awaitDevice", "(Ljava/lang/String;)Z");
        auto title = env->NewStringUTF(name);
        const bool found = env->CallStaticBooleanMethod(java, wait, title);
        env->DeleteLocalRef(title);
        checkJava();
        require(found, std::string("Android did not register ") + name);
    }
    void moveMouse(int x, int y) {
        if (!x && !y)
            return;
        input_event events[3]{};
        events[0].type = EV_REL;
        events[0].code = REL_X;
        events[0].value = x;
        events[1].type = EV_REL;
        events[1].code = REL_Y;
        events[1].value = y;
        events[2].type = EV_SYN;
        events[2].code = SYN_REPORT;
        writeInputEvents(mouse.v, events, 3);
        outX = x;
        outY = y;
    }
    void status() {
        std::ostringstream out;
        out << "STATE " << started << ' ' << (started && hold && !suspended) << ' ' << outX << ' '
            << outY << ' ' << normal(ABS_Z) << ' ' << normal(ABS_RZ);
        sendLine(socket, out.str());
        lastStatus = seconds();
    }
    void updateHold() {
        bool next = !assigning && (activationType == EV_KEY ? keys[activationCode] != 0
                                                            : normal(activationCode) > .5f);
        if (hold != next) {
            hold = next;
            engine.reset();
            gyro = {};
            outX = outY = 0;
            if (started)
                axes();
            status();
        }
    }
    void start() {
        if (started) {
            status();
            return;
        }
        stop();
        if (engine.settings.stick || consume) {
            createPad();
            checkIo(ioctl(input.v, EVIOCGRAB, 1) == 0, "Cannot intercept controller");
            grab = true;
            // Snapshot after ownership changes. Discard older queued events so a
            // key released while Android registers the pad is not replayed as held.
            input_event pending[64];
            while (read(input.v, pending, sizeof(pending)) > 0) {
            }
            readState();
            for (int k = 0; k < KEY_CNT; ++k)
                if (keys[k] && !(consume && activationType == EV_KEY && activationCode == k))
                    key(k, 1);
        }
        if (!engine.settings.stick)
            createMouse();
        started = true;
        updateHold();
        axes();
        status();
    }
    void stop() noexcept {
        engine.reset();
        gyro = {};
        started = false;
        assigning = false;
        lastGyro = 0;
        outX = outY = 0;
        if (grab) {
            // Cleanup must finish even if a final virtual write fails. Removing
            // the device also tells Android to release its held keys.
            try {
                for (int k = 0; k < KEY_CNT; ++k)
                    if (forwarded[k])
                        key(k, 0);
            } catch (...) {
            }
            ioctl(input.v, EVIOCGRAB, 0);
            grab = false;
        }
        forwarded.fill(0);
        destroyVirtualDevice(pad);
        destroyVirtualDevice(mouse);
    }
    void assign(int type, int code) {
        assigning = false;
        sendLine(socket, "BOUND " + std::to_string(type) + " " + std::to_string(code));
    }
    void event(const input_event& e) {
        if (e.type == EV_KEY && e.code < KEY_CNT) {
            keys[e.code] = e.value != 0;
            if (assigning && e.value == 1)
                assign(EV_KEY, e.code);
            updateHold();
            if (grab && !(consume && activationType == EV_KEY && e.code == activationCode) &&
                (e.value == 2 || forwarded[e.code] != keys[e.code]))
                key(e.code, e.value);
        } else if (e.type == EV_ABS && e.code < ABS_CNT) {
            physical[e.code] = e.value;
            if (e.code == ABS_BRAKE || e.code == ABS_GAS) {
                if (assigning && normal(e.code) > .6f)
                    assign(EV_ABS, e.code);
                updateHold();
            }
        } else if (e.type == EV_SYN && e.code == SYN_DROPPED)
            throw std::runtime_error("Controller event queue overflow; restart service");
        else if (e.type == EV_SYN && e.code == SYN_REPORT)
            axes();
    }
    bool command(const std::string& line) {
        lastCommand = seconds();
        std::istringstream in(line);
        std::string op;
        in >> op;
        if (op == "QUIT")
            return false;
        if (op == "PING")
            return true;
        if (op == "CONFIG") {
            const auto config = readConfig(in);
            const bool nextStick = config.motion.stick, nextConsume = config.consume;
            const bool restart = started && (engine.settings.stick != nextStick ||
                                             grab != (nextStick || nextConsume));
            if (restart)
                stop();
            engine.settings = config.motion;
            activationType = config.activationType;
            activationCode = config.activationCode;
            consume = nextConsume;
            engine.reset();
            gyro = {};
            if (restart)
                start();
            else {
                if (grab)
                    for (int k = 0; k < KEY_CNT; ++k) {
                        const int target = keys[k] && !(consume && activationType == EV_KEY &&
                                                        k == activationCode);
                        if (forwarded[k] != target)
                            key(k, target);
                    }
                updateHold();
                axes();
            }
        }
#ifndef NDEBUG
        else if (op == "TESTEVENT") {
            int type, code, value;
            if (in >> type >> code >> value) {
                require((type == EV_KEY && code > 0 && code < KEY_CNT) ||
                            (type == EV_ABS && code >= 0 && code < ABS_CNT) ||
                            (type == EV_SYN && code == SYN_REPORT),
                        "Invalid test input");
                input_event test{};
                test.type = type;
                test.code = code;
                test.value = value;
                event(test);
            }
        }
#endif
        else if (op == "START")
            start();
        else if (op == "STOP") {
            stop();
            status();
        } else if (op == "BIND") {
            stop();
            assigning = true;
            engine.reset();
            sendLine(socket, "BINDING");
        } else if (op == "CANCEL") {
            assigning = false;
            updateHold();
        } else if (op == "SUSPEND") {
            int v = 0;
            require(bool(in >> v) && (v == 0 || v == 1), "Invalid suspension state");
            suspended = v != 0;
            engine.reset();
            gyro = {};
            axes();
            status();
        } else if (op == "GYRO") {
            double time, x, y;
            require(bool(in >> time >> x >> y), "Invalid gyro sample");
            lastGyro = seconds();
            gyro = engine.sample(time, x, y, started && hold && !suspended);
            if (started && hold && !suspended) {
                if (engine.settings.stick)
                    axes();
                else
                    moveMouse(gyro.dx, gyro.dy);
            }
        } else {
            throw std::runtime_error("Unknown bridge command");
        }
        return true;
    }
    void loop() {
        std::string buffer;
        while (true) {
            pollfd fds[2]{{socket, POLLIN, 0}, {input.v, POLLIN, 0}};
            int result = poll(fds, 2, 20);
            if (result < 0 && errno == EINTR)
                continue;
            checkIo(result >= 0, "Input poll");
            if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL))
                break;
            if (fds[1].revents & (POLLERR | POLLHUP | POLLNVAL))
                throw std::runtime_error("Controller disconnected");
            // Physical release takes priority over queued gyro samples. Bound the
            // drain so a busy device cannot starve commands or the watchdog.
            if (fds[1].revents & POLLIN) {
                input_event events[64];
                ssize_t count;
                for (int batch = 0; batch < 8; ++batch) {
                    count = read(input.v, events, sizeof(events));
                    if (count < 0 && errno == EINTR)
                        continue;
                    if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
                        break;
                    checkIo(count > 0, "Read controller events");
                    for (size_t i = 0; i < size_t(count) / sizeof(input_event); ++i)
                        event(events[i]);
                }
            }
            if (fds[0].revents & POLLIN) {
                char chunk[4096];
                ssize_t count = read(socket, chunk, sizeof(chunk));
                if (count <= 0)
                    break;
                buffer.append(chunk, count);
                require(buffer.size() < 65536, "Command buffer overflow");
                size_t newline;
                while ((newline = buffer.find('\n')) != std::string::npos) {
                    auto line = buffer.substr(0, newline);
                    buffer.erase(0, newline + 1);
                    if (!command(line))
                        return;
                }
            }
            if (seconds() - lastCommand > 3)
                throw std::runtime_error("App heartbeat lost; controller released");
            if (started && lastGyro != 0 && seconds() - lastGyro > .15) {
                engine.reset();
                gyro = {};
                axes();
                lastGyro = 0;
            }
            if (seconds() - lastStatus > .1)
                status();
        }
    }
    ~Bridge() {
        stop();
    }
};

}  // namespace

extern "C" JNIEXPORT void JNICALL Java_dev_gyrobridge_NativeBridge_run(JNIEnv* env, jobject,
                                                                       jstring socketName, jint uid,
                                                                       jstring secret) {
    const char* n = env->GetStringUTFChars(socketName, nullptr);
    std::string name(n);
    env->ReleaseStringUTFChars(socketName, n);
    const char* t = env->GetStringUTFChars(secret, nullptr);
    std::string token(t);
    env->ReleaseStringUTFChars(secret, t);
    Fd server(::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0)), client;
    try {
        checkIo(server.v >= 0, "Socket creation");
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        checkIo(name.size() < sizeof(address.sun_path) - 1, "Socket name");
        memcpy(address.sun_path + 1, name.data(), name.size());
        checkIo(bind(server.v, reinterpret_cast<sockaddr*>(&address),
                     offsetof(sockaddr_un, sun_path) + 1 + name.size()) == 0,
                "Socket bind");
        checkIo(listen(server.v, 1) == 0, "Socket listen");
        pollfd waiting{server.v, POLLIN, 0};
        checkIo(poll(&waiting, 1, 10000) > 0, "App connection timed out");
        client.v = accept4(server.v, nullptr, nullptr, SOCK_CLOEXEC);
        checkIo(client.v >= 0, "Socket accept");
        ucred peer{};
        socklen_t size = sizeof(peer);
        checkIo(getsockopt(client.v, SOL_SOCKET, SO_PEERCRED, &peer, &size) == 0 &&
                    peer.uid == static_cast<uid_t>(uid),
                "Unexpected socket peer");
        timeval timeout{3, 0};
        setsockopt(client.v, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        std::string auth;
        char ch;
        while (auth.size() < 256 && read(client.v, &ch, 1) == 1 && ch != '\n')
            auth += ch;
        checkIo(auth == "AUTH " + token, "Authentication failed");
        Bridge bridge(env, client.v);
        bridge.loop();
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, "GyroBridgeNative", "%s", e.what());
        if (client.v >= 0) {
            try {
                sendLine(client.v, std::string("ERROR ") + e.what());
            } catch (...) {
            }
        }
    }
}
