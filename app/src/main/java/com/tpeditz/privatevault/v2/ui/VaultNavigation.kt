package com.tpeditz.privatevault.v2.ui

enum class PrimaryTab(val label: String) {
    HOME("Home"),
    NOTES("Notes"),
    PASSWORDS("Passwords"),
    MEDIA("Media"),
    SETTINGS("Settings")
}

sealed interface SecondaryDestination {
    data object None : SecondaryDestination
    data object Tools : SecondaryDestination
    data object Authenticator : SecondaryDestination
    data object PrivacyDashboard : SecondaryDestination
    data class NoteEditor(val noteId: String? = null) : SecondaryDestination
    data class PasswordEditor(val credentialId: String? = null) : SecondaryDestination
    data class MediaViewer(val mediaId: String) : SecondaryDestination
    data class DocumentViewer(val documentId: String) : SecondaryDestination
    data class LinkEditor(val linkId: String? = null) : SecondaryDestination
    data object BackupAndRestore : SecondaryDestination
    data object CloudBackup : SecondaryDestination
    data object SecuritySettings : SecondaryDestination
    data object OtaUpdates : SecondaryDestination
    data object Feedback : SecondaryDestination
    data object About : SecondaryDestination
}
