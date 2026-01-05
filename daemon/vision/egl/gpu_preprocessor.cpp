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

#include "vision/egl/gpu_preprocessor.h"
#include "vision/egl/egl_environment.h"
#include "core/error.h"

#include <GLES2/gl2ext.h>
#include <GLES3/gl3ext.h>
#include <android/hardware_buffer.h>
#include <chrono>
#include <cstring>

// EGL Android extensions
extern "C" {
typedef void *EGLClientBuffer;
EGLClientBuffer eglGetNativeClientBufferANDROID(const struct AHardwareBuffer *buffer);
}

// GL OES extensions
typedef void (*PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)(GLenum
target,
void *image
);
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES_ptr = nullptr;

static void glEGLImageTargetTexture2DOES(GLenum
target,
void *image
) {
if (!glEGLImageTargetTexture2DOES_ptr) {
glEGLImageTargetTexture2DOES_ptr = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)
        eglGetProcAddress("glEGLImageTargetTexture2DOES");
}
if (glEGLImageTargetTexture2DOES_ptr) {
glEGLImageTargetTexture2DOES_ptr(target, image
);
}
}

using namespace futon::core;

namespace futon::vision {

// Compute shader for RGBA -> RGB conversion with optional resize (regular sampler2D)
    static const char *kComputeShaderSource = R"(#version 310 es
precision highp float;
precision highp image2D;

layout(local_size_x = 16, local_size_y = 16) in;

layout(binding = 0) uniform highp sampler2D u_input;
layout(binding = 0, rgba8) writeonly uniform highp image2D u_output;

uniform ivec2 u_input_size;
uniform ivec2 u_output_size;
uniform int u_resize_factor;

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);

    if (pos.x >= u_output_size.x || pos.y >= u_output_size.y) {
        return;
    }

    // Calculate input coordinates with resize factor
    vec2 input_coord;
    if (u_resize_factor > 1) {
        // Bilinear sampling for resize
        input_coord = (vec2(pos) + 0.5) * float(u_resize_factor) / vec2(u_input_size);
    } else {
        input_coord = (vec2(pos) + 0.5) / vec2(u_input_size);
    }

    // Sample input (RGBA)
    vec4 rgba = texture(u_input, input_coord);

    // Output RGB (store in RGBA with A=1.0 for compatibility)
    // Note: Actual RGB_888 output would require different storage format
    imageStore(u_output, pos, vec4(rgba.rgb, 1.0));
}
)";

// Compute shader for external texture input (GL_TEXTURE_EXTERNAL_OES from GLConsumer)
    static const char *kComputeShaderExternalSource = R"(#version 310 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp image2D;

layout(local_size_x = 16, local_size_y = 16) in;

// External texture input from GLConsumer
uniform samplerExternalOES u_input_external;
layout(binding = 0, rgba8) writeonly uniform highp image2D u_output;

uniform vec2 u_input_size;
uniform vec2 u_output_size;
uniform mat4 u_transform_matrix;

void main() {
    ivec2 outCoord = ivec2(gl_GlobalInvocationID.xy);

    // Bounds check
    if (outCoord.x >= int(u_output_size.x) || outCoord.y >= int(u_output_size.y)) {
        return;
    }

    // Calculate normalized UV coordinates [0, 1]
    vec2 uv = (vec2(outCoord) + 0.5) / u_output_size;

    // Apply transform matrix from GLConsumer for proper texture orientation
    // The transform matrix handles buffer rotation, flipping, and cropping
    vec4 transformed_uv = u_transform_matrix * vec4(uv, 0.0, 1.0);

    // Sample external texture (RGBA from SurfaceFlinger)
    vec4 color = texture(u_input_external, transformed_uv.xy);

    // Output RGB with alpha = 1.0 (RGBA -> RGB conversion)
    imageStore(u_output, outCoord, vec4(color.rgb, 1.0));
}
)";

// Compute shader for ROI crop with letterbox padding (for OCR)
// Crops a region from external texture and resizes to fixed output with letterbox
    static const char *kComputeShaderRoiSource = R"(#version 310 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp image2D;

