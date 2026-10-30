fn main() {
    println!("cargo:rerun-if-env-changed=OMVLL_PLUGIN_PATH");

    let mut build = cc::Build::new();
    build
        .file("secure/secure_strings.c")
        .flag_if_supported("-fvisibility=hidden");

    if let Ok(plugin) = std::env::var("OMVLL_PLUGIN_PATH") {
        build.flag(&format!("-fpass-plugin={plugin}"));
    }

    build.compile("secure_strings");

    println!("cargo:rustc-link-lib=static=c++");
}
