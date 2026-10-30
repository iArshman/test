package me.eternal.purrfect.bridge;

oneway interface ConfigStateListener {
    void onConfigChanged();
    void onRestartRequired();
    void onCleanCacheRequired();
}