layout(local_size_x = 16, local_size_y = 16) in;

uniform samplerExternalOES u_input_external;
layout(binding = 0, rgba8) writeonly uniform highp image2D u_output;

uniform vec2 u_input_size;
uniform vec2 u_output_size;
uniform vec4 u_roi;  // (x, y, w, h) in normalized coordinates [0, 1]
uniform mat4 u_transform_matrix;

void main() {
    ivec2 outCoord = ivec2(gl_GlobalInvocationID.xy);

    if (outCoord.x >= int(u_output_size.x) || outCoord.y >= int(u_output_size.y)) {
        return;
    }

    // Calculate aspect ratios
    float roi_aspect = u_roi.z / u_roi.w;  // ROI width / height
    float out_aspect = u_output_size.x / u_output_size.y;  // Output width / height

    // Calculate letterbox parameters
    vec2 scale;
    vec2 offset;

    if (roi_aspect > out_aspect) {
        // ROI is wider than output: fit width, pad top/bottom
        scale.x = 1.0;
        scale.y = out_aspect / roi_aspect;
        offset.x = 0.0;
        offset.y = (1.0 - scale.y) * 0.5;
    } else {
        // ROI is taller than output: fit height, pad left/right
        scale.x = roi_aspect / out_aspect;
        scale.y = 1.0;
        offset.x = (1.0 - scale.x) * 0.5;
        offset.y = 0.0;
    }

    // Calculate normalized output coordinate [0, 1]
    vec2 out_uv = (vec2(outCoord) + 0.5) / u_output_size;

    // Check if we're in the letterbox padding area
    vec2 content_uv = (out_uv - offset) / scale;

    if (content_uv.x < 0.0 || content_uv.x > 1.0 ||
        content_uv.y < 0.0 || content_uv.y > 1.0) {
        // Padding area: output black (or gray for better visibility)
        imageStore(u_output, outCoord, vec4(0.5, 0.5, 0.5, 1.0));
        return;
    }

    // Map content UV to ROI coordinates in input texture
    vec2 roi_uv = u_roi.xy + content_uv * u_roi.zw;

    // Apply transform matrix from GLConsumer
    vec4 transformed_uv = u_transform_matrix * vec4(roi_uv, 0.0, 1.0);

    // Sample and output
    vec4 color = texture(u_input_external, transformed_uv.xy);
    imageStore(u_output, outCoord, vec4(color.rgb, 1.0));
}
)";

    GpuPreprocessor::GpuPreprocessor() = default;

    GpuPreprocessor::~GpuPreprocessor() {
        shutdown();
    }

    bool GpuPreprocessor::initialize() {
        if (initialized_) {
            FUTON_LOGW("GpuPreprocessor: already initialized");
            return true;
        }

        bound_thread_id_ = std::this_thread::get_id();

        // Create internal EGL environment
        if (!init_internal_egl()) {
            FUTON_LOGE("GpuPreprocessor: failed to create EGL environment");
            return false;
        }

        if (!load_egl_extensions()) {
            FUTON_LOGE("GpuPreprocessor: failed to load EGL extensions");
            shutdown();
            return false;
        }

        if (!create_compute_shader()) {
            FUTON_LOGE("GpuPreprocessor: failed to create compute shader");
            shutdown();
            return false;
        }

        // Create external texture shader for GLConsumer input
        if (!create_external_compute_shader()) {
            FUTON_LOGW(
                    "GpuPreprocessor: external texture shader not available (GL_OES_EGL_image_external_essl3 may not be supported)");
            // Continue without external shader - regular processing still works
        }

        // Create ROI shader for OCR preprocessing
        if (!create_roi_compute_shader()) {
            FUTON_LOGW("GpuPreprocessor: ROI shader not available");
        }

        initialized_ = true;
        FUTON_LOGI("GpuPreprocessor: initialized successfully");
        return true;
    }

    bool GpuPreprocessor::initialize(std::shared_ptr<EglEnvironment> egl_env) {
        if (initialized_) {
            FUTON_LOGW("GpuPreprocessor: already initialized");
            return true;
        }

        if (!egl_env || !egl_env->is_initialized()) {
            FUTON_LOGE("GpuPreprocessor: invalid EGL environment");
            return false;
        }

        bound_thread_id_ = std::this_thread::get_id();
        egl_env_ = egl_env;
        owns_egl_env_ = false;

        // Ensure context is current
        if (!egl_env_->is_current()) {
            if (!egl_env_->make_current()) {
                FUTON_LOGE("GpuPreprocessor: failed to make EGL context current");
                return false;
            }
        }

        if (!load_egl_extensions()) {
            FUTON_LOGE("GpuPreprocessor: failed to load EGL extensions");
            shutdown();
            return false;
        }

        if (!create_compute_shader()) {
            FUTON_LOGE("GpuPreprocessor: failed to create compute shader");
            shutdown();
            return false;
        }

        // Create external texture shader for GLConsumer input
        if (!create_external_compute_shader()) {
            FUTON_LOGW("GpuPreprocessor: external texture shader not available");
        }

        // Create ROI shader for OCR preprocessing
        if (!create_roi_compute_shader()) {
            FUTON_LOGW("GpuPreprocessor: ROI shader not available");
        }

        initialized_ = true;
        FUTON_LOGI("GpuPreprocessor: initialized with external EGL environment");
        return true;
    }

    bool GpuPreprocessor::init_internal_egl() {
        egl_env_ = std::make_shared<EglEnvironment>();
        owns_egl_env_ = true;

        EglConfig config;
        config.require_es31 = true;  // Required for compute shaders

        if (!egl_env_->initialize(config)) {
            FUTON_LOGE("GpuPreprocessor: EglEnvironment initialization failed");
            egl_env_.reset();
            return false;
        }

        return true;
    }

    void GpuPreprocessor::shutdown() {
        if (!initialized_) {
            return;
        }

        // Make context current for cleanup
        if (egl_env_ && egl_env_->is_initialized()) {
            egl_env_->make_current();
        }

        // Delete OpenGL resources
        if (compute_program_ != 0) {
            glDeleteProgram(compute_program_);
            compute_program_ = 0;
        }
        if (compute_program_external_ != 0) {
            glDeleteProgram(compute_program_external_);
            compute_program_external_ = 0;
        }
        if (compute_program_roi_ != 0) {
            glDeleteProgram(compute_program_roi_);
            compute_program_roi_ = 0;
        }
        if (input_texture_ != 0) {
            glDeleteTextures(1, &input_texture_);
            input_texture_ = 0;
        }
        if (output_texture_ != 0) {
            glDeleteTextures(1, &output_texture_);
            output_texture_ = 0;
        }

        // Release EGL environment if we own it
        if (owns_egl_env_ && egl_env_) {
            egl_env_->shutdown();
        }
        egl_env_.reset();
        owns_egl_env_ = false;

        initialized_ = false;
        FUTON_LOGD("GpuPreprocessor: shutdown complete");
    }

    bool GpuPreprocessor::load_egl_extensions() {
        if (!egl_env_) {
            FUTON_LOGE("load_egl_extensions: no EGL environment");
            return false;
        }

        EGLDisplay display = egl_env_->get_display();

        // Load EGL_KHR_image_base
        eglCreateImageKHR_ = (PFNEGLCREATEIMAGEKHRPROC) eglGetProcAddress("eglCreateImageKHR");
        eglDestroyImageKHR_ = (PFNEGLDESTROYIMAGEKHRPROC) eglGetProcAddress("eglDestroyImageKHR");

        // Load EGL_KHR_fence_sync
        eglCreateSyncKHR_ = (PFNEGLCREATESYNCKHRPROC) eglGetProcAddress("eglCreateSyncKHR");
        eglDestroySyncKHR_ = (PFNEGLDESTROYSYNCKHRPROC) eglGetProcAddress("eglDestroySyncKHR");
        eglClientWaitSyncKHR_ = (PFNEGLCLIENTWAITSYNCKHRPROC) eglGetProcAddress(
                "eglClientWaitSyncKHR");

        // Load EGL_ANDROID_native_fence_sync
        eglDupNativeFenceFDANDROID_ = (PFNEGLDUPNATIVEFENCEFDANDROIDPROC)
                eglGetProcAddress("eglDupNativeFenceFDANDROID");

        if (!eglCreateImageKHR_ || !eglDestroyImageKHR_) {
            FUTON_LOGW("EGL_KHR_image_base not available");
        }
        if (!eglCreateSyncKHR_ || !eglDestroySyncKHR_ || !eglClientWaitSyncKHR_) {
            FUTON_LOGW("EGL_KHR_fence_sync not available");
        }
        if (!eglDupNativeFenceFDANDROID_) {
            FUTON_LOGW("EGL_ANDROID_native_fence_sync not available");
        }

        return true;
    }

    bool GpuPreprocessor::create_compute_shader() {
        GLuint shader = compile_shader(GL_COMPUTE_SHADER, kComputeShaderSource);
        if (shader == 0) {
            return false;
        }

        compute_program_ = link_program(shader);
        glDeleteShader(shader);

        if (compute_program_ == 0) {
            return false;
        }

        // Get uniform locations
        u_input_size_ = glGetUniformLocation(compute_program_, "u_input_size");
        u_output_size_ = glGetUniformLocation(compute_program_, "u_output_size");
        u_resize_factor_ = glGetUniformLocation(compute_program_, "u_resize_factor");

        // Create textures
        glGenTextures(1, &input_texture_);
        glGenTextures(1, &output_texture_);

        FUTON_LOGD("Compute shader created successfully");
        return true;
    }

    bool GpuPreprocessor::create_external_compute_shader() {
        GLuint shader = compile_shader(GL_COMPUTE_SHADER, kComputeShaderExternalSource);
        if (shader == 0) {
            FUTON_LOGW(
                    "External compute shader compilation failed - GL_OES_EGL_image_external_essl3 may not be supported");
            return false;
        }

        compute_program_external_ = link_program(shader);
        glDeleteShader(shader);

        if (compute_program_external_ == 0) {
            FUTON_LOGW("External compute shader linking failed");
            return false;
        }

        // Get uniform locations for external shader
        u_ext_input_size_ = glGetUniformLocation(compute_program_external_, "u_input_size");
        u_ext_output_size_ = glGetUniformLocation(compute_program_external_, "u_output_size");
        u_ext_transform_matrix_ = glGetUniformLocation(compute_program_external_,
                                                       "u_transform_matrix");

        FUTON_LOGD("External compute shader created successfully");
        return true;
    }

    bool GpuPreprocessor::create_roi_compute_shader() {
        GLuint shader = compile_shader(GL_COMPUTE_SHADER, kComputeShaderRoiSource);
        if (shader == 0) {
            FUTON_LOGW("ROI compute shader compilation failed");
            return false;
        }

        compute_program_roi_ = link_program(shader);
        glDeleteShader(shader);

        if (compute_program_roi_ == 0) {
            FUTON_LOGW("ROI compute shader linking failed");
            return false;
        }

        // Get uniform locations
        u_roi_input_size_ = glGetUniformLocation(compute_program_roi_, "u_input_size");
        u_roi_output_size_ = glGetUniformLocation(compute_program_roi_, "u_output_size");
        u_roi_rect_ = glGetUniformLocation(compute_program_roi_, "u_roi");
        u_roi_transform_matrix_ = glGetUniformLocation(compute_program_roi_, "u_transform_matrix");

        FUTON_LOGD("ROI compute shader created successfully");
        return true;
    }

    GLuint GpuPreprocessor::compile_shader(GLenum
    type,
    const char *source
    ) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) {
    FUTON_LOGE("glCreateShader failed");
    return 0;
}

