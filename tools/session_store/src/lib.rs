// SPDX-License-Identifier: Apache-2.0
use std::fs;
#[cfg(unix)]
use std::fs::File;
use std::io::{self, Write};
use std::path::Path;

pub fn write_private_file(path: &Path, data: &[u8]) -> io::Result<()> {
    let parent = path
        .parent()
        .filter(|value| !value.as_os_str().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "session directory required"))?;
    fs::create_dir_all(parent)?;
    let mut file = tempfile::Builder::new()
        .prefix(".session-")
        .tempfile_in(parent)?;
    #[cfg(all(test, unix))]
    tests::checkpoint("created")?;
    file.write_all(data)?;
    #[cfg(all(test, unix))]
    tests::checkpoint("written")?;
    file.as_file().sync_all()?;
    #[cfg(all(test, unix))]
    tests::checkpoint("file_synced")?;
    file.persist(path).map_err(|failure| failure.error)?;
    #[cfg(all(test, unix))]
    tests::checkpoint("replaced")?;
    #[cfg(unix)]
    File::open(parent)?.sync_all()?;
    #[cfg(all(test, unix))]
    tests::checkpoint("directory_synced")?;
    Ok(())
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    use std::process::{Child, Command, Stdio};
    use std::time::{Duration, Instant};
    use tempfile::TempDir;

    const OLD: &[u8] = b"{\"session\":\"synthetic-old-state\"}";
    const NEW: &[u8] = b"{\"session\":\"synthetic-new-state\"}";

    struct Worker(Child);

    impl Drop for Worker {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }

    pub(super) fn checkpoint(stage: &str) -> io::Result<()> {
        if std::env::var("SESSION_CASE_FAIL_AT").as_deref() == Ok(stage) {
            return Err(io::Error::other("synthetic storage failure"));
        }
        if std::env::var("SESSION_CASE_STOP_AT").as_deref() == Ok(stage) {
            let root = std::env::var_os("SESSION_CASE_ROOT").expect("synthetic root");
            fs::write(Path::new(&root).join("ready"), stage)?;
            loop {
                std::thread::park();
            }
        }
        Ok(())
    }

    #[test]
    #[ignore = "entry point for isolated synthetic subprocesses"]
    fn child_writer() {
        let root = std::env::var_os("SESSION_CASE_ROOT").expect("synthetic root");
        let result = write_private_file(&Path::new(&root).join("auth.json"), NEW);
        std::process::exit(if result.is_ok() { 0 } else { 23 });
    }

    fn start(root: &Path, stage: &str, failure: bool) -> Worker {
        let mut command = Command::new(std::env::current_exe().unwrap());
        let module = module_path!().split_once("::").unwrap().1;
        let child_test = format!("{module}::child_writer");
        command
            .args(["--exact", &child_test, "--ignored", "--nocapture"])
            .env_remove("SESSION_CASE_STOP_AT")
            .env_remove("SESSION_CASE_FAIL_AT")
            .env("SESSION_CASE_ROOT", root)
            .env(
                if failure {
                    "SESSION_CASE_FAIL_AT"
                } else {
                    "SESSION_CASE_STOP_AT"
                },
                stage,
            )
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        Worker(command.spawn().unwrap())
    }

    fn await_barrier(worker: &mut Worker, root: &Path) {
        let deadline = Instant::now() + Duration::from_secs(15);
        while !root.join("ready").exists() {
            assert!(
                worker.0.try_wait().unwrap().is_none(),
                "writer exited before barrier"
            );
            assert!(Instant::now() < deadline, "writer did not reach barrier");
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn await_exit(worker: &mut Worker) -> std::process::ExitStatus {
        let deadline = Instant::now() + Duration::from_secs(15);
        loop {
            if let Some(status) = worker.0.try_wait().unwrap() {
                return status;
            }
            assert!(Instant::now() < deadline, "writer did not exit");
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn temporary_files(root: &Path) -> Vec<std::path::PathBuf> {
        fs::read_dir(root)
            .unwrap()
            .map(|entry| entry.unwrap().path())
            .filter(|path| {
                path.file_name()
                    .unwrap()
                    .to_string_lossy()
                    .starts_with(".session-")
            })
            .collect()
    }

    #[test]
    fn pre_replace_interruptions_preserve_live_state() {
        for stage in ["created", "written", "file_synced"] {
            let root = TempDir::new().unwrap();
            let target = root.path().join("auth.json");
            fs::write(&target, OLD).unwrap();
            let mut worker = start(root.path(), stage, false);
            await_barrier(&mut worker, root.path());
            worker.0.kill().unwrap();
            worker.0.wait().unwrap();
            assert_eq!(fs::read(&target).unwrap(), OLD, "interruption at {stage}");
            let remaining = temporary_files(root.path());
            assert_eq!(remaining.len(), 1);
            assert_eq!(fs::metadata(&remaining[0]).unwrap().mode() & 0o777, 0o600);
        }
    }

    #[test]
    fn post_replace_interruptions_leave_new_complete_state() {
        for stage in ["replaced", "directory_synced"] {
            let root = TempDir::new().unwrap();
            let target = root.path().join("auth.json");
            fs::write(&target, OLD).unwrap();
            let mut worker = start(root.path(), stage, false);
            await_barrier(&mut worker, root.path());
            worker.0.kill().unwrap();
            worker.0.wait().unwrap();
            assert_eq!(fs::read(&target).unwrap(), NEW);
            assert_eq!(fs::metadata(&target).unwrap().mode() & 0o777, 0o600);
            assert!(temporary_files(root.path()).is_empty());
        }
    }

    #[test]
    fn failures_before_replace_preserve_old_state_and_clean_temporary() {
        for stage in ["created", "written", "file_synced"] {
            let root = TempDir::new().unwrap();
            let target = root.path().join("auth.json");
            fs::write(&target, OLD).unwrap();
            let mut worker = start(root.path(), stage, true);
            assert_eq!(await_exit(&mut worker).code(), Some(23));
            assert_eq!(fs::read(&target).unwrap(), OLD);
            assert!(temporary_files(root.path()).is_empty());
        }
    }

    #[test]
    fn failures_after_replace_do_not_restore_old_state() {
        for stage in ["replaced", "directory_synced"] {
            let root = TempDir::new().unwrap();
            let target = root.path().join("auth.json");
            fs::write(&target, OLD).unwrap();
            let mut worker = start(root.path(), stage, true);
            assert_eq!(await_exit(&mut worker).code(), Some(23));
            assert_eq!(fs::read(&target).unwrap(), NEW);
            assert!(temporary_files(root.path()).is_empty());
        }
    }

    #[test]
    fn successful_replacement_is_private_and_keeps_open_reader_snapshot() {
        use std::io::Read;
        let root = TempDir::new().unwrap();
        let target = root.path().join("auth.json");
        fs::write(&target, OLD).unwrap();
        fs::set_permissions(&target, fs::Permissions::from_mode(0o644)).unwrap();
        let mut reader = File::open(&target).unwrap();
        let previous_inode = reader.metadata().unwrap().ino();
        write_private_file(&target, NEW).unwrap();
        assert_eq!(fs::read(&target).unwrap(), NEW);
        let current = fs::metadata(&target).unwrap();
        assert_eq!(current.mode() & 0o777, 0o600);
        assert_ne!(current.ino(), previous_inode);
        let mut old_snapshot = Vec::new();
        reader.read_to_end(&mut old_snapshot).unwrap();
        assert_eq!(old_snapshot, OLD);
        assert!(temporary_files(root.path()).is_empty());
    }

    #[test]
    fn first_save_creates_private_file_and_missing_parent() {
        let root = TempDir::new().unwrap();
        let target = root.path().join("new-parent/auth.json");
        write_private_file(&target, NEW).unwrap();
        assert_eq!(fs::read(&target).unwrap(), NEW);
        assert_eq!(fs::metadata(target).unwrap().mode() & 0o777, 0o600);
    }

    #[test]
    fn rename_failure_preserves_destination_and_cleans_temporary() {
        let root = TempDir::new().unwrap();
        let target = root.path().join("auth.json");
        fs::create_dir(&target).unwrap();
        fs::write(target.join("synthetic-state"), OLD).unwrap();
        assert!(write_private_file(&target, NEW).is_err());
        assert_eq!(fs::read(target.join("synthetic-state")).unwrap(), OLD);
        assert!(temporary_files(root.path()).is_empty());
    }

    #[test]
    fn parent_file_refuses_write() {
        let root = TempDir::new().unwrap();
        let parent = root.path().join("parent");
        fs::write(&parent, OLD).unwrap();
        assert!(write_private_file(&parent.join("auth.json"), NEW).is_err());
        assert_eq!(fs::read(parent).unwrap(), OLD);
    }

    #[test]
    fn missing_directory_argument_refuses_write() {
        assert_eq!(
            write_private_file(Path::new("auth.json"), NEW)
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidInput
        );
    }

    #[test]
    fn concurrent_readers_observe_only_complete_versions() {
        let root = TempDir::new().unwrap();
        let target = root.path().join("auth.json");
        write_private_file(&target, OLD).unwrap();
        let reader_target = target.clone();
        let barrier = std::sync::Arc::new(std::sync::Barrier::new(2));
        let reader_barrier = barrier.clone();
        let reader = std::thread::spawn(move || {
            reader_barrier.wait();
            for _ in 0..10_000 {
                let snapshot = fs::read(&reader_target).unwrap();
                assert!(snapshot == OLD || snapshot == NEW);
            }
        });
        barrier.wait();
        for turn in 0..32 {
            write_private_file(&target, if turn % 2 == 0 { NEW } else { OLD }).unwrap();
        }
        reader.join().unwrap();
    }
}
