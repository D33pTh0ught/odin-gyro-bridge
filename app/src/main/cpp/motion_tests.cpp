#include <jni.h>

#include <stdexcept>
#include <string>

#include "motion.h"
#ifndef NDEBUG
namespace {
std::string selfTests() {
    auto check = [](bool value, const char* what) {
        if (!value)
            throw std::runtime_error(what);
    };
    MotionEngine e;
    e.settings.smooth = 0;
    e.settings.mouseX = 1;
    e.settings.mouseY = 2;
    e.sample(1, 1, 1, true);
    int x = 0, y = 0;
    for (int i = 1; i <= 200; ++i) {
        auto v = e.sample(1 + i * .005, 1, 1, true);
        x += v.dx;
        y += v.dy;
    }
    check(x == -57 && y == -114, "Corrected default directions and independent gains");
    auto inactive = e.sample(2.005, 10, 10, false);
    check(inactive.dx == 0 && inactive.x == 0, "Inactive output");
    check(e.sample(2.010, 1, 1, true).dx == 0, "No inactive movement accumulates");
    check(std::abs(e.sample(2.015, 1, 1, true).x + .572957795) < .000001, "Stick rate conversion");
    check(mergeAxis(.75f, .5) == 1 && mergeAxis(-.75f, -.5) == -1, "Merge clamps");
    check(std::abs(mergeAxis(.3f, -.2) - .1f) < .00001, "Opposing stick merge");
    e.settings.invertX = true;
    e.reset();
    e.sample(4, 1, 1, true);
    check(e.sample(4.005, 1, 1, true).x > 0, "Inversion reverses corrected defaults");
    e.settings.smooth = 1;
    e.settings.invertX = false;
    e.reset();
    e.sample(5, 0, 0, true);
    auto v = e.sample(5.005, 1, 0, true);
    check(v.x < 0 && v.x > -.1, "Smoothing");
    check(e.sample(6, 1, 1, true).x == 0, "Long gap reset");
    check(e.sample(6.005, NAN, 1, true).x == 0, "Invalid sample reset");
    e.reset();
    e.settings.smooth = .25;
    e.sample(7, 0, 0, true);
    auto step = e.sample(7.01, 1, 1, true);
    check(std::abs(step.x / -.5729577951308232 - (1 - std::exp(-1.))) < .000001,
          "10 ms time constant has 63 percent step response");
    step = e.sample(7.03, 1, 1, true);
    check(std::abs(step.y / -.5729577951308232 - (1 - std::exp(-3.))) < .000001,
          "30 ms reaches 95 percent with variable sample intervals");
    e.settings.smooth = 0;
    e.settings.invertY = true;
    e.reset();
    e.sample(8, 1, 1, true);
    step = e.sample(8.005, 1, 1, true);
    check(step.x < 0 && step.y > 0, "Vertical inversion is independent of horizontal");
    return "PASS: 13 native motion checks";
}
}  // namespace
extern "C" JNIEXPORT jstring JNICALL Java_dev_gyrobridge_NativeBridge_selfTest(JNIEnv* env,
                                                                               jobject) {
    try {
        return env->NewStringUTF(selfTests().c_str());
    } catch (const std::exception& e) {
        return env->NewStringUTF((std::string("FAIL: ") + e.what()).c_str());
    }
}

#endif