glShaderSource(shader,
1, &source, nullptr);
glCompileShader(shader);

GLint compiled;
glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled
);
if (!compiled) {
GLint info_len = 0;
glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &info_len
);
if (info_len > 0) {
char *info_log = new char[info_len];
glGetShaderInfoLog(shader, info_len,
nullptr, info_log);
FUTON_LOGE("Shader compile error: %s", info_log);
delete[]
info_log;
}
glDeleteShader(shader);
return 0;
}

return
shader;
}

GLuint GpuPreprocessor::link_program(GLuint
compute_shader) {
GLuint program = glCreateProgram();
if (program == 0) {
FUTON_LOGE("glCreateProgram failed");
return 0;
}

glAttachShader(program, compute_shader
);
glLinkProgram(program);

GLint linked;
glGetProgramiv(program, GL_LINK_STATUS, &linked
);
if (!linked) {
GLint info_len = 0;
glGetProgramiv(program, GL_INFO_LOG_LENGTH, &info_len
);
if (info_len > 0) {
char *info_log = new char[info_len];
glGetProgramInfoLog(program, info_len,
nullptr, info_log);
FUTON_LOGE("Program link error: %s", info_log);
delete[]
info_log;
}
glDeleteProgram(program);
return 0;
}

return
program;
}

