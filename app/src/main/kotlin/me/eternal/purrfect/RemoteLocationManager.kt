package me.eternal.purrfect

import me.eternal.purrfect.bridge.location.FriendLocation
import me.eternal.purrfect.bridge.location.LocationManager

class RemoteLocationManager(
    private val remoteSideContext: RemoteSideContext
): LocationManager.Stub() {
    var friendsLocation = listOf<FriendLocation>()
        private set

    override fun provideFriendsLocation(friendsLocation: List<FriendLocation>) {
        this.friendsLocation = friendsLocation.sortedBy { -it.lastUpdated }
    }
}