package com.example.screenmirror.domain.models

enum class DeviceProtocol(val displayName: String) {
    CHROMECAST("Chromecast"),
    MIRACAST("Miracast"),
    AIRPLAY("AirPlay"),
    DLNA("Smart TV (DLNA)"),
    UNKNOWN("Unknown")
}
