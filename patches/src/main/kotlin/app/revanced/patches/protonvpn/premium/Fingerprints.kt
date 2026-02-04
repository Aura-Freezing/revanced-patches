package app.revanced.patches.protonvpn.premium

import app.revanced.patcher.fingerprint

internal val vpnUserFingerprint = fingerprint {
    strings("Proton VPN Account", "Proton Mail Account")
}

internal val appConfigResponseFingerprint = fingerprint {
    strings("ChangeServerShortDelayInSeconds", "ChangeServerLongDelayInSeconds")
}