bool GpuPreprocessor::validate_thread() {
    // Relaxed check: Logic flow in VisionPipeline ensures serialization via mutex
    // and context binding. If the EGL context is current on this thread, it is valid.
    if (egl_env_ && egl_env_->is_current()) {
        return true;
    }

    if (std::this_thread::get_id() != bound_thread_id_) {
        FUTON_LOGE("GpuPreprocessor: called from wrong thread");
        return false;
    }
    return true;
}

bool GpuPreprocessor::make_current() {
    if (!egl_env_) {
        return false;
    }

    if (!egl_env_->make_current()) {
        FUTON_LOGE("make_current: eglMakeCurrent failed");
        return false;
    }

    bound_thread_id_ = std::this_thread::get_id();
    return true;
}

void GpuPreprocessor::release_current() {
    if (egl_env_) {
        egl_env_->release_current();
    }
}

void GpuPreprocessor::get_output_dimensions(uint32_t input_width, uint32_t input_height,
                                            ResizeMode resize,
                                            uint32_t *out_width, uint32_t *out_height) {
    int factor = 1;
    switch (resize) {
        case ResizeMode::Half:
            factor = 2;
            break;
        case ResizeMode::Quarter:
            factor = 4;
            break;
        default:
            factor = 1;
            break;
    }

    if (out_width) *out_width = input_width / factor;
    if (out_height) *out_height = input_height / factor;
}

