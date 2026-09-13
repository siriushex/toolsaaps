"""Trusted, offline-only systemd profile. Not an inference deployment profile."""


def offline_properties(uid: int, gid: int) -> tuple[str, ...]:
    if any(type(value) is not int or not 0 < value < 2**31 for value in (uid, gid)):
        raise ValueError("nonroot_identity_required")
    private = f"rw,size=16M,mode=0700,uid={uid},gid={gid}"
    return (
        "User=copilot-ai", "Group=copilot-ai",
        "RootDirectory=/opt/copilot-ai/rootfs-offline-v1", "MountAPIVFS=yes",
        "WorkingDirectory=/work", "ProtectSystem=strict", "ProtectHome=yes",
        "PrivateNetwork=yes", "RestrictAddressFamilies=AF_UNIX", "PrivateDevices=yes",
        "PrivateTmp=yes", "NoNewPrivileges=yes", "CapabilityBoundingSet=",
        "ProtectKernelTunables=yes", "ProtectKernelModules=yes", "ProtectControlGroups=yes",
        "ProtectProc=invisible", "ProcSubset=pid", "RestrictSUIDSGID=yes",
        "KillMode=control-group", "TimeoutStopSec=3", "RuntimeMaxSec=30",
        "MemoryMax=384M", "MemorySwapMax=0", "CPUQuota=25%", "TasksMax=32", "UMask=0077",
        "TemporaryFileSystem=/state:" + private + " /work:" + private,
        "BindReadOnlyPaths=/opt/copilot-ai/runtime/codex-0.153.4:/bin/codex "
        "/var/lib/copilot-ai/codex/auth.json:/state/auth.json",
        "Environment=HOME=/work CODEX_HOME=/state PATH=/bin LANG=C.UTF-8",
    )
