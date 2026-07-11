package io.stamethyst.backend.easytier

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores bearer credentials outside the externally readable diagnostic snapshot. */
internal object EasyTierCredentialStore {
    private const val FILE_NAME = "easytier_credentials"
    private const val SESSION_TOKEN_PREFIX = "session:"
    private const val OWNER_TOKEN_PREFIX = "owner:"

    fun save(
        context: Context,
        roomId: String,
        playerId: String,
        sessionToken: String,
        ownerToken: String,
    ) {
        val preferences = preferences(context)
        preferences.edit().apply {
            if (sessionToken.isNotBlank()) {
                putString(sessionKey(roomId, playerId), sessionToken)
            }
            if (ownerToken.isNotBlank()) {
                putString(ownerKey(roomId), ownerToken)
            }
        }.apply()
    }

    fun sessionToken(context: Context, roomId: String, playerId: String): String =
        preferences(context).getString(sessionKey(roomId, playerId), "").orEmpty()

    fun ownerToken(context: Context, roomId: String): String =
        preferences(context).getString(ownerKey(roomId), "").orEmpty()

    fun clearSession(context: Context, roomId: String, playerId: String) {
        preferences(context).edit().remove(sessionKey(roomId, playerId)).apply()
    }

    fun clearRoom(context: Context, roomId: String, playerId: String) {
        preferences(context).edit()
            .remove(sessionKey(roomId, playerId))
            .remove(ownerKey(roomId))
            .apply()
    }

    private fun sessionKey(roomId: String, playerId: String): String =
        "$SESSION_TOKEN_PREFIX${roomId.trim()}:${playerId.trim()}"

    private fun ownerKey(roomId: String): String = "$OWNER_TOKEN_PREFIX${roomId.trim()}"

    private fun preferences(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext,
        FILE_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