bool GpuPreprocessor::allocate_output_buffer(uint32_t input_width, uint32_t input_height,
                                             ResizeMode resize, HardwareBufferWrapper *out_buffer) {
    if (!out_buffer) {
        return false;
    }

    uint32_t out_width, out_height;
    get_output_dimensions(input_width, input_height, resize, &out_width, &out_height);

    // Allocate RGBA buffer (RGB_888 not directly supported by AHardwareBuffer)
    uint64_t usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                     AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                     AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;

    return out_buffer->allocate(out_width, out_height,
                                AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, usage);
}

Result <PreprocessResult> GpuPreprocessor::process(AHardwareBuffer *input,
                                                   AHardwareBuffer *output,
                                                   ResizeMode resize) {
    if (!initialized_) {
        return Result<PreprocessResult>::error(FutonError::NotInitialized);
    }

    if (!validate_thread()) {
        return Result<PreprocessResult>::error(FutonError::InvalidArgument);
    }

    if (!input || !output) {
        FUTON_LOGE("process: null buffer");
        return Result<PreprocessResult>::error(FutonError::InvalidArgument);
    }

    auto start_time = std::chrono::high_resolution_clock::now();

    // Get buffer descriptions
    AHardwareBuffer_Desc input_desc, output_desc;
    AHardwareBuffer_describe(input, &input_desc);
    AHardwareBuffer_describe(output, &output_desc);

    // Bind input buffer as texture
    if (!bind_input_buffer(input, input_desc.width, input_desc.height)) {
        return Result<PreprocessResult>::error(FutonError::InternalError);
    }

    // Bind output buffer as image
    if (!bind_output_buffer(output, output_desc.width, output_desc.height)) {
        return Result<PreprocessResult>::error(FutonError::InternalError);
    }

    // Set uniforms
    glUseProgram(compute_program_);
    glUniform2i(u_input_size_, input_desc.width, input_desc.height);
    glUniform2i(u_output_size_, output_desc.width, output_desc.height);

    int resize_factor = 1;
    switch (resize) {
        case ResizeMode::Half:
            resize_factor = 2;
            break;
        case ResizeMode::Quarter:
            resize_factor = 4;
            break;
        default:
            resize_factor = 1;
            break;
    }
    glUniform1i(u_resize_factor_, resize_factor);

    // Dispatch compute shader
    GLuint groups_x = (output_desc.width + 15) / 16;
    GLuint groups_y = (output_desc.height + 15) / 16;
    glDispatchCompute(groups_x, groups_y, 1);

    // Memory barrier for image writes
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

    // Create fence for downstream sync
    int fence_fd = create_fence();

    auto end_time = std::chrono::high_resolution_clock::now();
    float process_time_ms = std::chrono::duration<float, std::milli>(end_time - start_time).count();

    PreprocessResult result;
    result.output_buffer = output;
    result.fence_fd = fence_fd;
    result.width = output_desc.width;
    result.height = output_desc.height;
    result.process_time_ms = process_time_ms;

    return Result<PreprocessResult>::ok(result);
}

