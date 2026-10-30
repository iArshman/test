package me.eternal.purrfect.bridge.task;

import me.eternal.purrfect.bridge.task.TaskListener;

interface TaskInterface {
    String createTask(String type, String title, String author, String hash);
    void updateTaskProgress(String hash, String label, int progress);
    void cancelTask(String hash);
    void failTask(String hash, String reason);
    void successTask(String hash);
    void registerTaskListener(String hash, TaskListener listener);
    void unregisterTaskListener(String hash, TaskListener listener);
}

