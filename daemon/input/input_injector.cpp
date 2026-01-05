/*
 * Futon - Android Automation Daemon
 * Copyright (C) 2025 Fleey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include "input_injector.h"
#include "text_injector.h"
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <cmath>
#include <cstdlib>
#include <cerrno>
#include <time.h>

using namespace futon::core;

namespace futon::input {

    InputInjector::InputInjector() = default;

    InputInjector::~InputInjector() {
        shutdown();
    }

    Result<void> InputInjector::initialize(const std::string &device_path) {
        if (initialized_) {
            return Result<void>::ok();
        }

        FUTON_LOGI("Initializing InputInjector with device_path='%s'...",
                   device_path.empty() ? "auto" : device_path.c_str());

        // Store device path for shell fallback
        device_path_ = device_path;

        // Initialize TextInjector for high-quality text input
        text_injector_ = std::make_unique<TextInjector>();
        auto text_init = text_injector_->initialize();
        if (text_init.is_ok()) {
            FUTON_LOGI("TextInjector initialized successfully");
        } else {
            FUTON_LOGW("TextInjector init failed: %s, will use shell fallback",
                       text_init.message.c_str());
            text_injector_.reset();
        }

        // Try uinput mode first (high-fidelity)
        device_cloner_ = std::make_unique<DeviceCloner>();

        auto discover_result = device_cloner_->discover_physical_device(device_path);
        if (discover_result.is_ok()) {
            // Cache device info for shell fallback before attempting uinput
            const auto &info = device_cloner_->get_physical_info();
            shell_max_x_ = info.abs_x_max;
            shell_max_y_ = info.abs_y_max;
            shell_pressure_max_ = info.abs_pressure_max > 0 ? info.abs_pressure_max : 255;
            if (device_path_.empty()) {
                device_path_ = info.path;  // Store discovered path for shell fallback
            }

            auto clone_result = device_cloner_->clone_to_uinput();
            if (clone_result.is_ok()) {
                mode_ = InjectionMode::UInput;
                initialized_ = true;
                FUTON_LOGI("InputInjector initialized in UInput mode (device: %s)",
                           info.path.c_str());
                return Result<void>::ok();
            }
            FUTON_LOGW("Failed to clone device to uinput: %s, falling back to sendevent mode",
                       clone_result.message.c_str());
        } else {
            FUTON_LOGW("Failed to discover physical device: %s, falling back to shell mode",
                       discover_result.message.c_str());
        }

        // Fallback to shell mode
        device_cloner_.reset();
        mode_ = InjectionMode::Shell;
        initialized_ = true;

        // Try to open device for direct sendevent if we have a path
        if (!device_path_.empty()) {
            shell_device_fd_ = open(device_path_.c_str(), O_WRONLY | O_NONBLOCK);
            if (shell_device_fd_ >= 0) {
                FUTON_LOGI(
                        "InputInjector initialized in Shell mode with direct sendevent (device: %s)",
                        device_path_.c_str());
            } else {
                FUTON_LOGW("Failed to open %s for sendevent: %s, will use 'input' command",
                           device_path_.c_str(), strerror(errno));
            }
        } else {
            FUTON_LOGI("InputInjector initialized in Shell mode (using 'input' command)");
        }

        return Result<void>::ok();
    }

    void InputInjector::shutdown() {
        if (text_injector_) {
            text_injector_->shutdown();
            text_injector_.reset();
        }
        if (device_cloner_) {
            device_cloner_->destroy();
            device_cloner_.reset();
        }
        if (shell_device_fd_ >= 0) {
            close(shell_device_fd_);
            shell_device_fd_ = -1;
        }
        device_path_.clear();
        initialized_ = false;
        FUTON_LOGI("InputInjector shutdown");
    }

    Result<void> InputInjector::tap(int32_t x, int32_t y, int32_t duration_ms) {
        if (!initialized_) {
            return Result<void>::err(FutonError::NotInitialized);
        }

        if (mode_ == InjectionMode::UInput) {
            return tap_uinput(x, y, duration_ms);
        } else {
            return tap_shell(x, y, duration_ms);
        }
    }

    Result<void> InputInjector::swipe(int32_t x1, int32_t y1,
                                      int32_t x2, int32_t y2,
                                      int32_t duration_ms) {
        if (!initialized_) {
            return Result<void>::err(FutonError::NotInitialized);
        }

        if (mode_ == InjectionMode::UInput) {
            return swipe_uinput(x1, y1, x2, y2, duration_ms);
        } else {
            return swipe_shell(x1, y1, x2, y2, duration_ms);
        }
    }

    Result<void> InputInjector::tap_with_profile(int32_t x, int32_t y,
                                                 const std::vector<TouchPoint> &profile) {
        if (!initialized_) {
            return Result<void>::err(FutonError::NotInitialized);
        }

        if (mode_ != InjectionMode::UInput || !device_cloner_ || !device_cloner_->is_ready()) {
            return Result<void>::err(FutonError::NotInitialized);
        }

        if (profile.empty()) {
            return Result<void>::err(FutonError::InvalidArgument);
        }

        int32_t tracking_id = get_next_tracking_id();
        int32_t mapped_x = map_x(x);
        int32_t mapped_y = map_y(y);

        // Touch down with first profile point
        event_count_ = 0;
        emit_touch_down(0, mapped_x, mapped_y,
                        profile[0].pressure, profile[0].touch_major, tracking_id);
        if (!sync_and_flush()) {
            return Result<void>::err(FutonError::InternalError);
        }

        // Apply profile points
        int64_t interval_us = 16667;  // ~60fps
        for (size_t i = 1; i < profile.size(); i++) {
            sleep_us(interval_us);
            event_count_ = 0;
            emit_touch_move(0, mapped_x, mapped_y,
                            profile[i].pressure, profile[i].touch_major);
            if (!sync_and_flush()) {
                return Result<void>::err(FutonError::InternalError);
            }
        }

        // Touch up
        event_count_ = 0;
        emit_touch_up(0);
        if (!sync_and_flush()) {
            return Result<void>::err(FutonError::InternalError);
        }

        return Result<void>::ok();
    }

    int32_t InputInjector::get_screen_width() const {
        if (device_cloner_) {
            return device_cloner_->get_physical_info().abs_x_max;
        }
        return shell_max_x_;  // Use cached value from discovery
    }

    int32_t InputInjector::get_screen_height() const {
        if (device_cloner_) {
            return device_cloner_->get_physical_info().abs_y_max;
        }
        return shell_max_y_;  // Use cached value from discovery
    }

    Result<void> InputInjector::multi_touch(const std::vector<int32_t> &xs,
                                            const std::vector<int32_t> &ys,
                                            const std::vector<int32_t> &actions) {
        if (!initialized_) {
            return Result<void>::err(FutonError::NotInitialized);
        }

        size_t count = xs.size();
        if (count == 0 || count != ys.size() || count != actions.size()) {
            return Result<void>::err(FutonError::InvalidArgument);
        }

        // Shell mode doesn't support multi-touch
        if (mode_ != InjectionMode::UInput || !device_cloner_ || !device_cloner_->is_ready()) {
            FUTON_LOGW("multi_touch: UInput mode required");
            return Result<void>::err(FutonError::NotSupported);
        }

        // Max 10 simultaneous touch points (typical Android limit)
        if (count > 10) {
            FUTON_LOGW("multi_touch: too many touch points (%zu > 10)", count);
            return Result<void>::err(FutonError::InvalidArgument);
        }

        event_count_ = 0;

        for (size_t i = 0; i < count; ++i) {
            int32_t slot = static_cast<int32_t>(i);
            int32_t mapped_x = map_x(xs[i]);
            int32_t mapped_y = map_y(ys[i]);
            int32_t action = actions[i];

            switch (action) {
                case ACTION_DOWN: {
                    int32_t tracking_id = get_next_tracking_id();
                    int32_t pressure = generate_pressure(0.5f, true);
                    int32_t touch_major = generate_touch_major(0.5f, true);
                    emit_touch_down(slot, mapped_x, mapped_y, pressure, touch_major, tracking_id);
                    break;
                }
                case ACTION_MOVE: {
                    int32_t pressure = generate_pressure(0.5f, false);
                    int32_t touch_major = generate_touch_major(0.5f, false);
                    emit_touch_move(slot, mapped_x, mapped_y, pressure, touch_major);
                    break;
                }
                case ACTION_UP: {
                    emit_touch_up(slot);
                    break;
                }
                default:
                    FUTON_LOGW("multi_touch: unknown action %d for slot %d", action, slot);
                    break;
            }
        }

        if (!sync_and_flush()) {
            return Result<void>::err(FutonError::InternalError);
        }

        FUTON_LOGD("multi_touch: injected %zu touch points", count);
        return Result<void>::ok();
    }



// UInput Injection Methods


    Result<void> InputInjector::tap_uinput(int32_t x, int32_t y, int32_t duration_ms) {
        if (!device_cloner_ || !device_cloner_->is_ready()) {
            return Result<void>::err(FutonError::NotInitialized);
        }

        int32_t tracking_id = get_next_tracking_id();
        int32_t mapped_x = map_x(x);
        int32_t mapped_y = map_y(y);

        // Calculate number of frames for the tap duration
        int frames = std::max(1, duration_ms / 16);  // ~60fps
        int64_t frame_interval_us = (duration_ms * 1000) / frames;

        // Touch down with realistic initial pressure/area
        event_count_ = 0;
        int32_t initial_pressure = generate_pressure(0.0f, true);
        int32_t initial_touch_major = generate_touch_major(0.0f, true);
        emit_touch_down(0, mapped_x, mapped_y, initial_pressure, initial_touch_major, tracking_id);
        if (!sync_and_flush()) {
            return Result<void>::err(FutonError::InternalError);
        }

        // Hold with varying pressure/area (simulates finger settling)
        for (int i = 1; i < frames; i++) {
            sleep_us(frame_interval_us);

            float t = static_cast<float>(i) / frames;
            int32_t pressure = generate_pressure(t, false);
            int32_t touch_major = generate_touch_major(t, false);

            event_count_ = 0;
            emit_touch_move(0, mapped_x, mapped_y, pressure, touch_major);
            if (!sync_and_flush()) {
                return Result<void>::err(FutonError::InternalError);
            }
        }

        // Touch up
        event_count_ = 0;
        emit_touch_up(0);
        if (!sync_and_flush()) {
            return Result<void>::err(FutonError::InternalError);
        }

        FUTON_LOGD("Tap injected at (%d, %d) -> mapped (%d, %d), duration %dms",
                   x, y, mapped_x, mapped_y, duration_ms);
        return Result<void>::ok();
    }

    Result<void> InputInjector::swipe_uinput(int32_t x1, int32_t y1,
                                             int32_t x2, int32_t y2,
                                             int32_t duration_ms) {
        if (!device_cloner_ || !device_cloner_->is_ready()) {
            return Result<void>::err(FutonError::NotInitialized);
        }

        int32_t tracking_id = get_next_tracking_id();
        int32_t mapped_x1 = map_x(x1);
        int32_t mapped_y1 = map_y(y1);
        int32_t mapped_x2 = map_x(x2);
        int32_t mapped_y2 = map_y(y2);

        // Calculate number of steps for smooth interpolation (~60fps)
        int steps = std::max(2, duration_ms / 16);
        int64_t step_delay_us = (duration_ms * 1000) / steps;

        // Touch down at start position
        event_count_ = 0;
        int32_t initial_pressure = generate_pressure(0.0f, true);
        int32_t initial_touch_major = generate_touch_major(0.0f, true);
        emit_touch_down(0, mapped_x1, mapped_y1, initial_pressure, initial_touch_major,
                        tracking_id);
        if (!sync_and_flush()) {
            return Result<void>::err(FutonError::InternalError);
        }

        // Interpolate movement with varying pressure/area
        for (int i = 1; i <= steps; i++) {
            sleep_us(step_delay_us);

            float t = static_cast<float>(i) / steps;

            // Linear interpolation for position
            int32_t x = mapped_x1 + static_cast<int32_t>((mapped_x2 - mapped_x1) * t);
            int32_t y = mapped_y1 + static_cast<int32_t>((mapped_y2 - mapped_y1) * t);

            // Realistic pressure/area variation during swipe
            int32_t pressure = generate_pressure(t, false);
            int32_t touch_major = generate_touch_major(t, false);

            event_count_ = 0;
            emit_touch_move(0, x, y, pressure, touch_major);
            if (!sync_and_flush()) {
                return Result<void>::err(FutonError::InternalError);
            }
        }

        // Touch up
        event_count_ = 0;
        emit_touch_up(0);
        if (!sync_and_flush()) {
            return Result<void>::err(FutonError::InternalError);
        }

        FUTON_LOGD("Swipe injected from (%d, %d) to (%d, %d), duration %dms",
                   x1, y1, x2, y2, duration_ms);
        return Result<void>::ok();
    }


// Shell Fallback Methods


    Result<void> InputInjector::tap_shell(int32_t x, int32_t y, int32_t duration_ms) {
        // If we have direct device access, use sendevent for proper device targeting
        if (shell_device_fd_ >= 0) {
            return tap_sendevent(x, y, duration_ms);
        }

        // Fallback to 'input' command (uses default device)
        // Note: Input commands need to be synchronous to maintain order
        // We add output redirection to minimize potential issues
        char cmd[256];

        if (duration_ms > 100) {
            // Long press - use swipe with same start/end
            snprintf(cmd, sizeof(cmd), "input swipe %d %d %d %d %d > /dev/null 2>&1",
                     x, y, x, y, duration_ms);
        } else {
            // Regular tap
            snprintf(cmd, sizeof(cmd), "input tap %d %d > /dev/null 2>&1", x, y);
        }

        int ret = system(cmd);
        if (ret != 0) {
            FUTON_LOGE("Shell tap command failed: %s (ret=%d)", cmd, ret);
            return Result<void>::err(FutonError::InternalError);
        }

        FUTON_LOGD("Shell tap at (%d, %d)", x, y);
        return Result<void>::ok();
    }

    Result<void> InputInjector::swipe_shell(int32_t x1, int32_t y1,
                                            int32_t x2, int32_t y2,
                                            int32_t duration_ms) {
        // If we have direct device access, use sendevent for proper device targeting
        if (shell_device_fd_ >= 0) {
            return swipe_sendevent(x1, y1, x2, y2, duration_ms);
        }

        // Fallback to 'input' command (uses default device)
        // Note: Input commands need to be synchronous to maintain order
        char cmd[256];
        snprintf(cmd, sizeof(cmd), "input swipe %d %d %d %d %d > /dev/null 2>&1",
                 x1, y1, x2, y2, duration_ms);

        int ret = system(cmd);
        if (ret != 0) {
            FUTON_LOGE("Shell swipe command failed: %s (ret=%d)", cmd, ret);
            return Result<void>::err(FutonError::InternalError);
        }

        FUTON_LOGD("Shell swipe from (%d, %d) to (%d, %d), duration %dms",
                   x1, y1, x2, y2, duration_ms);
        return Result<void>::ok();
    }

    // Direct sendevent implementation for shell mode with specific device
    Result<void> InputInjector::tap_sendevent(int32_t x, int32_t y, int32_t duration_ms) {
        if (shell_device_fd_ < 0) {
            return Result<void>::err(FutonError::NotInitialized);
        }

        int32_t tracking_id = get_next_tracking_id();
        int32_t pressure = shell_pressure_max_ / 2;  // Mid-range pressure

        // Touch down
        if (!write_sendevent(EV_ABS, ABS_MT_TRACKING_ID, tracking_id))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_ABS, ABS_MT_POSITION_X, x))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_ABS, ABS_MT_POSITION_Y, y))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_ABS, ABS_MT_PRESSURE, pressure))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_KEY, BTN_TOUCH, 1))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_SYN, SYN_REPORT, 0))
            return Result<void>::err(FutonError::InternalError);

        // Hold for duration
        if (duration_ms > 0) {
            sleep_ms(duration_ms);
        } else {
            sleep_ms(50);  // Default tap duration
        }

        // Touch up
        if (!write_sendevent(EV_ABS, ABS_MT_TRACKING_ID, -1))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_KEY, BTN_TOUCH, 0))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_SYN, SYN_REPORT, 0))
            return Result<void>::err(FutonError::InternalError);

        FUTON_LOGD("Sendevent tap at (%d, %d) on %s", x, y, device_path_.c_str());
        return Result<void>::ok();
    }

    Result<void> InputInjector::swipe_sendevent(int32_t x1, int32_t y1,
                                                int32_t x2, int32_t y2,
                                                int32_t duration_ms) {
        if (shell_device_fd_ < 0) {
            return Result<void>::err(FutonError::NotInitialized);
        }

        int32_t tracking_id = get_next_tracking_id();
        int32_t pressure = shell_pressure_max_ / 2;

        // Calculate steps for smooth interpolation (~60fps)
        int steps = std::max(2, duration_ms / 16);
        int64_t step_delay_us = (duration_ms * 1000) / steps;

        // Touch down at start position
        if (!write_sendevent(EV_ABS, ABS_MT_TRACKING_ID, tracking_id))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_ABS, ABS_MT_POSITION_X, x1))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_ABS, ABS_MT_POSITION_Y, y1))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_ABS, ABS_MT_PRESSURE, pressure))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_KEY, BTN_TOUCH, 1))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_SYN, SYN_REPORT, 0))
            return Result<void>::err(FutonError::InternalError);

        // Interpolate movement
        for (int i = 1; i <= steps; i++) {
            sleep_us(step_delay_us);

            float t = static_cast<float>(i) / steps;
            int32_t x = x1 + static_cast<int32_t>((x2 - x1) * t);
            int32_t y = y1 + static_cast<int32_t>((y2 - y1) * t);

            if (!write_sendevent(EV_ABS, ABS_MT_POSITION_X, x))
                return Result<void>::err(FutonError::InternalError);
            if (!write_sendevent(EV_ABS, ABS_MT_POSITION_Y, y))
                return Result<void>::err(FutonError::InternalError);
            if (!write_sendevent(EV_SYN, SYN_REPORT, 0))
                return Result<void>::err(FutonError::InternalError);
        }

        // Touch up
        if (!write_sendevent(EV_ABS, ABS_MT_TRACKING_ID, -1))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_KEY, BTN_TOUCH, 0))
            return Result<void>::err(FutonError::InternalError);
        if (!write_sendevent(EV_SYN, SYN_REPORT, 0))
            return Result<void>::err(FutonError::InternalError);

        FUTON_LOGD("Sendevent swipe from (%d, %d) to (%d, %d) on %s",
                   x1, y1, x2, y2, device_path_.c_str());
        return Result<void>::ok();
    }

    bool InputInjector::write_sendevent(uint16_t type, uint16_t code, int32_t value) {
        struct input_event ev;
        memset(&ev, 0, sizeof(ev));

        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        ev.input_event_sec = ts.tv_sec;
        ev.input_event_usec = ts.tv_nsec / 1000;
        ev.type = type;
        ev.code = code;
        ev.value = value;

        ssize_t written = write(shell_device_fd_, &ev, sizeof(ev));
        if (written != sizeof(ev)) {
            FUTON_LOGE("write_sendevent failed: wrote %zd of %zu bytes, errno=%d (%s)",
                       written, sizeof(ev), errno, strerror(errno));
            return false;
        }
        return true;
    }



// Event Helpers


    void InputInjector::add_event(uint16_t type, uint16_t code, int32_t value) {
        if (event_count_ >= MAX_EVENTS) {
            FUTON_LOGE("Event buffer overflow!");
            return;
        }

        struct input_event &ev = event_buffer_[event_count_++];
        memset(&ev, 0, sizeof(ev));
        ev.type = type;
        ev.code = code;
        ev.value = value;

        // Set timestamp
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        ev.input_event_sec = ts.tv_sec;
        ev.input_event_usec = ts.tv_nsec / 1000;
    }

    bool InputInjector::flush_events() {
        if (event_count_ == 0) return true;

        if (!device_cloner_ || !device_cloner_->is_ready()) {
            return false;
        }

        int fd = device_cloner_->get_uinput_fd();
        ssize_t bytes_to_write = event_count_ * sizeof(struct input_event);
        ssize_t bytes_written = write(fd, event_buffer_, bytes_to_write);

        if (bytes_written != bytes_to_write) {
            FUTON_LOGE("Write failed: wrote %zd of %zd bytes, errno=%d (%s)",
                       bytes_written, bytes_to_write, errno, strerror(errno));
            event_count_ = 0;
            return false;
        }

        event_count_ = 0;
        return true;
    }

    bool InputInjector::sync_and_flush() {
        add_event(EV_SYN, SYN_REPORT, 0);
        return flush_events();
    }


// MT Protocol B Event Emission


    void InputInjector::emit_touch_down(int32_t slot, int32_t x, int32_t y,
                                        int32_t pressure, int32_t touch_major,
                                        int32_t tracking_id) {
        add_event(EV_ABS, ABS_MT_SLOT, slot);
        add_event(EV_ABS, ABS_MT_TRACKING_ID, tracking_id);
        add_event(EV_ABS, ABS_MT_POSITION_X, x);
        add_event(EV_ABS, ABS_MT_POSITION_Y, y);
        add_event(EV_ABS, ABS_MT_PRESSURE, pressure);
        add_event(EV_ABS, ABS_MT_TOUCH_MAJOR, touch_major);

        if (slot == 0) {
            add_event(EV_KEY, BTN_TOUCH, 1);
        }
    }

    void InputInjector::emit_touch_move(int32_t slot, int32_t x, int32_t y,
                                        int32_t pressure, int32_t touch_major) {
        add_event(EV_ABS, ABS_MT_SLOT, slot);
        add_event(EV_ABS, ABS_MT_POSITION_X, x);
        add_event(EV_ABS, ABS_MT_POSITION_Y, y);
        add_event(EV_ABS, ABS_MT_PRESSURE, pressure);
        add_event(EV_ABS, ABS_MT_TOUCH_MAJOR, touch_major);
    }

    void InputInjector::emit_touch_up(int32_t slot) {
        add_event(EV_ABS, ABS_MT_SLOT, slot);
        add_event(EV_ABS, ABS_MT_TRACKING_ID, -1);

        if (slot == 0) {
            add_event(EV_KEY, BTN_TOUCH, 0);
        }
    }


// Coordinate Mapping


    int32_t InputInjector::map_x(int32_t x) const {
        if (!device_cloner_) {
            return x;
        }

        const auto &info = device_cloner_->get_physical_info();

        // Map from screen coordinates to device coordinates
        // Assuming input x is in screen pixel coordinates
        int32_t range = info.abs_x_max - info.abs_x_min;
        if (range <= 0) {
            return x;
        }

        // Clamp to valid range
        if (x < 0) x = 0;
        if (x > info.abs_x_max) x = info.abs_x_max;

        return info.abs_x_min + x;
    }

    int32_t InputInjector::map_y(int32_t y) const {
        if (!device_cloner_) {
            return y;
        }

        const auto &info = device_cloner_->get_physical_info();

        // Map from screen coordinates to device coordinates
        int32_t range = info.abs_y_max - info.abs_y_min;
        if (range <= 0) {
            return y;
        }

        // Clamp to valid range
        if (y < 0) y = 0;
        if (y > info.abs_y_max) y = info.abs_y_max;

        return info.abs_y_min + y;
    }


// Pressure/Area Curve Generation


    int32_t InputInjector::generate_pressure(float t, bool is_down) const {
        if (!device_cloner_) {
            return 50;  // Default
        }

        const auto &info = device_cloner_->get_physical_info();
        int32_t range = info.abs_pressure_max - info.abs_pressure_min;

        // Realistic pressure curve:
        // - Quick ramp up on touch down
        // - Slight variation during hold
        // - Quick ramp down on touch up

        float pressure_factor;
        if (is_down) {
            // Initial touch - quick ramp up
            pressure_factor = 0.3f + 0.5f * t;
        } else if (t < 0.1f) {
            // Just after touch down - settling
            pressure_factor = 0.8f + 0.15f * (t / 0.1f);
        } else if (t > 0.9f) {
            // About to lift - decreasing
            pressure_factor = 0.95f - 0.3f * ((t - 0.9f) / 0.1f);
        } else {
            // Middle of gesture - slight variation
            pressure_factor = 0.85f + 0.1f * sinf(t * 3.14159f * 4);
        }

        // Add small random variation for realism
        float noise = (static_cast<float>(rand() % 100) / 1000.0f) - 0.05f;
        pressure_factor += noise;

        // Clamp
        if (pressure_factor < 0.2f) pressure_factor = 0.2f;
        if (pressure_factor > 1.0f) pressure_factor = 1.0f;

        return info.abs_pressure_min + static_cast<int32_t>(range * pressure_factor);
    }

    int32_t InputInjector::generate_touch_major(float t, bool is_down) const {
        if (!device_cloner_) {
            return 10;  // Default
        }

        const auto &info = device_cloner_->get_physical_info();
        int32_t range = info.abs_touch_major_max - info.abs_touch_major_min;

        // Realistic touch area curve:
        // - Starts small, expands as finger settles
        // - Slight variation during hold
        // - Decreases before lift

        float area_factor;
        if (is_down) {
            // Initial touch - small area
            area_factor = 0.2f + 0.3f * t;
        } else if (t < 0.15f) {
            // Settling - area expands
            area_factor = 0.5f + 0.3f * (t / 0.15f);
        } else if (t > 0.85f) {
            // About to lift - area decreases
            area_factor = 0.8f - 0.4f * ((t - 0.85f) / 0.15f);
        } else {
            // Middle - stable with slight variation
            area_factor = 0.75f + 0.05f * sinf(t * 3.14159f * 2);
        }

        // Add small random variation
        float noise = (static_cast<float>(rand() % 100) / 1000.0f) - 0.05f;
        area_factor += noise;

        // Clamp
        if (area_factor < 0.1f) area_factor = 0.1f;
        if (area_factor > 1.0f) area_factor = 1.0f;

        return info.abs_touch_major_min + static_cast<int32_t>(range * area_factor);
    }


// Utilities


    int32_t InputInjector::get_next_tracking_id() {
        return tracking_id_counter_++;
    }

    void InputInjector::sleep_us(int64_t microseconds) {
        struct timespec ts;
        ts.tv_sec = microseconds / 1000000;
        ts.tv_nsec = (microseconds % 1000000) * 1000;
        nanosleep(&ts, nullptr);
    }

    void InputInjector::sleep_ms(int64_t milliseconds) {
        sleep_us(milliseconds * 1000);
    }

    /**
     * Check if string contains only ASCII characters (0x00-0x7F).
     * ASCII text can be reliably handled by shell 'input text' command.
     */
    static bool is_ascii_only(const std::string &text) {
        for (unsigned char c: text) {
            if (c > 0x7F) {
                return false;
            }
        }
        return true;
    }

    Result<void> InputInjector::input_text(const std::string &text) {
        if (!initialized_) {
            return Result<void>::err(FutonError::NotInitialized);
        }

        if (text.empty()) {
            return Result<void>::ok();
        }

        // Strategy:
        // 1. Pure ASCII  use shell 'input text' (fast, reliable)
        // 2. Contains Unicode  use TextInjector (ptrace injection)
        // 3. TextInjector fails  return error (shell doesn't support Unicode well)

        bool ascii_only = is_ascii_only(text);

        if (ascii_only) {
            // Fast path: ASCII text uses shell command directly
            FUTON_LOGD("input_text: ASCII text, using shell command");
            auto result = input_text_shell(text);
            if (result.is_ok()) {
                return result;
            }
            FUTON_LOGW("Shell input failed for ASCII text: %s", result.message.c_str());
        }

        // Unicode text or ASCII shell failed: use TextInjector
        if (text_injector_ && text_injector_->is_available()) {
            FUTON_LOGD("input_text: using TextInjector for %s text",
                       ascii_only ? "ASCII (fallback)" : "Unicode");
            auto result = text_injector_->inject_text(text, 2000);  // 2 second timeout
            if (result.is_ok()) {
                FUTON_LOGD("Text injected via TextInjector");
                return Result<void>::ok();
            }
            FUTON_LOGE("TextInjector failed: %s", result.message.c_str());
        } else {
            FUTON_LOGE("TextInjector not available for Unicode text");
        }

        return Result<void>::err(FutonError::InternalError,
                                 ascii_only ? "Shell input failed"
                                            : "Unicode input requires TextInjector which failed");
    }

    Result<void> InputInjector::input_text_shell(const std::string &text) {
        // Escape shell special characters
        std::string escaped;
        escaped.reserve(text.size() * 2);
        for (char c: text) {
            if (c == '\'' || c == '\\' || c == '"' || c == '$' || c == '`') {
                escaped += '\\';
            }
            escaped += c;
        }

        std::string cmd = "input text '" + escaped + "' > /dev/null 2>&1";
        int ret = system(cmd.c_str());

        if (ret != 0) {
            return Result<void>::err(FutonError::InternalError, "input text command failed");
        }

        return Result<void>::ok();
    }

    Result<void> InputInjector::input_text_shell_encoded(const std::string &text) {
        // Percent-encode the text for shell 'input text' command
        // Android's 'input text' supports %XX encoding for special characters
        std::string encoded;
        encoded.reserve(text.size() * 3);

        for (unsigned char c: text) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                encoded += static_cast<char>(c);
            } else {
                char hex[4];
                snprintf(hex, sizeof(hex), "%%%02X", c);
                encoded += hex;
            }
        }

        std::string cmd = "input text '" + encoded + "' > /dev/null 2>&1";
        int ret = system(cmd.c_str());

        if (ret != 0) {
            return Result<void>::err(FutonError::InternalError, "input text encoded command failed");
        }

        return Result<void>::ok();
    }

    Result<void> InputInjector::press_key(int32_t key_code) {
        if (!initialized_) {
            return Result<void>::err(FutonError::NotInitialized);
        }

        std::string cmd = "input keyevent " + std::to_string(key_code) + " > /dev/null 2>&1";
        int ret = system(cmd.c_str());

        if (ret != 0) {
            FUTON_LOGW("press_key failed with code %d for keycode %d", ret, key_code);
            return Result<void>::err(FutonError::InternalError, "input keyevent command failed");
        }

        return Result<void>::ok();
    }

} // namespace futon::input
