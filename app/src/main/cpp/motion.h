#pragma once
#include <algorithm>
#include <cmath>

struct MotionSettings {
    bool stick = false;
    double smooth = .25, mouseX = 12, mouseY = 12;
    double stickX = 1, stickY = 1;  // percent deflection per degree/second
    bool invertX = false, invertY = false;
};
struct MotionOutput {
    int dx = 0, dy = 0;
    double x = 0, y = 0;
};
class MotionEngine {
    double previous = 0, filteredX = 0, filteredY = 0, fractionX = 0, fractionY = 0;

   public:
    MotionSettings settings;
    void reset() {
        previous = filteredX = filteredY = fractionX = fractionY = 0;
    }
    MotionOutput sample(double time, double rx, double ry, bool active) {
        MotionOutput result;
        if (!active || !std::isfinite(time) || !std::isfinite(rx) || !std::isfinite(ry) ||
            std::abs(rx) > 100 || std::abs(ry) > 100) {
            reset();
            return result;
        }
        const double dt = time - previous;
        previous = time;
        if (dt <= 0 || dt > .1) {
            filteredX = filteredY = fractionX = fractionY = 0;
            return result;
        }
        const double tau = std::clamp(settings.smooth, 0., 1.) * .04;
        const double alpha = tau == 0 ? 1 : -std::expm1(-dt / tau);
        filteredX += alpha * (rx - filteredX);
        filteredY += alpha * (ry - filteredY);
        // Default directions match mouse/camera motion on the Portal. Inversion
        // remains an optional reversal of this baseline, shared by both modes.
        const double dx = filteredX * (180. / 3.141592653589793) * (settings.invertX ? 1 : -1);
        const double dy = filteredY * (180. / 3.141592653589793) * (settings.invertY ? 1 : -1);
        result.x = std::clamp(dx * settings.stickX / 100., -1., 1.);
        result.y = std::clamp(dy * settings.stickY / 100., -1., 1.);
        fractionX += dx * settings.mouseX * dt;
        fractionY += dy * settings.mouseY * dt;
        result.dx = static_cast<int>(fractionX);
        result.dy = static_cast<int>(fractionY);
        fractionX -= result.dx;
        fractionY -= result.dy;
        return result;
    }
};
inline float mergeAxis(float physical, double gyro) {
    return static_cast<float>(std::clamp(static_cast<double>(physical) + gyro, -1., 1.));
}
