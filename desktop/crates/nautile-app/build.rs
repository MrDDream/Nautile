//! On Windows: the icon, the name and the version of nautile.exe.

fn main() {
    println!("cargo:rerun-if-changed=../../packaging/nautile.ico");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        let mut res = winresource::WindowsResource::new();
        res.set_icon("../../packaging/nautile.ico")
            .set("ProductName", "Nautile")
            .set("FileDescription", "Nautile")
            .set("LegalCopyright", "GPL-3.0-or-later");
        if let Err(e) = res.compile() {
            println!("cargo:warning=no Windows resources: {e}");
        }
    }
}
