package me.eternal.purrfect.bridge.scripting;


interface IPCListener {
    void onMessage(in String[] args);
}