bool GpuPreprocessor::bind_input_buffer(AHardwareBuffer *buffer, uint32_t width, uint32_t height) {
    if (!eglCreateImageKHR_ || !egl_env_) {
        FUTON_LOGE("bind_input_buffer: EGL_KHR_image not available");
        return false;
    }

    EGLDisplay display = egl_env_->get_display();

    // Create EGLImage from AHardwareBuffer
    EGLClientBuffer client_buffer = eglGetNativeClientBufferANDROID(buffer);
    if (!client_buffer) {
        FUTON_LOGE("eglGetNativeClientBufferANDROID failed");
        return false;
    }

    EGLint attribs[] = {
            EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
            EGL_NONE
    };

    EGLImageKHR image = eglCreateImageKHR_(display, EGL_NO_CONTEXT,
                                           EGL_NATIVE_BUFFER_ANDROID,
                                           client_buffer, attribs);
    if (image == EGL_NO_IMAGE_KHR) {
        FUTON_LOGE("eglCreateImageKHR failed: 0x%x", eglGetError());
        return false;
    }

    // Bind to texture
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, input_texture_);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // Destroy EGLImage (texture keeps reference)
    eglDestroyImageKHR_(display, image);

    return true;
}

bool GpuPreprocessor::bind_output_buffer(AHardwareBuffer *buffer, uint32_t width, uint32_t height) {
    if (!eglCreateImageKHR_ || !egl_env_) {
        FUTON_LOGE("bind_output_buffer: EGL_KHR_image not available");
        return false;
    }

    EGLDisplay display = egl_env_->get_display();

    EGLClientBuffer client_buffer = eglGetNativeClientBufferANDROID(buffer);
    if (!client_buffer) {
        FUTON_LOGE("eglGetNativeClientBufferANDROID failed");
        return false;
    }

    EGLint attribs[] = {
            EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
            EGL_NONE
    };

    EGLImageKHR image = eglCreateImageKHR_(display, EGL_NO_CONTEXT,
                                           EGL_NATIVE_BUFFER_ANDROID,
                                           client_buffer, attribs);
    if (image == EGL_NO_IMAGE_KHR) {
        FUTON_LOGE("eglCreateImageKHR failed: 0x%x", eglGetError());
        return false;
    }

    // Bind to texture for image store
    glBindTexture(GL_TEXTURE_2D, output_texture_);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image);
    glBindImageTexture(0, output_texture_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);

    eglDestroyImageKHR_(display, image);

    return true;
}

bool GpuPreprocessor::bind_external_input_texture(GLuint
external_texture_id) {
glActiveTexture(GL_TEXTURE0);
glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_texture_id);

// Set texture parameters for external texture
glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

GLenum error = glGetError();
if (error != GL_NO_ERROR) {
FUTON_LOGE("bind_external_input_texture: GL error 0x%x", error);
return false;
}

