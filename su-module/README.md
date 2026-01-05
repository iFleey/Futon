# Futon Daemon Module

SukiSU/KernelSU module for SELinux policy patching and daemon deployment.

## Structure

```
su-module/
├── module.prop
├── post-fs-data.sh
└── verify_selinux.sh
```

## Behavior

`post-fs-data.sh` runs at boot:

1. Detects device architecture
2. Copies daemon binary to `/data/adb/futon/futon_daemon`
3. Patches SELinux policies

Daemon lifecycle is managed by the app, not the module.

## SELinux Policies

Declares `futon_daemon` type with access to:

- SurfaceFlinger, GPU, graphics devices
- ASHMEM for cross-process buffers
- Input devices for touch injection
- Binder for service registration
- HAL graphics (Android 14+)

## Verification

```bash
adb shell sh /data/adb/modules/futon_daemon/verify_selinux.sh
```

## Troubleshooting

If AVC denials appear:

1. `setenforce 0` to test in permissive mode
2. Check daemon context: `cat /proc/<pid>/attr/current`
3. Check logs: `cat /data/adb/futon/daemon.log`
