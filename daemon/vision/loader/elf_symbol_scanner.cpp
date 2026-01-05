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

#include "vision/loader/elf_symbol_scanner.h"
#include "core/error.h"

#include <elf.h>
#include <dlfcn.h>
#include <cxxabi.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <cstring>
#include <fstream>
#include <sstream>
#include <algorithm>

using namespace futon::core;

namespace futon::vision {

// Use 64-bit ELF structures for ARM64
    using ElfW_Ehdr = Elf64_Ehdr;
    using ElfW_Shdr = Elf64_Shdr;
    using ElfW_Sym = Elf64_Sym;
    using ElfW_Phdr = Elf64_Phdr;
    using ElfW_Dyn = Elf64_Dyn;

    ElfSymbolScanner::ElfSymbolScanner() = default;

    ElfSymbolScanner::~ElfSymbolScanner() = default;

    std::string ElfSymbolScanner::demangle(const std::string &mangled) {
        if (mangled.empty()) {
            return mangled;
        }

        int status = 0;
        char *demangled = abi::__cxa_demangle(mangled.c_str(), nullptr, nullptr, &status);

        if (status == 0 && demangled) {
            std::string result(demangled);
            free(demangled);
            return result;
        }

        return mangled;
    }

    int ElfSymbolScanner::analyze_param_count(const std::string &demangled) {
        // Find the parameter list in parentheses
        size_t paren_start = demangled.find('(');
        size_t paren_end = demangled.rfind(')');

        if (paren_start == std::string::npos || paren_end == std::string::npos ||
            paren_end <= paren_start) {
            return -1;
        }

        std::string params = demangled.substr(paren_start + 1, paren_end - paren_start - 1);

        // Empty parameter list
        if (params.empty() || params == "void") {
            return 0;
        }

        // Count parameters by counting commas at the top level (not inside templates)
        int count = 1;
        int template_depth = 0;
        int paren_depth = 0;

        for (char c: params) {
            if (c == '<') {
                template_depth++;
            } else if (c == '>') {
                template_depth--;
            } else if (c == '(') {
                paren_depth++;
            } else if (c == ')') {
                paren_depth--;
            } else if (c == ',' && template_depth == 0 && paren_depth == 0) {
                count++;
            }
        }

        return count;
    }

    bool ElfSymbolScanner::is_static_method(const std::string &demangled) {
        // Static methods in C++ don't have 'this' pointer
        // We can't easily determine this from the demangled name alone
        // For SurfaceComposerClient methods, they are typically static
        return demangled.find("SurfaceComposerClient::") != std::string::npos;
    }

    bool ElfSymbolScanner::parse_proc_maps(std::vector<LibraryMapping> &mappings) {
        std::ifstream maps("/proc/self/maps");
        if (!maps.is_open()) {
            FUTON_LOGE("ElfSymbolScanner: failed to open /proc/self/maps");
            return false;
        }

        std::string line;
        while (std::getline(maps, line)) {
            LibraryMapping mapping;

            // Parse line format: address perms offset dev inode pathname
            // Example: 7f8a000000-7f8a100000 r-xp 00000000 fd:00 12345 /system/lib64/libgui.so

            std::istringstream iss(line);
            std::string addr_range, perms, offset, dev, inode, path;

            if (!(iss >> addr_range >> perms >> offset >> dev >> inode)) {
                continue;
            }

            // Path is optional
            std::getline(iss >> std::ws, path);

            // Parse address range
            size_t dash_pos = addr_range.find('-');
            if (dash_pos == std::string::npos) {
                continue;
            }

            mapping.base_address = std::stoull(addr_range.substr(0, dash_pos), nullptr, 16);
            mapping.end_address = std::stoull(addr_range.substr(dash_pos + 1), nullptr, 16);
            mapping.path = path;
            mapping.is_readable = (perms.length() > 0 && perms[0] == 'r');
            mapping.is_executable = (perms.length() > 2 && perms[2] == 'x');

            mappings.push_back(mapping);
        }

        return true;
    }

