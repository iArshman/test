package me.eternal.purrfect.bridge.storage;

import me.eternal.purrfect.bridge.storage.FileHandle;

interface FileHandleManager {
    @nullable FileHandle getFileHandle(String scope, String name);
}