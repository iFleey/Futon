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

#include "text_injector.h"
#include "ime_controller.h"
#include "shell_executor.h"

#include <unistd.h>
#include <dirent.h>
#include <cstring>
#include <cstdlib>
#include <vector>
#include <algorithm>

using namespace futon::core;

namespace futon::input {

    TextInjector::TextInjector() = default;

    TextInjector::~TextInjector() {
        shutdown();
    }

    Result<void> TextInjector::initialize() {
        if (initialized_) return Result<void>::ok();

        if (getuid() != 0 && geteuid() != 0) {
            return Result<void>::err(FutonError::PermissionDenied, "Root access required");
        }

        if (!ShellExecutor::instance().is_running()) {
            ShellExecutor::instance().start();
        }

        ime_controller_ = std::make_unique<ImeController>();
        ime_controller_->initialize();

        initialized_ = true;
        return Result<void>::ok();
    }

    void TextInjector::shutdown() {
        if (!initialized_) return;
        ime_controller_.reset();
        // ShellExecutor managed by main()
        initialized_ = false;
    }

    bool TextInjector::is_available() const {
        return initialized_ && ime_controller_ != nullptr;
    }

    pid_t TextInjector::get_foreground_pid() {
        DIR *proc_dir = opendir("/proc");
        if (!proc_dir) return -1;

        struct Candidate {
            pid_t pid;
            unsigned long cputime;
            char cmdline[256];
        };

        std::vector<Candidate> candidates;
        struct dirent *entry;

        while ((entry = readdir(proc_dir)) != nullptr) {
            if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;

            pid_t pid = atoi(entry->d_name);
            if (pid <= 0) continue;

            char path[64];
            snprintf(path, sizeof(path), "/proc/%d/oom_score_adj", pid);

            FILE *f = fopen(path, "r");
            if (!f) continue;

            int oom_adj = 999;
            fscanf(f, "%d", &oom_adj);
            fclose(f);

            if (oom_adj != 0) continue;

            snprintf(path, sizeof(path), "/proc/%d/status", pid);
            f = fopen(path, "r");
            if (!f) continue;

            int uid = -1;
            char line[256];
            while (fgets(line, sizeof(line), f)) {
                if (strncmp(line, "Uid:", 4) == 0) {
                    sscanf(line + 4, "%d", &uid);
                    break;
                }
            }
            fclose(f);

            if (uid < 10000) continue;

            snprintf(path, sizeof(path), "/proc/%d/stat", pid);
            f = fopen(path, "r");
            if (!f) continue;

            unsigned long utime = 0, stime = 0;
            char stat_line[512];
            if (fgets(stat_line, sizeof(stat_line), f)) {
                char *comm_end = strrchr(stat_line, ')');
                if (comm_end) {
                    int dummy;
                    char state;
                    sscanf(comm_end + 2, "%c %d %d %d %d %d %d %d %d %d %d %lu %lu",
                           &state, &dummy, &dummy, &dummy, &dummy, &dummy, &dummy,
                           &dummy, &dummy, &dummy, &dummy, &utime, &stime);
                }
            }
            fclose(f);

            snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
            f = fopen(path, "r");
            char cmdline[256] = {0};
            if (f) {
                fread(cmdline, 1, sizeof(cmdline) - 1, f);
                fclose(f);
            }

            if (cmdline[0] == '\0') continue;
            if (strncmp(cmdline, "com.android.", 12) == 0) continue;
            if (strncmp(cmdline, "android.", 8) == 0) continue;

            Candidate c;
            c.pid = pid;
            c.cputime = utime + stime;
            strncpy(c.cmdline, cmdline, sizeof(c.cmdline) - 1);
            candidates.push_back(c);
        }
        closedir(proc_dir);

        if (candidates.empty()) return -1;

        std::sort(candidates.begin(), candidates.end(),
                  [](const Candidate &a, const Candidate &b) { return a.cputime > b.cputime; });

        return candidates[0].pid;
    }

    Result<void> TextInjector::inject_text(const std::string &text, int timeout_ms) {
        if (!initialized_) {
            return Result<void>::err(FutonError::NotInitialized);
        }
        if (text.empty()) return Result<void>::ok();

        if (!ime_controller_) {
            return Result<void>::err(FutonError::NotInitialized, "ImeController is null");
        }

        return ime_controller_->inject_text(text, timeout_ms);
    }

} // namespace futon::input
