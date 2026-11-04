package dev.braid.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.braid.crdt.ReplicaId
import dev.braid.sync.Invite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "braid")

/**
 * Small key-value settings in DataStore (async, transactional, no main-thread I/O, unlike
 * SharedPreferences). The invite is stored sealed by [SecretBox].
 */
class AppPreferences(context: Context, private val box: SecretBox = SecretBox()) {
    private val store = context.applicationContext.dataStore

    val displayName: Flow<String> = store.data.map { it[DISPLAY_NAME].orEmpty() }

    suspend fun setDisplayName(name: String) = store.edit { it[DISPLAY_NAME] = name.trim() }

    suspend fun activeInvite(): Invite? {
        val sealed = store.data.first()[INVITE] ?: return null
        return box.open(sealed)?.let { Invite.parse(it).getOrNull() }
    }

    suspend fun setActiveInvite(invite: Invite?) = store.edit {
        if (invite == null) it.remove(INVITE) else it[INVITE] = box.seal(invite.toUri())
    }

    /**
     * The replica id for a workspace, created on first use. One id per (install, workspace):
     * reusing an id across reinstalls would restart its sequence numbers at 1 and collide with
     * ops peers already hold, so a fresh install always gets a fresh id.
     */
    suspend fun replicaFor(workspaceId: String): ReplicaId {
        val key = stringPreferencesKey("replica_$workspaceId")
        store.data.first()[key]?.let { return ReplicaId.parse(it) }
        val fresh = ReplicaId.random()
        store.edit { it[key] = fresh.toString() }
        return fresh
    }

    private companion object {
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val INVITE = stringPreferencesKey("invite_sealed")
    }
}
