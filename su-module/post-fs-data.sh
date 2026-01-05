#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later

MODDIR=${0%/*}
BASE_DIR="/data/adb/futon"
DAEMON_PATH="$BASE_DIR/futon_daemon"
LIB_DIR="$BASE_DIR/lib"
LOG_FILE="$BASE_DIR/daemon.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

get_arch() {
    local abi=$(getprop ro.product.cpu.abi)
    case "$abi" in
        arm64-v8a*) echo "arm64-v8a" ;;
        armeabi-v7a*) echo "armeabi-v7a" ;;
        x86_64*) echo "x86_64" ;;
        x86*) echo "x86" ;;
    esac
}

deploy_daemon() {
    local arch=$(get_arch)
    [ -z "$arch" ] && return 1

    local src="$MODDIR/bin/$arch/futon_daemon"
    [ ! -f "$src" ] && return 1

    mkdir -p "$BASE_DIR"
    cp "$src" "$DAEMON_PATH"
    chmod 755 "$DAEMON_PATH"
    chown root:root "$DAEMON_PATH"
    log "Deployed daemon: $arch"

    # Deploy LiteRT shared libraries
    local lib_src="$MODDIR/lib/$arch"
    if [ -d "$lib_src" ]; then
        mkdir -p "$LIB_DIR"
        cp "$lib_src"/*.so "$LIB_DIR/" 2>/dev/null
        chmod 755 "$LIB_DIR"/*.so 2>/dev/null
        chown root:root "$LIB_DIR"/*.so 2>/dev/null
        log "Deployed libraries: $(ls -1 $LIB_DIR/*.so 2>/dev/null | wc -l) files"
    fi
}

patch_selinux() {
    # Detect policy tool: KernelSU, Magisk, or SuperSU
    if command -v ksud >/dev/null 2>&1; then
        POLICY_CMD="ksud sepolicy patch"
    elif command -v magiskpolicy >/dev/null 2>&1; then
        POLICY_CMD="magiskpolicy --live"
    elif command -v supolicy >/dev/null 2>&1; then
        POLICY_CMD="supolicy --live"
    else
        log "No SELinux policy tool found"
        return 1
    fi
    
    log "Using policy tool: $POLICY_CMD"
    
    # Helper function to apply policy
    apply_policy() {
        $POLICY_CMD "$1" 2>/dev/null
    }

    apply_policy "type futon_daemon"
    apply_policy "typeattribute futon_daemon domain"
    apply_policy "typeattribute futon_daemon coredomain"
    apply_policy "typeattribute futon_daemon mlstrustedsubject"

    apply_policy "allow su futon_daemon process { transition }"
    apply_policy "allow magisk futon_daemon process { transition }"
    apply_policy "allow futon_daemon futon_daemon process { fork sigchld sigkill sigstop signull signal getsched setsched getpgid setpgid getcap setcap getattr setrlimit }"
    apply_policy "allow futon_daemon futon_daemon file { read execute execute_no_trans entrypoint }"

    apply_policy "allow futon_daemon adb_data_file dir { read write add_name remove_name search create getattr setattr }"
    apply_policy "allow futon_daemon adb_data_file file { read write create unlink open getattr setattr execute execute_no_trans }"

    apply_policy "allow futon_daemon surfaceflinger_service service_manager { find }"
    apply_policy "allow futon_daemon gpu_device chr_file { read write ioctl open }"
    apply_policy "allow futon_daemon graphics_device chr_file { read write ioctl open }"
    apply_policy "allow futon_daemon ashmem_device chr_file { read write ioctl open }"

    apply_policy "allow futon_daemon uhid_device chr_file { read write ioctl open }"
    apply_policy "allow futon_daemon input_device chr_file { read write ioctl open }"
    apply_policy "allow futon_daemon input_device dir { read open search }"

    # Shell execution permissions for am, monkey, cmd commands
    apply_policy "allow futon_daemon shell_exec file { read execute open getattr execute_no_trans }"
    apply_policy "allow futon_daemon system_file file { read execute open getattr execute_no_trans }"
    apply_policy "allow futon_daemon system_file dir { read open search getattr }"
    apply_policy "allow futon_daemon toolbox_exec file { read execute open getattr execute_no_trans }"
    
    # Allow executing am, monkey, cmd (activity manager commands)
    apply_policy "allow futon_daemon zygote_exec file { read execute open getattr }"
    apply_policy "allow futon_daemon activity_service service_manager { find }"
    apply_policy "allow futon_daemon package_service service_manager { find }"
    apply_policy "allow futon_daemon activity_task_service service_manager { find }"
    
    # Process execution and pipe operations
    apply_policy "allow futon_daemon futon_daemon fifo_file { read write create open getattr }"
    apply_policy "allow futon_daemon devpts chr_file { read write open getattr }"
    apply_policy "allow futon_daemon null_device chr_file { read write open }"
    
    # Allow shell domain transition for subprocess
    apply_policy "allow futon_daemon shell process { transition }"
    apply_policy "allow futon_daemon shell fd { use }"
    apply_policy "allow shell futon_daemon fd { use }"
    apply_policy "allow shell futon_daemon fifo_file { read write getattr }"
    apply_policy "allow shell futon_daemon process { sigchld }"

    apply_policy "allow futon_daemon servicemanager binder { call transfer }"
    apply_policy "allow futon_daemon service_manager_type service_manager { add find }"

    # The daemon's service is registered as default_android_service type
    # Allow untrusted_app to find default_android_service (where futon_daemon is registered)
    apply_policy "allow untrusted_app default_android_service service_manager { find }"
    apply_policy "allow untrusted_app_25 default_android_service service_manager { find }"
    apply_policy "allow untrusted_app_27 default_android_service service_manager { find }"
    apply_policy "allow untrusted_app_29 default_android_service service_manager { find }"
    apply_policy "allow untrusted_app_30 default_android_service service_manager { find }"
    apply_policy "allow untrusted_app_32 default_android_service service_manager { find }"
    
    # Allow apps to use binder with futon_daemon
    apply_policy "allow untrusted_app futon_daemon binder { call transfer }"
    apply_policy "allow untrusted_app_25 futon_daemon binder { call transfer }"
    apply_policy "allow untrusted_app_27 futon_daemon binder { call transfer }"
    apply_policy "allow untrusted_app_29 futon_daemon binder { call transfer }"
    apply_policy "allow untrusted_app_30 futon_daemon binder { call transfer }"
    apply_policy "allow untrusted_app_32 futon_daemon binder { call transfer }"
    
    # Allow futon_daemon to call back to apps (for callbacks)
    apply_policy "allow futon_daemon untrusted_app binder { call transfer }"
    apply_policy "allow futon_daemon untrusted_app_25 binder { call transfer }"
    apply_policy "allow futon_daemon untrusted_app_27 binder { call transfer }"
    apply_policy "allow futon_daemon untrusted_app_29 binder { call transfer }"
    apply_policy "allow futon_daemon untrusted_app_30 binder { call transfer }"
    apply_policy "allow futon_daemon untrusted_app_32 binder { call transfer }"
    
    # Platform app support
    apply_policy "allow platform_app default_android_service service_manager { find }"
    apply_policy "allow platform_app futon_daemon binder { call transfer }"
    apply_policy "allow futon_daemon platform_app binder { call transfer }"
    
    # Priv-app support
    apply_policy "allow priv_app default_android_service service_manager { find }"
    apply_policy "allow priv_app futon_daemon binder { call transfer }"
    apply_policy "allow futon_daemon priv_app binder { call transfer }"
    
    # System app support
    apply_policy "allow system_app default_android_service service_manager { find }"
    apply_policy "allow system_app futon_daemon binder { call transfer }"
    apply_policy "allow futon_daemon system_app binder { call transfer }"

    API_LEVEL=$(getprop ro.build.version.sdk)
    if [ "$API_LEVEL" -ge 34 ] 2>/dev/null; then
        apply_policy "allow futon_daemon hal_graphics_composer_default hwservice_manager { find }"
        apply_policy "allow futon_daemon hal_graphics_composer_service hwservice_manager { find }"
        apply_policy "allow futon_daemon hal_graphics_allocator_default hwservice_manager { find }"
    fi
}

deploy_daemon || exit 1
patch_selinux

# Set uinput permissions for touch injection
# /dev/uinput is owned by uhid:uhid with 660 permissions by default
# We need to make it accessible for the daemon
if [ -c /dev/uinput ]; then
    chmod 666 /dev/uinput
    log "Set /dev/uinput permissions to 666"
fi
