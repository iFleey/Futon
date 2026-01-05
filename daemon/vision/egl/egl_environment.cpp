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

#include "vision/egl/egl_environment.h"
#include "core/error.h"

#include <GLES3/gl3.h>
#include <cstring>

using namespace futon::core;

namespace futon::vision {

    EglEnvironment::EglEnvironment() = default;

    EglEnvironment::~EglEnvironment() {
        shutdown();
    }

    EglEnvironment::EglEnvironment(EglEnvironment &&other) noexcept
            : initialized_(other.initialized_), bound_thread_id_(other.bound_thread_id_),
              display_(other.display_), context_(other.context_), surface_(other.surface_),
              config_(other.config_), egl_major_(other.egl_major_), egl_minor_(other.egl_minor_),
              extensions_(other.extensions_) {
        other.initialized_ = false;
        other.display_ = EGL_NO_DISPLAY;
        other.context_ = EGL_NO_CONTEXT;
        other.surface_ = EGL_NO_SURFACE;
        other.config_ = nullptr;
        other.extensions_ = nullptr;
    }

    EglEnvironment &EglEnvironment::operator=(EglEnvironment &&other) noexcept {
        if (this != &other) {
            shutdown();

            initialized_ = other.initialized_;
            bound_thread_id_ = other.bound_thread_id_;
            display_ = other.display_;
            context_ = other.context_;
            surface_ = other.surface_;
            config_ = other.config_;
            egl_major_ = other.egl_major_;
            egl_minor_ = other.egl_minor_;
            extensions_ = other.extensions_;

            other.initialized_ = false;
            other.display_ = EGL_NO_DISPLAY;
            other.context_ = EGL_NO_CONTEXT;
            other.surface_ = EGL_NO_SURFACE;
            other.config_ = nullptr;
            other.extensions_ = nullptr;
        }
        return *this;
    }

    bool EglEnvironment::initialize(const EglConfig &config) {
        if (initialized_) {
            FUTON_LOGW("EglEnvironment: already initialized");
            return true;
        }

        FUTON_LOGI("EglEnvironment: initializing native EGL environment");

        if (!init_display()) {
            FUTON_LOGE("EglEnvironment: failed to initialize display");
            shutdown();
            return false;
        }

        if (!choose_config(config)) {
            FUTON_LOGE("EglEnvironment: failed to choose config");
            shutdown();
            return false;
        }

        if (!create_pbuffer_surface(config)) {
            FUTON_LOGE("EglEnvironment: failed to create pbuffer surface");
            shutdown();
            return false;
        }

        if (!create_context(config)) {
            FUTON_LOGE("EglEnvironment: failed to create context");
            shutdown();
            return false;
        }

        if (!make_current()) {
            FUTON_LOGE("EglEnvironment: failed to make context current");
            shutdown();
            return false;
        }

        initialized_ = true;
        FUTON_LOGI("EglEnvironment: initialized successfully (EGL %d.%d)", egl_major_, egl_minor_);
        FUTON_LOGD("EglEnvironment: GL_VENDOR: %s", get_gl_vendor());
        FUTON_LOGD("EglEnvironment: GL_RENDERER: %s", get_gl_renderer());
        FUTON_LOGD("EglEnvironment: GL_VERSION: %s", get_gl_version());

        return true;
    }

    void EglEnvironment::shutdown() {
        if (!initialized_ && display_ == EGL_NO_DISPLAY) {
            return;
        }

        FUTON_LOGD("EglEnvironment: shutting down");

        if (display_ != EGL_NO_DISPLAY) {
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }

        if (surface_ != EGL_NO_SURFACE && display_ != EGL_NO_DISPLAY) {
            eglDestroySurface(display_, surface_);
            surface_ = EGL_NO_SURFACE;
        }

        if (context_ != EGL_NO_CONTEXT && display_ != EGL_NO_DISPLAY) {
            eglDestroyContext(display_, context_);
            context_ = EGL_NO_CONTEXT;
        }

        if (display_ != EGL_NO_DISPLAY) {
            eglTerminate(display_);
            display_ = EGL_NO_DISPLAY;
        }

        config_ = nullptr;
        extensions_ = nullptr;
        egl_major_ = 0;
        egl_minor_ = 0;
        initialized_ = false;

        FUTON_LOGD("EglEnvironment: shutdown complete");
    }

    bool EglEnvironment::init_display() {
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display_ == EGL_NO_DISPLAY) {
            FUTON_LOGE("eglGetDisplay(EGL_DEFAULT_DISPLAY) failed: 0x%x", eglGetError());
            return false;
        }

        if (!eglInitialize(display_, &egl_major_, &egl_minor_)) {
            FUTON_LOGE("eglInitialize failed: 0x%x", eglGetError());
            return false;
        }

        FUTON_LOGD("EGL initialized: version %d.%d", egl_major_, egl_minor_);

        extensions_ = eglQueryString(display_, EGL_EXTENSIONS);
        if (extensions_) {
            FUTON_LOGD("EGL extensions available");
        }

