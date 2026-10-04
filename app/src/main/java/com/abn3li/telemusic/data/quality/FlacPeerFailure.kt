package com.abn3li.telemusic.data.quality

internal data class FlacPeerFailure(val filename: String, val reason: String)

/** Only UploadDenied carries a reason. Never reinterpret UploadFailed as a refusal. */
internal fun readFlacPeerFailure(code: Int, body: WireReader, secrets: List<String> = emptyList()): FlacPeerFailure {
    require(code == 50 || code == 46)
    val filename = body.string()
    if (code == 46) return FlacPeerFailure(filename, "Peer interrupted the upload")
    var reason = body.string()
    for (secret in secrets.filter { it.isNotEmpty() }) reason = reason.replace(secret, "[redacted]", ignoreCase = true)
    reason = reason.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ").replace(Regex("\\s+"), " ").trim().take(160)
    return FlacPeerFailure(filename, "Peer rejected: ${reason.ifBlank { "no reason supplied" }}")
}