    LibraryMapping ElfSymbolScanner::find_library(const std::string &library_name) {
        std::vector<LibraryMapping> mappings;
        if (!parse_proc_maps(mappings)) {
            return LibraryMapping{};
        }

        // Find the first executable mapping for the library
        for (const auto &mapping: mappings) {
            if (mapping.path.find(library_name) != std::string::npos) {
                // Return the first mapping (usually the base)
                FUTON_LOGD("Found library %s at 0x%lx - 0x%lx (%s)",
                           library_name.c_str(),
                           static_cast<unsigned long>(mapping.base_address),
                           static_cast<unsigned long>(mapping.end_address),
                           mapping.path.c_str());
                return mapping;
            }
        }

        FUTON_LOGW("Library %s not found in /proc/self/maps", library_name.c_str());
        return LibraryMapping{};
    }


    bool ElfSymbolScanner::parse_elf_dynamic_symbols(const std::string &path,
                                                     uintptr_t base_addr,
                                                     const std::regex &pattern,
                                                     std::vector<DiscoveredSymbol> &symbols) {
        // Open the ELF file
        int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) {
            FUTON_LOGE("ElfSymbolScanner: failed to open %s: %s", path.c_str(), strerror(errno));
            return false;
        }

        // Get file size
        struct stat st;
        if (fstat(fd, &st) < 0) {
            FUTON_LOGE("ElfSymbolScanner: fstat failed: %s", strerror(errno));
            close(fd);
            return false;
        }

