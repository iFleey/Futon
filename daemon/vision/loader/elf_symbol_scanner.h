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

#ifndef FUTON_VISION_LOADER_ELF_SYMBOL_SCANNER_H
#define FUTON_VISION_LOADER_ELF_SYMBOL_SCANNER_H

#include <cstdint>
#include <string>
#include <vector>
#include <functional>
#include <regex>

namespace futon::vision {

/**
 * Information about a discovered symbol.
 */
    struct DiscoveredSymbol {
        std::string mangled_name;
        std::string demangled_name;
        void *address = nullptr;
        int param_count = -1;
        bool is_static_method = false;
    };

/**
 * Information about a loaded library from /proc/self/maps.
 */
    struct LibraryMapping {
        uintptr_t base_address = 0;
        uintptr_t end_address = 0;
        std::string path;
        bool is_executable = false;
        bool is_readable = false;
    };

/**
 * ElfSymbolScanner - Dynamic ELF symbol table scanner.
 *
 * Scans loaded libraries at runtime to discover symbols by pattern matching.
 * This enables compatibility with different Android versions where symbol
 * names may vary.
 */
    class ElfSymbolScanner {
    public:
        ElfSymbolScanner();

        ~ElfSymbolScanner();

        /**
         * Find a library's base address from /proc/self/maps.
         * @param library_name Library name (e.g., "libgui.so")
         * @return Library mapping info, or empty if not found
         */
        LibraryMapping find_library(const std::string &library_name);

        /**
         * Scan a library for symbols matching a regex pattern.
         * @param library_path Full path to the library
         * @param pattern Regex pattern to match symbol names
         * @return Vector of discovered symbols
         */
        std::vector<DiscoveredSymbol> scan_symbols(
                const std::string &library_path,
                const std::string &pattern);

        /**
         * Scan a library using its mapping info.
         * @param mapping Library mapping from find_library()
         * @param pattern Regex pattern to match symbol names
         * @return Vector of discovered symbols
         */
        std::vector<DiscoveredSymbol> scan_symbols(
                const LibraryMapping &mapping,
                const std::string &pattern);

        /**
         * Find the best matching createDisplay symbol.
         * Searches for SurfaceComposerClient::createDisplay or createVirtualDisplay.
         * @param libgui_path Path to libgui.so
         * @return Best matching symbol, or empty if not found
         */
        DiscoveredSymbol find_create_display_symbol(const std::string &libgui_path);

        /**
         * Find the best matching createDisplay symbol using library mapping.
         */
        DiscoveredSymbol find_create_display_symbol(const LibraryMapping &mapping);

        /**
         * Demangle a C++ symbol name.
         * @param mangled Mangled symbol name
         * @return Demangled name, or original if demangling fails
         */
        static std::string demangle(const std::string &mangled);

        /**
         * Analyze a demangled signature to count parameters.
         * @param demangled Demangled function signature
         * @return Number of parameters, or -1 if analysis fails
         */
        static int analyze_param_count(const std::string &demangled);

        /**
         * Check if a symbol is a static method.
         * @param demangled Demangled function signature
         * @return true if static method
         */
        static bool is_static_method(const std::string &demangled);

    private:
        struct ElfInfo;

        bool parse_proc_maps(std::vector<LibraryMapping> &mappings);

        bool parse_elf_dynamic_symbols(const std::string &path,
                                       uintptr_t base_addr,
                                       const std::regex &pattern,
                                       std::vector<DiscoveredSymbol> &symbols);

        bool parse_elf_from_memory(uintptr_t base_addr,
                                   const std::regex &pattern,
                                   std::vector<DiscoveredSymbol> &symbols);
    };

} // namespace futon::vision

#endif // FUTON_VISION_LOADER_ELF_SYMBOL_SCANNER_H