return true;
}

Result <PreprocessResult> GpuPreprocessor::process_external_texture(
        GLuint
external_texture_id,
uint32_t input_width,
        uint32_t
input_height,
const float *transform_matrix,
        AHardwareBuffer
* output,
ResizeMode resize
) {

if (!initialized_) {
return
Result<PreprocessResult>::error(FutonError::NotInitialized);
}

if (!

validate_thread()

) {
return
Result<PreprocessResult>::error(FutonError::InvalidArgument);
}

if (compute_program_external_ == 0) {
FUTON_LOGE("process_external_texture: external shader not available");
return
Result<PreprocessResult>::error(FutonError::NotInitialized);
}

if (external_texture_id == 0 || !output) {
FUTON_LOGE("process_external_texture: invalid parameters");
return
Result<PreprocessResult>::error(FutonError::InvalidArgument);
}

auto start_time = std::chrono::high_resolution_clock::now();

// Get output buffer description
AHardwareBuffer_Desc output_desc;
AHardwareBuffer_describe(output, &output_desc
);

if (!
bind_external_input_texture(external_texture_id)
) {
return
Result<PreprocessResult>::error(FutonError::InternalError);
}

if (!
bind_output_buffer(output, output_desc
.width, output_desc.height)) {
return
Result<PreprocessResult>::error(FutonError::InternalError);
}

// Use external texture shader
glUseProgram(compute_program_external_);

// Set uniforms
glUniform2f(u_ext_input_size_,
static_cast
<float>(input_width),
static_cast
<float>(input_height)
);
glUniform2f(u_ext_output_size_,
static_cast
<float>(output_desc
.width), static_cast
<float>(output_desc
.height));

// Set transform matrix (identity if not provided)
if (transform_matrix) {
glUniformMatrix4fv(u_ext_transform_matrix_,
1, GL_FALSE, transform_matrix);
} else {
// Identity matrix
static const float identity[16] = {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
};
glUniformMatrix4fv(u_ext_transform_matrix_,
1, GL_FALSE, identity);
}

// Bind external texture to sampler
GLint sampler_loc = glGetUniformLocation(compute_program_external_, "u_input_external");
glUniform1i(sampler_loc,
0);

// Dispatch compute shader
GLuint groups_x = (output_desc.width + 15) / 16;
GLuint groups_y = (output_desc.height + 15) / 16;
glDispatchCompute(groups_x, groups_y,
1);

glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

int fence_fd = create_fence();

auto end_time = std::chrono::high_resolution_clock::now();
float process_time_ms = std::chrono::duration<float, std::milli>(end_time - start_time).count();

PreprocessResult result;
result.
output_buffer = output;
result.
fence_fd = fence_fd;
result.
width = output_desc.width;
result.
height = output_desc.height;
result.
process_time_ms = process_time_ms;

FUTON_LOGD("process_external_texture: %ux%u -> %ux%u in %.2fms, fence_fd=%d",
input_width, input_height, output_desc.width, output_desc.height,
process_time_ms, fence_fd);

return
Result<PreprocessResult>::ok(result);
}

int GpuPreprocessor::create_fence() {
    if (!eglCreateSyncKHR_ || !eglDupNativeFenceFDANDROID_ || !egl_env_) {
        // Fallback: flush and return -1
        glFlush();
        return -1;
    }

    EGLDisplay display = egl_env_->get_display();

    // Create EGL sync object
    EGLSyncKHR sync = eglCreateSyncKHR_(display, EGL_SYNC_NATIVE_FENCE_ANDROID, nullptr);
    if (sync == EGL_NO_SYNC_KHR) {
        FUTON_LOGW("eglCreateSyncKHR failed, using glFlush");
        glFlush();
        return -1;
    }

    // Flush to ensure sync is inserted
    glFlush();

    // Get native fence fd
    int fence_fd = eglDupNativeFenceFDANDROID_(display, sync);
    eglDestroySyncKHR_(display, sync);

    return fence_fd;
}

