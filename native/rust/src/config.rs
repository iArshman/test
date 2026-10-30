use std::{error::Error, sync::Mutex};
use jni::{objects::JObject, JNIEnv};
use crate::{secstrings, util::get_jni_string};

static NATIVE_CONFIG: Mutex<Option<NativeConfig>> = Mutex::new(None);

pub fn native_config() -> NativeConfig {
    NATIVE_CONFIG.lock().unwrap().as_ref().expect("NativeConfig not loaded").clone()
}

/// Native configuration structure mirrored from 'NativeConfig.kt'.
/// 
/// CRITICAL: Fields must maintain 1:1 parity with the Kotlin implementation.
/// Mismatches in field names, types, or order will result in a JNI SIGABRT.
#[derive(Debug, Clone)]
pub(crate) struct NativeConfig {
    pub disable_bitmoji: bool,
    pub disable_metrics: bool,
    pub valdi_hooks: bool,
    pub custom_emoji_font_path: Option<String>,
    pub debug_font_redirect: bool,
}

impl NativeConfig {
    fn new(env: &mut JNIEnv, obj: JObject) -> Result<Self, Box<dyn Error>> {
        macro_rules! get_boolean {
            ($field:expr) => {
                env.get_field(&obj, $field, "Z")?.z()?
            };
        }

        macro_rules! get_string {
            ($field:expr) => {
                match env.get_field(&obj, $field, "Ljava/lang/String;")?.l()? {
                    jstring => if !jstring.is_null() {
                        Some(get_jni_string(env, jstring.into())?)
                    } else {
                        None
                    },
                }
            };
        }

        Ok(Self {
            disable_bitmoji: get_boolean!("disableBitmoji"),
            disable_metrics: get_boolean!("disableMetrics"),
            valdi_hooks: get_boolean!("valdiHooks"),
            custom_emoji_font_path: get_string!("customEmojiFontPath"),
            debug_font_redirect: get_boolean!("debugFontRedirect"),
        })
    }
}

pub struct BlockerConfig {
    pub allowed_eps_active: Vec<String>,
    pub detection_keywords: Vec<String>,
    pub risk_block_list: Vec<String>,
}

pub fn get_blocker_config() -> BlockerConfig {
    let config_str = include_str!("../../../config/config.json");
    let raw: serde_json::Value = serde_json::from_str(config_str).unwrap();

    let allowed = raw["allowed_eps_active"].as_array().cloned().unwrap_or_default();

    BlockerConfig {
        allowed_eps_active: allowed
            .into_iter()
            .filter_map(|value| value.as_str().map(|s| s.to_lowercase()))
            .collect(),
        detection_keywords: secstrings::get_detection_keywords()
            .into_iter()
            .map(|s| s.to_lowercase())
            .collect(),
        risk_block_list: secstrings::get_risk_block_list()
            .into_iter()
            .map(|s| s.to_lowercase())
            .collect(),
    }
}

pub extern "system" fn load_config(mut env: JNIEnv, _class: JObject, obj: JObject)  {
    NATIVE_CONFIG.lock().unwrap().replace(
        NativeConfig::new(&mut env, obj).expect("Failed to load NativeConfig")
    );
    
    info!("Config loaded {:?}", native_config());
}
