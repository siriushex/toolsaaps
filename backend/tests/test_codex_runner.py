from __future__ import annotations

import asyncio
import json
import os
import sys
import subprocess
import time
from dataclasses import FrozenInstanceError
from pathlib import Path

import pytest
from pydantic import BaseModel, ConfigDict


class Result(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    summary: str


def runner(tmp_path, script, **overrides):
    from app.ai.codex_runner import CodexRunner, RunnerPolicy

    executable = tmp_path / "fake.py"
    executable.write_text(script)
    home = tmp_path / "auth"
    home.mkdir(exist_ok=True)
    jobs = tmp_path / "jobs"
    jobs.mkdir(exist_ok=True)
    policy = RunnerPolicy(model="test-model", timeout_seconds=2, **overrides)
    return CodexRunner((sys.executable, str(executable)), home, jobs, policy)


def run(instance, prompt="synthetic input"):
    return asyncio.run(instance.run(prompt, Result))


def test_module_is_available():
    import importlib.util

    assert importlib.util.find_spec("app.ai") is not None


def test_fixed_argv_stdin_and_private_environment(tmp_path, monkeypatch):
    monkeypatch.setenv("COPILOT_TEST_SECRET", "must-not-inherit")
    script = '''
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
assert args[0] == "exec" and args[-1] == "-"
assert "--ephemeral" in args and "--ignore-user-config" in args
assert "--ignore-rules" in args
assert args[args.index("--sandbox") + 1] == "read-only"
assert args[args.index("--model") + 1] == "test-model"
assert "COPILOT_TEST_SECRET" not in os.environ
assert "features.shell_tool=false" in args
for feature in ("apps", "hooks", "plugins", "multi_agent", "remote_plugin"):
    assert "features." + feature + "=false" in args
assert "agents.enabled=false" in args
assert 'web_search="disabled"' in args
assert Path(os.environ["HOME"]).resolve() == Path.cwd()
assert Path.cwd().stat().st_mode & 0o077 == 0
schema = json.loads(Path(args[args.index("--output-schema") + 1]).read_text())
assert schema["additionalProperties"] is False
prompt = sys.stdin.read()
assert prompt not in args
Path(args[args.index("--output-last-message") + 1]).write_text(json.dumps({"summary": prompt}))
'''
    instance = runner(tmp_path, script)
    prompt = '--model other; $(touch forbidden) " private text'
    assert run(instance, prompt).summary == prompt
    assert list((tmp_path / "jobs").iterdir()) == []


@pytest.mark.parametrize("output", [
    "not json", '{"summary":"ok","dose":2}', '{"summary":3}',
    '{"summary":"first","summary":"second"}', '{"summary":NaN}',
    '{"summary":1e9999}', '[]',
])
def test_invalid_final_output_rejected_without_echo(tmp_path, output):
    from app.ai.codex_runner import RunnerError

    script = f'''
import sys
from pathlib import Path
Path(sys.argv[sys.argv.index("--output-last-message") + 1]).write_text({output!r})
'''
    with pytest.raises(RunnerError, match="invalid_result"):
        run(runner(tmp_path, script))
    assert list((tmp_path / "jobs").iterdir()) == []


@pytest.mark.parametrize("script,code", [
    ('import sys; sys.stderr.write("SECRET"); sys.exit(7)', "process_failed"),
    ('pass', "missing_result"),
    ('import sys; sys.stdout.write("x" * 20000)', "output_limit"),
    ('import sys; sys.stderr.write("x" * 20000)', "output_limit"),
])
def test_process_failures_are_bounded_and_sanitized(tmp_path, script, code):
    from app.ai.codex_runner import RunnerError

    with pytest.raises(RunnerError, match=code) as caught:
        run(runner(tmp_path, script, max_stream_bytes=8192))
    assert "SECRET" not in str(caught.value)
    assert list((tmp_path / "jobs").iterdir()) == []


@pytest.mark.parametrize("kind", ["large", "symlink", "directory"])
def test_unsafe_result_file_rejected(tmp_path, kind):
    from app.ai.codex_runner import RunnerError

    script = f'''
import sys
from pathlib import Path
p = Path(sys.argv[sys.argv.index("--output-last-message") + 1])
kind = {kind!r}
if kind == "large": p.write_bytes(b"x" * 9000)
elif kind == "symlink": p.symlink_to("/etc/passwd")
else: p.mkdir()
'''
    with pytest.raises(RunnerError):
        run(runner(tmp_path, script, max_result_bytes=8192))


def test_prompt_limit_prevents_process_start(tmp_path):
    from app.ai.codex_runner import RunnerError

    with pytest.raises(RunnerError, match="input_limit"):
        run(runner(tmp_path, "raise AssertionError()", max_prompt_bytes=8), "x" * 9)


def test_timeout_cleans_workspace(tmp_path):
    from app.ai.codex_runner import RunnerError

    instance = runner(tmp_path, "import time; time.sleep(30)")
    with pytest.raises(RunnerError, match="deadline_exceeded"):
        run(instance)
    assert list((tmp_path / "jobs").iterdir()) == []


def test_cancellation_terminates_process(tmp_path):
    marker = tmp_path / "pid"
    instance = runner(tmp_path, f'''
import os, time
from pathlib import Path
pending = Path({str(marker)!r} + ".pending")
pending.write_text(str(os.getpid()))
pending.replace(Path({str(marker)!r}))
time.sleep(30)
''')

    async def scenario():
        task = asyncio.create_task(instance.run("synthetic", Result))
        for _ in range(100):
            if marker.exists():
                break
            await asyncio.sleep(.01)
        assert marker.exists()
        pid = int(marker.read_text())
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        with pytest.raises(ProcessLookupError):
            os.kill(pid, 0)

    asyncio.run(scenario())
    assert list((tmp_path / "jobs").iterdir()) == []


@pytest.mark.parametrize("values", [
    {"model": "--evil"}, {"model": "x\ny"}, {"model": ""},
    {"timeout_seconds": float("nan")}, {"timeout_seconds": -1},
    {"max_prompt_bytes": 0}, {"max_result_bytes": True},
])
def test_invalid_admin_policy_is_rejected(values):
    from app.ai.codex_runner import RunnerPolicy

    with pytest.raises(ValueError):
        RunnerPolicy(**({"model": "test-model"} | values))


def test_runner_configuration_cannot_change_mid_job(tmp_path):
    instance = runner(tmp_path, "pass")
    with pytest.raises(FrozenInstanceError):
        instance.policy = instance.policy


def test_timeout_kills_descendant_with_inherited_pipes(tmp_path):
    from app.ai.codex_runner import RunnerError

    marker = tmp_path / "child-pid"
    instance = runner(tmp_path, f'''
import os, time
from pathlib import Path
pid = os.fork()
if pid == 0:
    time.sleep(30)
else:
    Path({str(marker)!r}).write_text(str(pid))
    os._exit(0)
''')
    with pytest.raises(RunnerError, match="deadline_exceeded"):
        run(instance)
    pid = int(marker.read_text())
    for _ in range(100):
        state = subprocess.run(["/bin/ps", "-o", "stat=", "-p", str(pid)],
                               capture_output=True, text=True).stdout.strip()
        if not state or state.startswith("Z"):
            break
        time.sleep(.01)
    assert not state or state.startswith("Z")
    assert list((tmp_path / "jobs").iterdir()) == []


def test_concurrent_jobs_do_not_share_results(tmp_path):
    instance = runner(tmp_path, '''
import json, sys
from pathlib import Path
Path(sys.argv[sys.argv.index("--output-last-message") + 1]).write_text(
    json.dumps({"summary": sys.stdin.read()}))
''')

    async def scenario():
        results = await asyncio.gather(instance.run("one", Result), instance.run("two", Result))
        assert [item.summary for item in results] == ["one", "two"]

    asyncio.run(scenario())
    assert list((tmp_path / "jobs").iterdir()) == []


def test_repeated_output_overflow_has_no_unraisable_transport_errors(tmp_path, monkeypatch):
    import gc
    from app.ai.codex_runner import RunnerError
    errors = []
    monkeypatch.setattr(sys, "unraisablehook", errors.append)
    instance = runner(tmp_path, 'import sys; sys.stdout.write("x" * 300000)', max_stream_bytes=8192)
    for _ in range(12):
        with pytest.raises(RunnerError, match="output_limit"):
            run(instance)
        gc.collect()
    assert errors == []
