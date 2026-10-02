package com.german.dronnk.actions

enum class ActionType {
    OPEN_APP,
    CALL_CONTACT,
    WHATSAPP_CHAT,
    WHATSAPP_MESSAGE,
    PLAY_YOUTUBE,
    SPOTIFY_SEARCH,
    TORCH_ON,
    TORCH_OFF,
    VOLUME_UP,
    VOLUME_DOWN,
    VOLUME_MUTE,
    MEDIA_PAUSE,
    MEDIA_PLAY,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    BATTERY,
    NONE
}

data class DronnkAction(
    val type: ActionType,
    val value: String = "",
    val target: String = "",
    val message: String = "",
    val reply: String = ""
)
