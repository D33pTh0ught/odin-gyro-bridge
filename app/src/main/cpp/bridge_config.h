#pragma once
#include <sstream>

#include "input_io.h"
#include "motion.h"

namespace gyrobridge {
struct BridgeConfig {
    MotionSettings motion;
    int activationType = EV_KEY;
    int activationCode = BTN_TL;
    bool consume = false;
};

inline BridgeConfig readConfig(std::istream& input) {
    int mode, type, code, consume, invertX, invertY;
    double smooth, mouseX, mouseY, stickX, stickY;
    require(bool(input >> mode >> type >> code >> consume >> smooth >> mouseX >> mouseY >> stickX >>
                 stickY >> invertX >> invertY),
            "Incomplete input settings");
    input >> std::ws;
    require(input.eof(), "Unexpected input settings");
    const auto boolean = [](int value) { return value == 0 || value == 1; };
    require(boolean(mode) && boolean(consume) && boolean(invertX) && boolean(invertY),
            "Invalid input flags");
    require((type == EV_KEY && code > KEY_RESERVED && code < KEY_CNT) ||
                (type == EV_ABS && (code == ABS_BRAKE || code == ABS_GAS)),
            "Invalid activation input");
    require(smooth >= 0 && smooth <= 1 && mouseX >= .1 && mouseX <= 60 && mouseY >= .1 &&
                mouseY <= 60 && stickX >= .1 && stickX <= 10 && stickY >= .1 && stickY <= 10,
            "Invalid sensitivity");
    return {{mode == 1, smooth, mouseX, mouseY, stickX, stickY, invertX != 0, invertY != 0},
            type,
            code,
            consume != 0 && type == EV_KEY};
}
}  // namespace gyrobridge
