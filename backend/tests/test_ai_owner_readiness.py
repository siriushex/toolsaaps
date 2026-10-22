from types import SimpleNamespace
import stat
from unittest.mock import Mock, patch

import pytest

from app.ai import owner_readiness as readiness


def info(**changes):
    return SimpleNamespace(**(dict(st_mode=stat.S_IFSOCK | 0o660, st_nlink=1,
                                  st_uid=0, st_gid=123, st_dev=1, st_ino=2) | changes))


@pytest.mark.parametrize("changes", [dict(st_mode=stat.S_IFREG | 0o660),
    dict(st_mode=stat.S_IFSOCK | 0o666), dict(st_uid=123), dict(st_gid=0), dict(st_nlink=2)])
def test_rejects_unsafe_socket_metadata(changes):
    assert not readiness.valid_listener(info(**changes), (42, 0, 0), 42, 123)


@pytest.mark.parametrize("pid,peer", [(0, (0, 0, 0)), (42, (43, 0, 0)),
    (42, (42, 123, 0)), (42, (42, 0, 123))])
def test_rejects_wrong_service_peer(pid, peer):
    assert not readiness.valid_listener(info(), peer, pid, 123)


def test_accepts_exact_service_peer():
    assert readiness.valid_listener(info(), (42, 0, 0), 42, 123)


@pytest.mark.parametrize("values,changed_inode", [([42, 43], False), ([42, 42], True), ([42, 42], False)])
def test_connect_is_frame_free_and_rechecks_identity(values, changed_inode):
    path = Mock()
    path.lstat.side_effect = [info(), info(st_ino=3 if changed_inode else 2)]
    client = Mock()
    client.getsockopt.return_value = readiness.struct.pack("3i", 42, 0, 0)
    context = Mock()
    context.__enter__ = Mock(return_value=client)
    context.__exit__ = Mock(return_value=False)
    with patch.object(readiness, "main_pid", side_effect=values), \
            patch.object(readiness.socket, "SO_PEERCRED", 17, create=True), \
            patch.object(readiness.socket, "socket", return_value=context):
        result = readiness.listener_ready("personal-ai-image.service", path, 123)
    assert result is (values == [42, 42] and not changed_inode)
    client.send.assert_not_called()
    client.sendall.assert_not_called()
    client.recv.assert_not_called()


def test_unsupported_peer_credentials_fail_closed():
    with patch.object(readiness, "socket", SimpleNamespace()):
        assert not readiness.listener_ready("personal-ai-image.service", Mock(), 123)


def test_waits_for_both_dependencies_and_has_a_deadline():
    current = [0.0]
    calls = []
    def sleep(value):
        current[0] += value
    def check(unit, path, gid):
        calls.append(unit)
        return unit.endswith("text.service") or current[0] >= 1
    assert readiness.wait_for_dependencies(123, check=check, clock=lambda: current[0], sleep=sleep)
    assert set(calls) == {unit for unit, _ in readiness.ENDPOINTS}
    current[0] = 0
    assert not readiness.wait_for_dependencies(123, check=lambda *_: False,
                                               clock=lambda: current[0], sleep=sleep)
    assert 25 <= current[0] < 26


def test_success_after_deadline_is_rejected():
    current = [0.0]
    def check(*_):
        current[0] += 15
        return True
    assert not readiness.wait_for_dependencies(123, check=check, clock=lambda: current[0],
                                               sleep=lambda _: None)
