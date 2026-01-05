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

#ifndef FUTON_VISION_EGL_EGL_ENVIRONMENT_H
#define FUTON_VISION_EGL_EGL_ENVIRONMENT_H

#include "core/error.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <thread>
#include <cstdint>
#include <optional>

namespace futon::vision {

/**
 * EGL configuration options.
 */
    struct EglConfig {
        int red_size = 8;
        int green_size = 8;
        int blue_size = 8;
        int alpha_size = 8;
        int depth_size = 0;
        int stencil_size = 0;
        int pbuffer_width = 1;
        int pbuffer_height = 1;
        bool require_es3 = true;
        bool require_es31 = true;  // Required for compute shaders
    };

/**
 * EglEnvironment - Native EGL environment without Java dependencies.
 *
 * Provides a complete EGL setup for offscreen rendering:
 * - EGL Display initialization
 * - EGL Config selection (OpenGL ES 3.1 for compute shaders)
 * - PBuffer Surface creation (no window/Activity required)
 * - EGL Context creation and management
 *
 * This class is designed to be used independently of any Android
 * Activity or SurfaceView, enabling pure native GPU operations.
 *
 * Thread safety:
 * - EGL context is bound to a single thread at a time
 * - Use make_current()/release_current() for thread migration
 */
    class EglEnvironment {
    public:
        EglEnvironment();

        ~EglEnvironment();

        // Disable copy
        EglEnvironment(const EglEnvironment &) = delete;

        EglEnvironment &operator=(const EglEnvironment &) = delete;

        // Enable move
        EglEnvironment(EglEnvironment &&other) noexcept;

        EglEnvironment &operator=(EglEnvironment &&other) noexcept;

        /**
         * Initialize the EGL environment.
         * Creates display, selects config, creates pbuffer surface and context.
         * @param config EGL configuration options
         * @return true on success
         */
        bool initialize(const EglConfig &config = EglConfig{});

        /**
         * Shutdown and release all EGL resources.
         */
        void shutdown();

        /**
         * Check if environment is initialized.
         */
        bool is_initialized() const { return initialized_; }

        /**
         * Make EGL context current on the calling thread.
         * @return true on success
         */
        bool make_current();

        /**
         * Release EGL context from current thread.
         */
        void release_current();

        /**
         * Check if context is current on calling thread.
         */
        bool is_current() const;

        /**
         * Get the thread ID that currently owns the context.
         */
        std::thread::id get_bound_thread_id() const { return bound_thread_id_; }

        // Accessors for EGL objects
        EGLDisplay get_display() const { return display_; }

        EGLContext get_context() const { return context_; }

        EGLSurface get_surface() const { return surface_; }

        EGLConfig get_config() const { return config_; }

        /**
         * Get EGL version (major.minor).
         */
        void get_version(int *major, int *minor) const;

        /**
         * Query EGL extensions string.
         */
        const char *get_extensions() const;

        /**
         * Check if a specific EGL extension is supported.
         */
        bool has_extension(const char *extension) const;

        /**
         * Get GL vendor string (requires context to be current).
         */
        const char *get_gl_vendor() const;

        /**
         * Get GL renderer string (requires context to be current).
         */
        const char *get_gl_renderer() const;

        /**
         * Get GL version string (requires context to be current).
         */
        const char *get_gl_version() const;

    private:
        bool initialized_ = false;
        std::thread::id bound_thread_id_;

        // EGL state
        EGLDisplay display_ = EGL_NO_DISPLAY;
        EGLContext context_ = EGL_NO_CONTEXT;
        EGLSurface surface_ = EGL_NO_SURFACE;
        EGLConfig config_ = nullptr;

        // Version info
        int egl_major_ = 0;
        int egl_minor_ = 0;

        // Cached extension string
        const char *extensions_ = nullptr;

        // Internal initialization steps
        bool init_display();

        bool choose_config(const EglConfig &config);

        bool create_pbuffer_surface(const EglConfig &config);

        bool create_context(const EglConfig &config);
    };

/**
 * RAII guard for EGL context binding.
 *
 * Automatically makes context current on construction and releases on destruction.
 * This solves the "Context Bounding" problem where EGL contexts must be explicitly
 * unbound before another thread can use them.
 *
 * Usage:
 *   {
 *       auto scope = EglScopedContext::bind(egl_env);
 *       if (!scope) return error;
 *       // GPU operations here...
 *   } // Context automatically released
 *
 * Note: This class is defined after EglEnvironment to allow inline method
 * implementations that call EglEnvironment methods.
 */
    class EglScopedContext {
    public:
        ~EglScopedContext() {
            if (env_ && bound_) {
                env_->release_current();
            }
        }

        // Move only
        EglScopedContext(EglScopedContext &&other) noexcept
                : env_(other.env_), bound_(other.bound_) {
            other.env_ = nullptr;
            other.bound_ = false;
        }

        EglScopedContext &operator=(EglScopedContext &&other) noexcept {
            if (this != &other) {
                if (env_ && bound_) {
                    env_->release_current();
                }
                env_ = other.env_;
                bound_ = other.bound_;
                other.env_ = nullptr;
                other.bound_ = false;
            }
            return *this;
        }

        // No copy
        EglScopedContext(const EglScopedContext &) = delete;

        EglScopedContext &operator=(const EglScopedContext &) = delete;

        /**
         * Bind EGL context to current thread with RAII guard.
         * @param env EGL environment to bind
         * @return Optional containing guard on success, empty on failure
         */
        static std::optional<EglScopedContext> bind(EglEnvironment *env) {
            if (!env) {
                return std::nullopt;
            }
            if (!env->make_current()) {
                return std::nullopt;
            }
            return EglScopedContext(env);
        }

        /**
         * Bind only if context is not already current on this thread.
         * More efficient when context might already be bound.
         */
        static std::optional<EglScopedContext> bind_if_needed(EglEnvironment *env) {
            if (!env) {
                return std::nullopt;
            }
            if (env->is_current()) {
                // Already current, return guard that won't release
                return EglScopedContext(env, false);
            }
            if (!env->make_current()) {
                return std::nullopt;
            }
            return EglScopedContext(env);
        }

        explicit operator bool() const { return bound_ || (env_ && env_->is_current()); }

    private:
        explicit EglScopedContext(EglEnvironment *env, bool will_release = true)
                : env_(env), bound_(will_release) {}

        EglEnvironment *env_ = nullptr;
        bool bound_ = false;
    };

} // namespace futon::vision

#endif // FUTON_VISION_EGL_EGL_ENVIRONMENT_H
