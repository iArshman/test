package me.eternal.purrfect.bridge.task;

interface TaskListener {
    oneway void onProgress(String label, int progress);
    oneway void onStateChange(String status);
    oneway void onSuccess();
    oneway void onCancel();
}