        return true;
    }

    bool EglEnvironment::choose_config(const EglConfig &config) {
        EGLint renderable_type = EGL_OPENGL_ES2_BIT;
        if (config.require_es3 || config.require_es31) {
            renderable_type = EGL_OPENGL_ES3_BIT;
        }

        EGLint config_attribs[] = {
                EGL_RENDERABLE_TYPE, renderable_type,
                EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                EGL_RED_SIZE, config.red_size,
                EGL_GREEN_SIZE, config.green_size,
                EGL_BLUE_SIZE, config.blue_size,
                EGL_ALPHA_SIZE, config.alpha_size,
                EGL_DEPTH_SIZE, config.depth_size,
                EGL_STENCIL_SIZE, config.stencil_size,
                EGL_NONE
        };

        EGLint num_configs = 0;
        if (!eglChooseConfig(display_, config_attribs, &config_, 1, &num_configs)) {
            FUTON_LOGE("eglChooseConfig failed: 0x%x", eglGetError());
            return false;
        }

        if (num_configs == 0) {
            FUTON_LOGE("eglChooseConfig: no matching configs found");
            return false;
        }

        FUTON_LOGD("EGL config selected (num_configs=%d)", num_configs);
        return true;
    }

    bool EglEnvironment::create_pbuffer_surface(const EglConfig &config) {
        EGLint pbuffer_attribs[] = {
                EGL_WIDTH, config.pbuffer_width,
                EGL_HEIGHT, config.pbuffer_height,
                EGL_NONE
        };

        surface_ = eglCreatePbufferSurface(display_, config_, pbuffer_attribs);
        if (surface_ == EGL_NO_SURFACE) {
            FUTON_LOGE("eglCreatePbufferSurface failed: 0x%x", eglGetError());
            return false;
        }

        FUTON_LOGD("PBuffer surface created (%dx%d)",
                   config.pbuffer_width, config.pbuffer_height);
        return true;
    }

    bool EglEnvironment::create_context(const EglConfig &config) {
        int major_version = 3;
        int minor_version = config.require_es31 ? 1 : 0;

        EGLint context_attribs[] = {
                EGL_CONTEXT_CLIENT_VERSION, major_version,
                EGL_CONTEXT_MINOR_VERSION, minor_version,
                EGL_NONE
        };

        context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, context_attribs);
        if (context_ == EGL_NO_CONTEXT) {
            EGLint error = eglGetError();
            FUTON_LOGE("eglCreateContext (ES %d.%d) failed: 0x%x",
                       major_version, minor_version, error);

            if (config.require_es31 && error == EGL_BAD_MATCH) {
                FUTON_LOGW("Falling back to OpenGL ES 3.0");
                EGLint fallback_attribs[] = {
                        EGL_CONTEXT_CLIENT_VERSION, 3,
                        EGL_CONTEXT_MINOR_VERSION, 0,
                        EGL_NONE
                };
                context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, fallback_attribs);
                if (context_ == EGL_NO_CONTEXT) {
                    FUTON_LOGE("eglCreateContext (ES 3.0 fallback) failed: 0x%x", eglGetError());
                    return false;
                }
                FUTON_LOGW("Using OpenGL ES 3.0 (compute shaders may not be available)");
            } else {
                return false;
            }
        }

        FUTON_LOGD("EGL context created (OpenGL ES %d.%d requested)",
                   major_version, minor_version);
        return true;
    }

    bool EglEnvironment::make_current() {
        if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT) {
            FUTON_LOGE("make_current: EGL not initialized");
            return false;
        }

        if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
            FUTON_LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
            return false;
        }

        bound_thread_id_ = std::this_thread::get_id();
        return true;
    }

    void EglEnvironment::release_current() {
        if (display_ != EGL_NO_DISPLAY) {
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        bound_thread_id_ = std::thread::id{};
    }

    bool EglEnvironment::is_current() const {
        if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT) {
            return false;
        }
        return eglGetCurrentContext() == context_;
    }

    void EglEnvironment::get_version(int *major, int *minor) const {
        if (major) *major = egl_major_;
        if (minor) *minor = egl_minor_;
    }

    const char *EglEnvironment::get_extensions() const {
        return extensions_;
    }

    bool EglEnvironment::has_extension(const char *extension) const {
        if (!extensions_ || !extension) {
            return false;
        }

        const char *start = extensions_;
        const char *where;
        size_t ext_len = strlen(extension);

        while ((where = strstr(start, extension)) != nullptr) {
            const char *terminator = where + ext_len;
            if ((where == start || *(where - 1) == ' ') &&
                (*terminator == ' ' || *terminator == '\0')) {
                return true;
            }
            start = terminator;
        }

        return false;
    }

    const char *EglEnvironment::get_gl_vendor() const {
        if (!is_current()) {
            return nullptr;
        }
        return reinterpret_cast<const char *>(glGetString(GL_VENDOR));
    }

    const char *EglEnvironment::get_gl_renderer() const {
        if (!is_current()) {
            return nullptr;
        }
        return reinterpret_cast<const char *>(glGetString(GL_RENDERER));
    }

    const char *EglEnvironment::get_gl_version() const {
        if (!is_current()) {
            return nullptr;
        }
        return reinterpret_cast<const char *>(glGetString(GL_VERSION));
    }

} // namespace futon::vision
