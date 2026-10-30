package me.eternal.purrfect.bridge.scripting;

import me.eternal.purrfect.bridge.scripting.IPCListener;
import me.eternal.purrfect.bridge.scripting.AutoReloadListener;

interface IScripting {
    List<String> getEnabledScripts();

    @nullable ParcelFileDescriptor getScriptContent(String path);

    oneway void registerIPCListener(String channel, String eventName, IPCListener listener);

    int sendIPCMessage(String channel, String eventName, in String[] args);

    @nullable String configTransaction(String module, String action, @nullable String key, @nullable String value, boolean save);

    oneway void registerAutoReloadListener(in AutoReloadListener listener);
}