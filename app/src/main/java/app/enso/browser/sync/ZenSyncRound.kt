package app.enso.browser.sync

import app.enso.browser.spaces.SpacesState
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** The phone's spaces as a sync sees them: [app.enso.browser.spaces.SpacesStore] and the tab manager in the app. */
internal interface SyncedModel {
    val state: SpacesState

    /** Counts the user's edits, so a sync can tell whether the phone changed while it ran. */
    val edits: Int

    /** Replaces the model in one go, closing the pages of tabs that no longer exist. */
    fun apply(transform: (SpacesState) -> SpacesState)

    /** Saves the model to its file now. */
    fun flush()
}

/** The settings one sync runs with. */
internal class SyncOptions(val syncOpenTabs: Boolean, val syncTabNavigations: Boolean, val dryRun: Boolean)

/** How one sync ended. With [again], the phone still has changes to send or to work out, so another should follow. */
internal class SyncOutcome(val result: ZenSyncResult, val again: Boolean)

/**
 * One sync from start to finish: the phone's changes are worked out and exchanged with the server, and Zen's state
 * is applied to the phone's model. Call it from the thread that owns the model; the network and the files run on [io].
 *
 * The mirror (what sync knows of Zen) is only ever saved together with the model taking it in. The phone's changes
 * are the difference between its model and the mirror, so a mirror that knew more of Zen than the model did made the
 * next sync upload that as the phone's changes: it deleted what Zen had added and re-created what Zen had deleted.
 */
internal class ZenSyncRound(
    private val engine: ZenSpacesEngine,
    private val model: SyncedModel,
    private val io: CoroutineContext,
) {
    suspend fun run(auth: SyncAuth, device: SyncDevice?, options: SyncOptions): SyncOutcome {
        val editsBefore = model.edits
        val local = model.state
        val result = withContext(io) {
            engine.sync(auth, options.dryRun, device, local.zenApplied) { base ->
                zenChanges(local, base.records, options.syncOpenTabs, options.syncTabNavigations, base.gone)
            }
        }
        if (result !is ZenSyncResult.Synced) return SyncOutcome(result, again = false)

        // Edited while syncing: applying Zen's state now would undo the edit, so it's synced first.
        val editedMeanwhile = model.edits != editsBefore
        // A dry run never sends, so applying Zen's state would wipe the phone's edits.
        val keepsUnsent = options.dryRun && result.unsent > 0
        if (editedMeanwhile || keepsUnsent) {
            // The model stays as it is, so the mirror stays the one it reflects, plus what the phone just uploaded.
            // Those uploads change the mirror's stamp, and the model's with it: a backup from before them must not
            // pass for the model this mirror belongs to.
            val unapplied = result.unapplied
            if (result.sent > 0) model.apply { it.copy(zenApplied = unapplied.stamp) }
            withContext(io) {
                if (result.sent > 0) model.flush()
                engine.save(unapplied)
            }
            return SyncOutcome(result, again = editedMeanwhile)
        }

        // Too many deletions at once sent nothing. Zen's state goes over the model as after any sync, which puts back
        // what was removed, and the mirror keeps a note of it for the sync sheet.
        val refused = result.refusedDeletions.takeIf { it > 0 }
            ?.let { RefusedDeletions(it, at = result.mirror.lastSynced ?: System.currentTimeMillis()) }
        val mirror = if (refused != null) result.mirror.copy(refused = refused) else result.mirror
        model.apply { it.withZenSpaces(mirror).copy(zenApplied = mirror.stamp) }
        withContext(io) {
            // The model's file first. Killed between the two, the next sync finds that they disagree, and downloads
            // everything instead of uploading a difference.
            model.flush()
            engine.save(mirror)
        }
        // Whatever the phone still has to say goes now rather than at the next sync minutes later: changes Zen's held
        // back (worked out on top of Zen's versions now), what's left of a sync that was refused, the deletion of a
        // folder that just went up emptied, and what's the phone's own that Zen's state was applied around.
        val pending = withContext(io) {
            zenChanges(model.state, mirror.records, options.syncOpenTabs, options.syncTabNavigations, mirror.gone).isNotEmpty()
        }
        // Not after a sync that couldn't send what it had (the server refused a record, say), and not for ever: both
        // wait for the regular sync instead of asking again every few seconds.
        val couldSend = result.unsent == 0 || refused != null
        val again = pending && couldSend && followUps < MAX_FOLLOW_UPS
        followUps = if (again) followUps + 1 else 0
        return SyncOutcome(result.copy(mirror = mirror), again)
    }

    /** How many syncs in a row have asked for another. */
    private var followUps = 0

    private companion object {
        const val MAX_FOLLOW_UPS = 5
    }
}
