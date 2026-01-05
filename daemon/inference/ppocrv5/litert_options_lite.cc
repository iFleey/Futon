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

#include "litert/cc/litert_options.h"

#include <optional>
#include <utility>

#include "litert/c/litert_common.h"
#include "litert/c/litert_options.h"
#include "litert/cc/litert_expected.h"
#include "litert/cc/litert_macros.h"
#include "litert/cc/options/litert_compiler_options.h"
#include "litert/cc/options/litert_cpu_options.h"
#include "litert/cc/options/litert_gpu_options.h"
#include "litert/cc/options/litert_runtime_options.h"

namespace litert {

    namespace {

        template<typename OptionType>
        Expected<OptionType &> EnsureOption(std::optional<OptionType> &slot) {
            if (!slot) {
                LITERT_ASSIGN_OR_RETURN(auto
                option, OptionType::Create());
                slot.emplace(std::move(option));
            }
            return slot.value();
        }

        template<typename OptionType>
        LiteRtStatus AppendAndReset(LiteRtOptions options,
                                    std::optional<OptionType> &slot) {
            if (!slot) {
                return kLiteRtStatusOk;
            }
            LiteRtOpaqueOptions opaque = slot->Release();
            slot.reset();
            return LiteRtAddOpaqueOptions(options, opaque);
        }

    }  // namespace

    Expected<GpuOptions &> Options::GetGpuOptions() {
        return EnsureOption(gpu_options_);
    }

    Expected<CpuOptions &> Options::GetCpuOptions() {
        return EnsureOption(cpu_options_);
    }

    Expected<qualcomm::QualcommOptions &> Options::GetQualcommOptions() {
        return Unexpected(kLiteRtStatusErrorUnsupported,
                          "Qualcomm options not supported in this build");
    }

    Expected<mediatek::MediatekOptions &> Options::GetMediatekOptions() {
        return Unexpected(kLiteRtStatusErrorUnsupported,
                          "MediaTek options not supported in this build");
    }

    Expected<google_tensor::GoogleTensorOptions &>
    Options::GetGoogleTensorOptions() {
        return Unexpected(kLiteRtStatusErrorUnsupported,
                          "Google Tensor options not supported in this build");
    }

    Expected<intel_openvino::IntelOpenVinoOptions &>
    Options::GetIntelOpenVinoOptions() {
        return Unexpected(kLiteRtStatusErrorUnsupported,
                          "Intel OpenVINO options not supported in this build");
    }

    Expected<RuntimeOptions &> Options::GetRuntimeOptions() {
        return EnsureOption(runtime_options_);
    }

    Expected<CompilerOptions &> Options::GetCompilerOptions() {
        return EnsureOption(compiler_options_);
    }

    Expected<void> Options::Build() {
        LITERT_RETURN_IF_ERROR(AppendAndReset(Get(), gpu_options_));
        LITERT_RETURN_IF_ERROR(AppendAndReset(Get(), cpu_options_));
        LITERT_RETURN_IF_ERROR(AppendAndReset(Get(), runtime_options_));
        LITERT_RETURN_IF_ERROR(AppendAndReset(Get(), compiler_options_));
        return {};
    }

    Expected<void> Options::SetExternalWeightScopedFile(
            ScopedFile &scoped_file, ScopedWeightSectionMap sections) {
        return Unexpected(kLiteRtStatusErrorUnsupported,
                          "External weight loader not supported in this build");
    }

}  // namespace litert
