//! « Moniteur »: CPU, memory, disk, load and uptime of a Linux host, read over SSH every few seconds
//! (Android's `MonitorScreen`). Each reading is one `exec` on the same connection.

use std::time::Duration;

use russh::ChannelMsg;

use crate::session::{Event, Input, RUNTIME, Session, channels};
use crate::ssh::{self, Params};

/// What is read on the server: the first line of /proc/stat, the memory, the root disk, the load, the uptime,
/// the number of processors.
pub const COMMAND: &str = "export LC_ALL=C; head -1 /proc/stat; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; \
df -kP / | tail -1; cat /proc/loadavg; cat /proc/uptime; nproc 2>/dev/null || getconf _NPROCESSORS_ONLN";

/// Seconds between two readings.
pub const EVERY: u64 = 3;

/// The processes, busiest first, after a marker line: added to [COMMAND] while the list is shown.
pub const PROCESSES: &str = "echo '--processes--'; ps -eo pid=,user=,pcpu=,pmem=,rss=,args= --sort=-pcpu 2>/dev/null | head -n 400";
const MARKER: &str = "--processes--";

/// What the monitor's [Input::Data] asks: the process list on or off.
pub const PROCESSES_ON: &[u8] = b"processes:on";
pub const PROCESSES_OFF: &[u8] = b"processes:off";

/// One line of `ps`.
#[derive(Debug, Clone, PartialEq)]
pub struct Process {
    pub pid: u32,
    pub user: String,
    pub cpu: f32,
    pub mem: f32,
    pub rss_kb: u64,
    pub command: String,
}

