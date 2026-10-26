#pragma once
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <stdexcept>
#include <string>

namespace gyrobridge {
inline constexpr std::array<int, 8> controllerAxes{
    ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_BRAKE, ABS_GAS, ABS_HAT0X, ABS_HAT0Y,
};

inline double seconds() {
    return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

inline void require(bool condition, const std::string& message) {
    if (!condition)
        throw std::runtime_error(message);
}

inline void checkIo(bool condition, const std::string& operation) {
    if (!condition)
        throw std::runtime_error(operation + ": " + strerror(errno));
}

struct Fd {
    int v = -1;
    Fd() = default;
    explicit Fd(int value) : v(value) {}
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
    void reset(int value = -1) noexcept {
        if (v >= 0)
            close(v);
        v = value;
    }
    ~Fd() {
        reset();
    }
};

inline void destroyVirtualDevice(Fd& device) noexcept {
    if (device.v >= 0)
        ioctl(device.v, UI_DEV_DESTROY);
    device.reset();
}

inline void writeInputEvents(int fd, const input_event* events, size_t count) {
    const auto bytes = sizeof(input_event) * count;
    ssize_t written;
    do {
        written = write(fd, events, bytes);
    } while (written < 0 && errno == EINTR);
    checkIo(written == static_cast<ssize_t>(bytes), "Virtual input write");
}

inline void sendLine(int fd, const std::string& text) {
    const std::string line = text + '\n';
    ssize_t written;
    do {
        written = send(fd, line.data(), line.size(), MSG_NOSIGNAL | MSG_DONTWAIT);
    } while (written < 0 && errno == EINTR);
    // Fail open on a blocked/disconnected app instead of hanging with input grabbed.
    checkIo(written == static_cast<ssize_t>(line.size()), "App connection lost");
}
}  // namespace gyrobridge
