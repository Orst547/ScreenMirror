package com.example.screenmirror.domain.models

data class MirrorRoute(
    val id: String,
    val name: String,
    val description: String?,
    val protocol: DeviceProtocol,
    val ipAddress: String? = null,
    val port: Int? = null          // populated by NSD resolve; null for Miracast peers
)