/// The processes of a reading, `None` when it has no list (not asked, or `ps` without `-o`).
pub fn parse_processes(text: &str) -> Option<Vec<Process>> {
    let (_, list) = text.split_once(MARKER)?;
    let v: Vec<Process> = list
        .lines()
        .filter_map(|l| {
            let mut w = l.split_whitespace();
            let pid = w.next()?.parse().ok()?;
            let user = w.next()?.to_owned();
            let cpu = w.next()?.replace(',', ".").parse().ok()?;
            let mem = w.next()?.replace(',', ".").parse().ok()?;
            let rss_kb = w.next()?.parse().ok()?;
            let command = w.collect::<Vec<_>>().join(" ");
            (!command.is_empty()).then_some(Process {
                pid,
                user,
                cpu,
                mem,
                rss_kb,
                command,
            })
        })
        .collect();
    Some(v)
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Sample {
    /// Jiffies since boot, all and idle (with iowait): the CPU use is their difference between two samples.
    pub cpu_total: u64,
    pub cpu_idle: u64,
    pub mem_total_kb: u64,
    pub mem_available_kb: u64,
    pub disk_size_kb: u64,
    pub disk_used_kb: u64,
    pub load: [f32; 3],
    pub uptime_s: u64,
    pub cpus: u32,
}

impl Sample {
    pub fn mem_percent(&self) -> f32 {
        if self.mem_total_kb == 0 {
            return 0.0;
        }
        (self.mem_total_kb - self.mem_available_kb.min(self.mem_total_kb)) as f32 * 100.0 / self.mem_total_kb as f32
    }

    pub fn disk_percent(&self) -> f32 {
        if self.disk_size_kb == 0 {
            return 0.0;
        }
        self.disk_used_kb as f32 * 100.0 / self.disk_size_kb as f32
    }

    /// The CPU use between [prev] and this sample, in percent.
    pub fn cpu_percent(&self, prev: &Sample) -> f32 {
        let total = self.cpu_total.saturating_sub(prev.cpu_total);
        let idle = self.cpu_idle.saturating_sub(prev.cpu_idle);
        if total == 0 {
            return 0.0;
        }
        (total - idle.min(total)) as f32 * 100.0 / total as f32
    }
}

/// Reads the output of [COMMAND]; `None` when it is not a Linux host.
pub fn parse(text: &str) -> Option<Sample> {
    let text = text.split_once(MARKER).map_or(text, |(head, _)| head);
    let mut s = Sample::default();
    let mut lines = text.lines().map(str::trim).filter(|l| !l.is_empty());
    let stat: Vec<u64> = lines
        .next()?
        .strip_prefix("cpu")?
        .split_whitespace()
        .filter_map(|v| v.parse().ok())
        .collect();
    if stat.len() < 4 {
        return None;
    }
    s.cpu_total = stat.iter().take(8).sum();
    s.cpu_idle = stat[3] + stat.get(4).copied().unwrap_or(0);
    for l in lines {
        let words: Vec<&str> = l.split_whitespace().collect();
        match words.as_slice() {
            ["MemTotal:", v, ..] => s.mem_total_kb = v.parse().unwrap_or(0),
            ["MemAvailable:", v, ..] => s.mem_available_kb = v.parse().unwrap_or(0),
            // df -kP: Filesystem 1024-blocks Used Available Capacity Mounted
            [_, size, used, _, pct, "/"] if pct.ends_with('%') => {
                s.disk_size_kb = size.parse().unwrap_or(0);
                s.disk_used_kb = used.parse().unwrap_or(0);
            }
            // /proc/loadavg: 0.10 0.20 0.30 1/234 5678
            [a, b, c, procs, _] if procs.contains('/') => {
                s.load = [a.parse().unwrap_or(0.0), b.parse().unwrap_or(0.0), c.parse().unwrap_or(0.0)];
            }
            // /proc/uptime: 12345.67 23456.78
            [up, _] if up.contains('.') => s.uptime_s = up.parse::<f64>().unwrap_or(0.0) as u64,
            [n] => s.cpus = n.parse().unwrap_or(0),
            _ => {}
        }
    }
    Some(s)
}

async fn read_once(conn: &ssh::Connection, processes: bool) -> Result<String, String> {
    let mut ch = conn.handle.channel_open_session().await.map_err(|e| e.to_string())?;
    let command = if processes {
        format!("{COMMAND}; {PROCESSES}")
    } else {
        COMMAND.to_owned()
    };
    ch.exec(true, command).await.map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    loop {
        match tokio::time::timeout_at(deadline, ch.wait()).await {
            Ok(Some(ChannelMsg::Data { data })) => {
                if !ssh::push_bounded(&mut out, &data) {
                    break;
                }
            }
            Ok(Some(ChannelMsg::Eof | ChannelMsg::Close)) | Ok(None) | Err(_) => break,
            Ok(Some(_)) => {}
        }
    }
    Ok(String::from_utf8_lossy(&out).into_owned())
}

/// Connects, then sends one [Event::Output] (the raw text of [COMMAND]) every [EVERY] seconds until closed.
pub fn spawn(p: Params) -> Session {
    let (session, events, mut input) = channels();
    RUNTIME.spawn(async move {
        let reason = match ssh::connect(&p, &events).await {
            Err(e) => Some(e),
            Ok(conn) => {
                events.send(Event::Connected);
                let mut processes = false;
                let r = loop {
                    match read_once(&conn, processes).await {
                        Ok(text) => events.send(Event::Output(text.into_bytes())),
                        Err(e) if conn.handle.is_closed() => break Some(e),
                        Err(_) => {}
                    }
                    // A change of the process list reads again at once.
                    tokio::select! {
                        i = input.recv() => match i {
                            Some(Input::Close) | None => break None,
                            Some(Input::Data(d)) => processes = d == PROCESSES_ON,
                            Some(_) => {}
                        },
                        _ = tokio::time::sleep(Duration::from_secs(EVERY)) => {}
                    }
                };
                conn.close().await;
                r
            }
        };
        events.send(Event::Closed(reason));
    });
    session
}

#[cfg(test)]
mod tests {
    use super::*;

    const OUT: &str = "cpu  4705 356 584 3699176 23060 0 277 0 0 0
MemTotal:        8040260 kB
MemAvailable:    5023412 kB
/dev/sda1        61255492 22849808  35264996      40% /
0.42 0.36 0.30 2/412 31337
93241.35 361072.11
4
";

    #[test]
    fn a_linux_reading_is_understood() {
        let s = parse(OUT).unwrap();
        assert_eq!((s.cpus, s.uptime_s), (4, 93241));
        assert_eq!(s.load, [0.42, 0.36, 0.30]);
        assert!((s.mem_percent() - 37.52).abs() < 0.1);
        assert!((s.disk_percent() - 37.3).abs() < 0.1);
        let mut later = s.clone();
        later.cpu_total += 400;
        later.cpu_idle += 300;
        assert!((later.cpu_percent(&s) - 25.0).abs() < 0.01);
        assert!(parse("not linux").is_none());
    }

    #[test]
    fn the_process_list_follows_the_reading() {
        let text = format!(
            "{OUT}--processes--\n  812 postgres  12.5  3.1 251004 postgres: checkpointer\n    1 root  0,0  0.1 11520 /sbin/init splash\n"
        );
        // The reading itself is unchanged.
        assert_eq!(parse(&text), parse(OUT));
        let p = parse_processes(&text).unwrap();
        assert_eq!(p.len(), 2);
        assert_eq!(
            (p[0].pid, p[0].user.as_str(), p[0].cpu, p[0].rss_kb),
            (812, "postgres", 12.5, 251004)
        );
        assert_eq!(p[0].command, "postgres: checkpointer");
        assert_eq!(p[1].command, "/sbin/init splash");
        assert!(parse_processes(OUT).is_none());
    }
}
