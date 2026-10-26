# GyroBridge for Odin 2 Portal

System-wide gyroscope aiming as a mouse or an addition to the right stick. Uses AYN's built-in service without rooting, firmware changes, or an ADB connection during use.

[Download the latest APK](https://github.com/D33pTh0ught/odin-gyro-bridge/releases/latest).

## Capabilities

- **Mouse or right-stick output.** Stick mode combines gyro movement with the physical right stick.
- **Hold to activate.** Assign a grip key, trigger, or other controller button using **Press to assign**.
- **Use button only for gyro.** Prevents the assigned digital button from also reaching the game, in either output mode. Analog triggers always reach the game.
- **Separate horizontal and vertical sensitivity for each mode**, with axis inversion.
- **Smoothing time constant (0–40 ms).** Controls how quickly the filter follows changes, rather than adding a fixed delay. Higher values reduce jitter but slow the response; zero disables filtering.
- **Gyro calibration and optional startup after boot.** Keep the device still for two seconds when calibrating. The background service pauses gyro processing while the screen is off and can be stopped from its notification.

## Compatibility

Verified on **Odin 2 Portal**, Android 13, firmware `Odin2Portal_V1.0.0.338_20260319_184645_user`. Other controller styles may work if they expose the same controller identity and axis layout, but have not been verified. Firmware updates may affect compatibility with AYN's built-in service.

---

<sub>Created with assistance from GPT-6.1 sol.</sub>