Result <PreprocessResult> GpuPreprocessor::process_roi(
        GLuint
external_texture_id,
uint32_t input_width,
        uint32_t
input_height,
const float *transform_matrix,
float roi_x,
float roi_y,
float roi_w,
float roi_h,
        AHardwareBuffer
* output) {

if (!initialized_) {
return
Result<PreprocessResult>::error(FutonError::NotInitialized);
}

if (!

validate_thread()

) {
return
Result<PreprocessResult>::error(FutonError::InvalidArgument);
}

if (compute_program_roi_ == 0) {
FUTON_LOGE("process_roi: ROI shader not available");
return
Result<PreprocessResult>::error(FutonError::NotInitialized);
}

if (external_texture_id == 0 || !output) {
FUTON_LOGE("process_roi: invalid parameters");
return
Result<PreprocessResult>::error(FutonError::InvalidArgument);
}

// Validate ROI bounds
if (roi_x<0.0f || roi_y < 0.0f || roi_w <= 0.0f || roi_h <= 0.0f ||
          roi_x + roi_w> 1.0f || roi_y + roi_h > 1.0f) {
FUTON_LOGE("process_roi: invalid ROI bounds (%.3f, %.3f, %.3f, %.3f)",
roi_x, roi_y, roi_w, roi_h);
return
Result<PreprocessResult>::error(FutonError::InvalidArgument);
}

auto start_time = std::chrono::high_resolution_clock::now();

// Get output buffer description
AHardwareBuffer_Desc output_desc;
AHardwareBuffer_describe(output, &output_desc
);

if (!
bind_external_input_texture(external_texture_id)
) {
return
Result<PreprocessResult>::error(FutonError::InternalError);
}

if (!
bind_output_buffer(output, output_desc
.width, output_desc.height)) {
return
Result<PreprocessResult>::error(FutonError::InternalError);
}

// Use ROI shader
glUseProgram(compute_program_roi_);

// Set uniforms
glUniform2f(u_roi_input_size_,
static_cast
<float>(input_width),
static_cast
<float>(input_height)
);
glUniform2f(u_roi_output_size_,
static_cast
<float>(output_desc
.width),
static_cast
<float>(output_desc
.height));
glUniform4f(u_roi_rect_, roi_x, roi_y, roi_w, roi_h
);

// Set transform matrix
if (transform_matrix) {
glUniformMatrix4fv(u_roi_transform_matrix_,
1, GL_FALSE, transform_matrix);
} else {
static const float identity[16] = {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
};
glUniformMatrix4fv(u_roi_transform_matrix_,
1, GL_FALSE, identity);
}

// Bind external texture to sampler
GLint sampler_loc = glGetUniformLocation(compute_program_roi_, "u_input_external");
glUniform1i(sampler_loc,
0);

// Dispatch compute shader
GLuint groups_x = (output_desc.width + 15) / 16;
GLuint groups_y = (output_desc.height + 15) / 16;
glDispatchCompute(groups_x, groups_y,
1);

glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

int fence_fd = create_fence();

auto end_time = std::chrono::high_resolution_clock::now();
float process_time_ms = std::chrono::duration<float, std::milli>(end_time - start_time).count();

PreprocessResult result;
result.
output_buffer = output;
result.
fence_fd = fence_fd;
result.
width = output_desc.width;
result.
height = output_desc.height;
result.
process_time_ms = process_time_ms;

FUTON_LOGD("process_roi: ROI(%.2f,%.2f,%.2f,%.2f) -> %ux%u in %.2fms",
roi_x, roi_y, roi_w, roi_h,
output_desc.width, output_desc.height, process_time_ms);

return
Result<PreprocessResult>::ok(result);
}

bool GpuPreprocessor::allocate_ocr_buffer(uint32_t target_width, uint32_t target_height,
                                          HardwareBufferWrapper *out_buffer) {
    if (!out_buffer) {
        return false;
    }

    // Allocate RGBA buffer for OCR input
    uint64_t usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                     AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                     AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;

    return out_buffer->allocate(target_width, target_height,
                                AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, usage);
}

} // namespace futon::vision
