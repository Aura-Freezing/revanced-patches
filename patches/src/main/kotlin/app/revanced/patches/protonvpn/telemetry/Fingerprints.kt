package app.revanced.patches.protonvpn.telemetry

import app.revanced.patcher.fingerprint

// Find SentryIntegration by unique strings
internal val sentryIntegrationFingerprint = fingerprint {
    strings("sentry_installation_id", "sentry_is_enabled")
}

// Find LocalUserSettings by "Telemetry: " log string (used in toLogList)
internal val localUserSettingsFingerprint = fingerprint {
    strings("Telemetry: ")
}
