//! Identifies a server's operating system (distribution) from one SSH command, for its logo
//! (Android's `OsDetector`).

/// Run once on the server; its whole output goes to [parse].
pub const COMMAND: &str = "cat /etc/os-release 2>/dev/null; echo @@; uname -s 2>/dev/null; echo @@; \
[ -e /etc/unraid-version ] && echo unraid; command -v pveversion >/dev/null 2>&1 && echo proxmox; \
[ -e /etc.defaults/VERSION ] && echo synology; [ -e /etc/openwrt_release ] && echo openwrt; \
[ -e /etc/truenas-version ] || command -v midclt >/dev/null 2>&1 && echo truenas; \
[ -r /proc/device-tree/model ] && tr -d '\\000' < /proc/device-tree/model; true";

fn alias(id: &str) -> &str {
    match id {
        "rhel" | "ol" => "redhat",
        "linuxmint" => "mint",
        "pop" => "popos",
        "sles" | "sled" | "opensuse-leap" | "opensuse-tumbleweed" => "opensuse",
        "amzn" => "linux",
        "archarm" => "arch",
        other => other,
    }
}

/// The id of the logo table (`kit::os_brand`); unknown Linux distributions give their own id, nothing at all
/// gives `"unknown"`.
pub fn parse(output: &str) -> String {
    let mut parts = output.split("@@").map(str::trim);
    let os_release = parts.next().unwrap_or_default();
    let kernel = parts.next().unwrap_or_default().to_lowercase();
    let extras = parts.next().unwrap_or_default().to_lowercase();
    for special in ["unraid", "proxmox", "synology", "openwrt", "truenas"] {
        if extras.lines().any(|l| l.trim() == special) {
            return special.into();
        }
    }
    let id = os_release
        .lines()
        .filter_map(|l| l.split_once('='))
        .find(|(k, _)| k.trim() == "ID")
        .map(|(_, v)| v.trim().trim_matches(|c| c == '"' || c == '\'').to_lowercase())
        .filter(|v| !v.is_empty());
    if let Some(id) = id {
        if (id == "debian" || id == "raspbian") && extras.contains("raspberry pi") {
            return "raspbian".into();
        }
        return alias(&id).into();
    }
    if kernel.starts_with("darwin") {
        "macos"
    } else if kernel.starts_with("freebsd") {
        "freebsd"
    } else if kernel.starts_with("openbsd") {
        "openbsd"
    } else if kernel.starts_with("linux") {
        "linux"
    } else if ["mingw", "msys", "cygwin"].iter().any(|k| kernel.contains(k)) {
        "windows"
    } else {
        "unknown"
    }
    .into()
}

/// This computer's own system, for its local shells.
pub fn local() -> String {
    match std::env::consts::OS {
        "windows" => "windows".into(),
        "macos" => "macos".into(),
        "freebsd" => "freebsd".into(),
        "openbsd" => "openbsd".into(),
        _ => {
            let release = std::fs::read_to_string("/etc/os-release")
                .or_else(|_| std::fs::read_to_string("/usr/lib/os-release"))
                .unwrap_or_default();
            match parse(&format!("{release}\n@@\nLinux\n@@\n")).as_str() {
                "unknown" => "linux".into(),
                id => id.into(),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::parse;

    #[test]
    fn distributions_and_systems_are_recognised() {
        let ubuntu = "NAME=\"Ubuntu\"\nID=ubuntu\nID_LIKE=debian\n@@\nLinux\n@@\n";
        assert_eq!(parse(ubuntu), "ubuntu");
        assert_eq!(parse("ID=\"rhel\"\n@@\nLinux\n@@\n"), "redhat");
        assert_eq!(parse("ID=linuxmint\n@@\nLinux\n@@\n"), "mint");
        assert_eq!(parse("ID=debian\n@@\nLinux\n@@\nRaspberry Pi 4 Model B"), "raspbian");
        assert_eq!(parse("ID=debian\n@@\nLinux\n@@\nproxmox\n"), "proxmox");
        assert_eq!(parse("ID=slackware\n@@\nLinux\n@@\nunraid\n"), "unraid");
        assert_eq!(parse("ID=somethingnew\n@@\nLinux\n@@\n"), "somethingnew");
        assert_eq!(parse("@@\nDarwin\n@@\n"), "macos");
        assert_eq!(parse("@@\nFreeBSD\n@@\n"), "freebsd");
        assert_eq!(parse("@@\nMINGW64_NT-10.0\n@@\n"), "windows");
        assert_eq!(parse("@@\nLinux\n@@\n"), "linux");
        assert_eq!(parse("'cat' is not recognized"), "unknown");
    }
}
