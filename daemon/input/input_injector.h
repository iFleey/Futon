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

#ifndef FUTON_INPUT_INPUT_INJECTOR_H
#define FUTON_INPUT_INPUT_INJECTOR_H

#include "core/error.h"
#include "device_cloner.h"
#include "text_injector.h"
#include <linux/input.h>
#include <memory>
#include <vector>
#include <string>
#include <cstdint>

namespace futon::input {

// Touch point for custom injection profiles
    struct TouchPoint {
        int32_t x;
        int32_t y;
        int32_t pressure;
        int32_t touch_major;
    };

// Input injection mode
    enum class InjectionMode {
        UInput,     // High-fidelity uinput device (preferred)
        Shell       // Shell "input" command fallback
    };

// Input injector with high-fidelity touch simulation
    class InputInjector {
    public:
        InputInjector();

        ~InputInjector();

        // Disable copy
        InputInjector(const InputInjector &) = delete;

        InputInjector &operator=(const InputInjector &) = delete;

        // Initialize the injector
        // Attempts uinput first, falls back to shell if needed
        // If device_path is non-empty, uses that specific device instead of auto-detecting
        core::Result<void> initialize(const std::string &device_path = "");

        // Shutdown and release resources
        void shutdown();

        // Inject a tap at (x, y) with configurable duration
        // Uses realistic pressure/area variation curves
        core::Result<void> tap(int32_t x, int32_t y, int32_t duration_ms = 50);

        // Inject a swipe from (x1, y1) to (x2, y2)
        // Uses smooth interpolation with pressure/area variation
        core::Result<void> swipe(int32_t x1, int32_t y1,
                                 int32_t x2, int32_t y2,
                                 int32_t duration_ms);

        // Inject tap with custom pressure/area profile
        core::Result<void> tap_with_profile(int32_t x, int32_t y,
                                            const std::vector<TouchPoint> &profile);

        // Multi-touch action types
        static constexpr int32_t ACTION_DOWN = 0;
        static constexpr int32_t ACTION_UP = 1;
        static constexpr int32_t ACTION_MOVE = 2;

        // Inject multi-touch gesture
        // xs, ys: coordinates for each touch point
        // actions: action for each point (ACTION_DOWN, ACTION_UP, ACTION_MOVE)
        core::Result<void> multi_touch(const std::vector<int32_t> &xs,
                                       const std::vector<int32_t> &ys,
                                       const std::vector<int32_t> &actions);

        // Get screen dimensions from device
        int32_t get_screen_width() const;

        int32_t get_screen_height() const;

        // Get current injection mode
        InjectionMode get_mode() const { return mode_; }

        // Check if initialized
        bool is_initialized() const { return initialized_; }

        // Input text via TextInjector (in-process injection)
        // Falls back to shell command if injection fails
        core::Result<void> input_text(const std::string &text);

        // Press key (via shell command)
        core::Result<void> press_key(int32_t key_code);

    private:
        std::unique_ptr<DeviceCloner> device_cloner_;
        std::unique_ptr<TextInjector> text_injector_;
        InjectionMode mode_ = InjectionMode::Shell;
        bool initialized_ = false;

        // Device path for shell fallback (sendevent)
        std::string device_path_;
        // Cached device info for shell fallback
        int32_t shell_max_x_ = 1080;
        int32_t shell_max_y_ = 2400;
        int32_t shell_pressure_max_ = 255;
        int shell_device_fd_ = -1;

        static constexpr int MAX_EVENTS = 0x4C;
        struct input_event event_buffer_[MAX_EVENTS];
        int event_count_ = 0;
        int32_t tracking_id_counter_ = 0x464C;

        // UInput injection methods
        core::Result<void> tap_uinput(int32_t x, int32_t y, int32_t duration_ms);

        core::Result<void> swipe_uinput(int32_t x1, int32_t y1,
                                        int32_t x2, int32_t y2,
                                        int32_t duration_ms);

        // Shell fallback methods
        core::Result<void> tap_shell(int32_t x, int32_t y, int32_t duration_ms);

        core::Result<void> swipe_shell(int32_t x1, int32_t y1,
                                       int32_t x2, int32_t y2,
                                       int32_t duration_ms);

        // Direct sendevent methods (for shell mode with specific device)
        core::Result<void> tap_sendevent(int32_t x, int32_t y, int32_t duration_ms);

        core::Result<void> swipe_sendevent(int32_t x1, int32_t y1,
                                           int32_t x2, int32_t y2,
                                           int32_t duration_ms);

        // Text input shell methods
        core::Result<void> input_text_shell(const std::string &text);

        core::Result<void> input_text_shell_encoded(const std::string &text);

        // Write single event to device fd
        bool write_sendevent(uint16_t type, uint16_t code, int32_t value);

        // Event helpers
        void add_event(uint16_t type, uint16_t code, int32_t value);

        bool flush_events();

        bool sync_and_flush();

        // MT Protocol B event emission
        void emit_touch_down(int32_t slot, int32_t x, int32_t y,
                             int32_t pressure, int32_t touch_major, int32_t tracking_id);

        void emit_touch_move(int32_t slot, int32_t x, int32_t y,
                             int32_t pressure, int32_t touch_major);

        void emit_touch_up(int32_t slot);

        // Coordinate mapping
        int32_t map_x(int32_t x) const;

        int32_t map_y(int32_t y) const;

        // Pressure/area curve generation
        int32_t generate_pressure(float t, bool is_down) const;

        int32_t generate_touch_major(float t, bool is_down) const;

        // Get next tracking ID
        int32_t get_next_tracking_id();

        // Sleep utilities
        static void sleep_us(int64_t microseconds);

        static void sleep_ms(int64_t milliseconds);
    };

} // namespace futon::input

#endif // FUTON_INPUT_INPUT_INJECTOR_H
