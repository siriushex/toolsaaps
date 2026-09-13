from uuid import uuid4

import pytest

from app.ai.rootfs_profile import offline_properties


def test_offline_profile_has_bounded_private_filesystem_and_no_network():
    properties = offline_properties(998, 998)
    assert "PrivateNetwork=yes" in properties
    assert "RestrictAddressFamilies=AF_UNIX" in properties
    assert "ProtectSystem=strict" in properties
    assert "KillMode=control-group" in properties
    assert "MemoryMax=384M" in properties
    assert "TasksMax=32" in properties
    assert "RuntimeMaxSec=30" in properties
    mounts = [p for p in properties if p.startswith("BindReadOnlyPaths=")]
    assert len(mounts) == 1
    assert "auth.json:/state/auth.json" in mounts[0]
    assert "/root" not in mounts[0]
    assert "/var/lib/copilot-ai/codex:" not in mounts[0]
    assert "TemporaryFileSystem=/state:rw,size=16M,mode=0700,uid=998,gid=998 /work:rw,size=16M,mode=0700,uid=998,gid=998" in properties


@pytest.mark.parametrize("uid,gid", [(-1, 998), (0, 998), (998, 0), (True, 998), ("998", 998)])
def test_rejects_root_or_invalid_identity(uid, gid):
    with pytest.raises(ValueError):
        offline_properties(uid, gid)
