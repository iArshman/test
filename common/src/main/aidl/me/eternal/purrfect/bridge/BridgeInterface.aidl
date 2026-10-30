package me.eternal.purrfect.bridge;

import java.util.List;
import java.util.Map;
import android.content.Intent;
import android.os.ParcelFileDescriptor;

import me.eternal.purrfect.bridge.DownloadCallback;
import me.eternal.purrfect.bridge.SyncCallback;
import me.eternal.purrfect.bridge.scripting.IScripting;
import me.eternal.purrfect.bridge.e2ee.E2eeInterface;
import me.eternal.purrfect.bridge.logger.LoggerInterface;
import me.eternal.purrfect.bridge.logger.TrackerInterface;
import me.eternal.purrfect.bridge.ConfigStateListener;
import me.eternal.purrfect.bridge.snapclient.MessagingBridge;
import me.eternal.purrfect.bridge.AccountStorage;
import me.eternal.purrfect.bridge.storage.FileHandleManager;
import me.eternal.purrfect.bridge.location.LocationManager;
import me.eternal.purrfect.bridge.call.CallDownloadSession;
import me.eternal.purrfect.bridge.task.TaskInterface;

interface BridgeInterface {
    /**
    * Get the Purrfect APK path (used in LSPatch updater and for auto bridge restart)
    */
    String getApplicationApkPath();

    /**
    * broadcast a log message
    */
    oneway void broadcastLog(String tag, String level, String message);

    /**
     * Enqueue a download
     */
    oneway void enqueueDownload(in Intent intent, DownloadCallback callback);

    /**
     * File conversation
     */
    @nullable ParcelFileDescriptor convertMedia(in ParcelFileDescriptor input, String inputExtension, String outputExtension, @nullable String audioCodec, @nullable String videoCodec);

    /**
    * Get rules for a given user or conversation
    * @return list of rules (MessagingRuleType)
    */
    List<String> getRules(String uuid);

    /**
    * Get all ids for a specific rule
    * @param type rule type (MessagingRuleType)
    * @return list of ids
    */
    List<String> getRuleIds(String type);

    /**
    * Update rule for a giver user or conversation
    *
    * @param type rule type (MessagingRuleType)
    */
    oneway void setRule(String uuid, String type, boolean state);

    /**
    * Sync groups and friends
    */
    oneway void sync(SyncCallback callback);

    /**
    * Trigger sync for an id
    */
    oneway void triggerSync(String scope, String id);

    /**
    * Pass all groups and friends to be able to add them to the database
    * @param groups list of groups (MessagingGroupInfo as parcelable)
    * @param friends list of friends (MessagingFriendInfo as parcelable)
    */
    oneway void passGroupsAndFriends(in List<String> groups, in List<String> friends, int chunkIndex, int totalChunks);

    @nullable String getScopeNotes(String id);

    oneway void setScopeNotes(String id, String content);

    Map<String, String> getAllScopeNotes();

    oneway void setAllScopeNotes(in Map<String, String> notes);

    IScripting getScriptingInterface();

    E2eeInterface getE2eeInterface();

    LoggerInterface getLogger();

    TrackerInterface getTracker();

    AccountStorage getAccountStorage();

    FileHandleManager getFileHandleManager();

    LocationManager getLocationManager();

    TaskInterface getTaskInterface();

    oneway void registerMessagingBridge(MessagingBridge bridge);

    oneway void openOverlay(String type);

    oneway void closeOverlay();

    oneway void registerConfigStateListener(in ConfigStateListener listener);

    @nullable String getDebugProp(String key, @nullable String defaultValue);

    boolean openMappingsGenerator(String reason, String completionMode);

    String getRedditFeaturesJson();

    String getWhatsAppFeaturesJson();

    String getInstagramFeaturesJson();

    CallDownloadSession startCallDownload(long startTimestamp, String author);
}