        // Memory map the file
        void *mapped = mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);

        if (mapped == MAP_FAILED) {
            FUTON_LOGE("ElfSymbolScanner: mmap failed: %s", strerror(errno));
            return false;
        }

        bool success = false;
        const uint8_t *base = static_cast<const uint8_t *>(mapped);

        // Verify ELF magic
        const ElfW_Ehdr *ehdr = reinterpret_cast<const ElfW_Ehdr *>(base);
        if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) {
            FUTON_LOGE("ElfSymbolScanner: invalid ELF magic");
            goto cleanup;
        }

        // Verify 64-bit ELF
        if (ehdr->e_ident[EI_CLASS] != ELFCLASS64) {
            FUTON_LOGE("ElfSymbolScanner: not a 64-bit ELF");
            goto cleanup;
        }

        {
            // Find section header string table
            if (ehdr->e_shstrndx == SHN_UNDEF || ehdr->e_shoff == 0) {
                FUTON_LOGW("ElfSymbolScanner: no section headers, trying dynamic segment");
                // Fall back to parsing from memory
                success = parse_elf_from_memory(base_addr, pattern, symbols);
                goto cleanup;
            }

            const ElfW_Shdr *shdr_table = reinterpret_cast<const ElfW_Shdr *>(base + ehdr->e_shoff);
            const ElfW_Shdr *shstrtab_hdr = &shdr_table[ehdr->e_shstrndx];
            const char *shstrtab = reinterpret_cast<const char *>(base + shstrtab_hdr->sh_offset);

            // Find .dynsym and .dynstr sections
            const ElfW_Shdr *dynsym_hdr = nullptr;
            const ElfW_Shdr *dynstr_hdr = nullptr;

            for (int i = 0; i < ehdr->e_shnum; i++) {
                const ElfW_Shdr *shdr = &shdr_table[i];
                const char *name = shstrtab + shdr->sh_name;

                if (strcmp(name, ".dynsym") == 0) {
                    dynsym_hdr = shdr;
                } else if (strcmp(name, ".dynstr") == 0) {
                    dynstr_hdr = shdr;
                }
            }

            if (!dynsym_hdr || !dynstr_hdr) {
                FUTON_LOGW("ElfSymbolScanner: .dynsym or .dynstr not found");
                success = parse_elf_from_memory(base_addr, pattern, symbols);
                goto cleanup;
            }

            // Parse dynamic symbols
            const ElfW_Sym *dynsym = reinterpret_cast<const ElfW_Sym *>(base +
                                                                        dynsym_hdr->sh_offset);
            const char *dynstr = reinterpret_cast<const char *>(base + dynstr_hdr->sh_offset);
            size_t sym_count = dynsym_hdr->sh_size / sizeof(ElfW_Sym);

            FUTON_LOGD("ElfSymbolScanner: scanning %zu symbols in %s", sym_count, path.c_str());

            for (size_t i = 0; i < sym_count; i++) {
                const ElfW_Sym *sym = &dynsym[i];

                // Skip undefined symbols
                if (sym->st_shndx == SHN_UNDEF) {
                    continue;
                }

                // Skip non-function symbols
                if (ELF64_ST_TYPE(sym->st_info) != STT_FUNC) {
                    continue;
                }

                const char *sym_name = dynstr + sym->st_name;
                if (!sym_name || sym_name[0] == '\0') {
                    continue;
                }

                // Check if symbol matches pattern
                std::string name_str(sym_name);
                if (std::regex_search(name_str, pattern)) {
                    DiscoveredSymbol discovered;
                    discovered.mangled_name = name_str;
                    discovered.demangled_name = demangle(name_str);
                    discovered.address = reinterpret_cast<void *>(base_addr + sym->st_value);
                    discovered.param_count = analyze_param_count(discovered.demangled_name);
                    discovered.is_static_method = is_static_method(discovered.demangled_name);

                    FUTON_LOGI("ElfSymbolScanner: found symbol: %s",
                               discovered.demangled_name.c_str());
                    FUTON_LOGD("  mangled: %s", discovered.mangled_name.c_str());
                    FUTON_LOGD("  address: %p", discovered.address);
                    FUTON_LOGD("  params: %d", discovered.param_count);

                    symbols.push_back(discovered);
                }
            }

            success = true;
        }

        cleanup:
        munmap(mapped, st.st_size);
        return success;
    }

    bool ElfSymbolScanner::parse_elf_from_memory(uintptr_t base_addr,
                                                 const std::regex &pattern,
                                                 std::vector<DiscoveredSymbol> &symbols) {
        // Parse ELF from memory using program headers (for stripped binaries)
        const uint8_t *base = reinterpret_cast<const uint8_t *>(base_addr);
        const ElfW_Ehdr *ehdr = reinterpret_cast<const ElfW_Ehdr *>(base);

        // Verify ELF magic
        if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) {
            FUTON_LOGE("parse_elf_from_memory: invalid ELF magic at %p", base);
            return false;
        }

        // Find PT_DYNAMIC segment
        const ElfW_Phdr *phdr_table = reinterpret_cast<const ElfW_Phdr *>(base + ehdr->e_phoff);
        const ElfW_Dyn *dynamic = nullptr;

        for (int i = 0; i < ehdr->e_phnum; i++) {
            if (phdr_table[i].p_type == PT_DYNAMIC) {
                dynamic = reinterpret_cast<const ElfW_Dyn *>(base + phdr_table[i].p_vaddr);
                break;
            }
        }

        if (!dynamic) {
            FUTON_LOGE("parse_elf_from_memory: PT_DYNAMIC not found");
            return false;
        }

        // Parse dynamic section to find symbol table
        const ElfW_Sym *symtab = nullptr;
        const char *strtab = nullptr;
        size_t strtab_size = 0;
        size_t sym_count = 0;

        // First pass: find addresses
        for (const ElfW_Dyn *dyn = dynamic; dyn->d_tag != DT_NULL; dyn++) {
            switch (dyn->d_tag) {
                case DT_SYMTAB:
                    symtab = reinterpret_cast<const ElfW_Sym *>(base + dyn->d_un.d_ptr);
                    break;
                case DT_STRTAB:
                    strtab = reinterpret_cast<const char *>(base + dyn->d_un.d_ptr);
                    break;
                case DT_STRSZ:
                    strtab_size = dyn->d_un.d_val;
                    break;
                case DT_HASH: {
                    // Old-style hash table: first word is nbucket, second is nchain (= symbol count)
                    const uint32_t *hash = reinterpret_cast<const uint32_t *>(base +
                                                                              dyn->d_un.d_ptr);
                    sym_count = hash[1];
                    break;
                }
                case DT_GNU_HASH: {
                    // GNU hash table is more complex, we'll estimate symbol count
                    // For now, use a reasonable upper bound
                    if (sym_count == 0) {
                        sym_count = 10000;  // Will be bounded by strtab_size check
                    }
                    break;
                }
            }
        }

        if (!symtab || !strtab) {
            FUTON_LOGE("parse_elf_from_memory: symbol table not found");
            return false;
        }

        FUTON_LOGD("parse_elf_from_memory: scanning ~%zu symbols", sym_count);

        // Scan symbols
        for (size_t i = 0; i < sym_count; i++) {
            const ElfW_Sym *sym = &symtab[i];

            // Skip undefined symbols
            if (sym->st_shndx == SHN_UNDEF) {
                continue;
            }

            // Skip non-function symbols
            if (ELF64_ST_TYPE(sym->st_info) != STT_FUNC) {
                continue;
            }

            // Bounds check for string table
            if (sym->st_name >= strtab_size) {
                continue;
            }

            const char *sym_name = strtab + sym->st_name;
            if (!sym_name || sym_name[0] == '\0') {
                continue;
            }

            // Check if symbol matches pattern
            std::string name_str(sym_name);
            if (std::regex_search(name_str, pattern)) {
                DiscoveredSymbol discovered;
                discovered.mangled_name = name_str;
                discovered.demangled_name = demangle(name_str);
                discovered.address = reinterpret_cast<void *>(base_addr + sym->st_value);
                discovered.param_count = analyze_param_count(discovered.demangled_name);
                discovered.is_static_method = is_static_method(discovered.demangled_name);

                FUTON_LOGI("ElfSymbolScanner: found symbol (memory): %s",
                           discovered.demangled_name.c_str());

                symbols.push_back(discovered);
            }
        }

        return true;
    }

    std::vector<DiscoveredSymbol> ElfSymbolScanner::scan_symbols(
            const std::string &library_path,
            const std::string &pattern) {

        std::vector<DiscoveredSymbol> symbols;

        // Find library mapping
        LibraryMapping mapping = find_library(library_path);
        if (mapping.base_address == 0) {
            FUTON_LOGE("scan_symbols: library %s not loaded", library_path.c_str());
            return symbols;
        }

        return scan_symbols(mapping, pattern);
    }

    std::vector<DiscoveredSymbol> ElfSymbolScanner::scan_symbols(
            const LibraryMapping &mapping,
            const std::string &pattern) {

        std::vector<DiscoveredSymbol> symbols;

        if (mapping.base_address == 0) {
            return symbols;
        }

        try {
            std::regex regex_pattern(pattern);

            // Try to parse from file first (more reliable)
            if (!mapping.path.empty()) {
                parse_elf_dynamic_symbols(mapping.path, mapping.base_address,
                                          regex_pattern, symbols);
            }

            // If no symbols found, try parsing from memory
            if (symbols.empty()) {
                FUTON_LOGD("scan_symbols: trying memory-based parsing");
                parse_elf_from_memory(mapping.base_address, regex_pattern, symbols);
            }
        } catch (const std::regex_error &e) {
            FUTON_LOGE("scan_symbols: invalid regex pattern: %s", e.what());
        }

        return symbols;
    }

    DiscoveredSymbol ElfSymbolScanner::find_create_display_symbol(const std::string &libgui_path) {
        LibraryMapping mapping = find_library(libgui_path);
        return find_create_display_symbol(mapping);
    }

    DiscoveredSymbol ElfSymbolScanner::find_create_display_symbol(const LibraryMapping &mapping) {
        // Pattern to match createDisplay or createVirtualDisplay
        const std::string pattern = ".*SurfaceComposerClient.*(createDisplay|createVirtualDisplay).*";

        std::vector<DiscoveredSymbol> symbols = scan_symbols(mapping, pattern);

        if (symbols.empty()) {
            FUTON_LOGE("find_create_display_symbol: no matching symbols found");
            return DiscoveredSymbol{};
        }

        // Sort by parameter count (prefer newer APIs with more parameters)
        std::sort(symbols.begin(), symbols.end(),
                  [](const DiscoveredSymbol &a, const DiscoveredSymbol &b) {
                      // Prefer createVirtualDisplay over createDisplay
                      bool a_virtual =
                              a.demangled_name.find("createVirtualDisplay") != std::string::npos;
                      bool b_virtual =
                              b.demangled_name.find("createVirtualDisplay") != std::string::npos;
                      if (a_virtual != b_virtual) {
                          return a_virtual;
                      }
                      // Then prefer more parameters (newer API)
                      return a.param_count > b.param_count;
                  });

        FUTON_LOGI("find_create_display_symbol: selected %s (params=%d)",
                   symbols[0].demangled_name.c_str(), symbols[0].param_count);

        return symbols[0];
    }

} // namespace futon::vision
