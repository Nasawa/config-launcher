package dev.nasawa.configlauncher.widgetdata

import dev.nasawa.configlauncher.store.ConfigSource
import dev.nasawa.configlauncher.store.Stamp

/**
 * #139 (S3-D): reads each allowed card's data file and keeps the last good values (ux.md §17.9).
 *
 * - Only cards the caller lets through (consent `allowed`, `data.file` declared) are read. A card that isn't is
 *   forgotten here, so no data stays in memory for it.
 * - Polled with launcher.json (every 2 s in the foreground). A file is re-read when its stamp changes, every 5th poll
 *   anyway (coarse mtimes), or on [reload].
 * - Missing or unreadable: the last values are kept and the card is marked [Snapshot.problem] (`warn_data_missing`).
 *   On the first ever read there are no values, so bindings show `—`.
 * Not thread-safe: the engine calls it under its mutex.
 */
class WidgetDataReader(private val clock: () -> Long = System::currentTimeMillis) {
    /** What a card shows: the last good data (null before the first good read) and whether the last read failed. */
    data class Snapshot(val data: WidgetData.Data?, val problem: Boolean, val readAt: Long)

    /** A card to read: its id and its path in launcher.json (for the warning). */
    data class Target(val id: String, val path: String)

    enum class Reload { Updated, Same, Ignored }

    private val snapshots = LinkedHashMap<String, Snapshot>()
    private val stamps = HashMap<String, Stamp?>()
    private var polls = 0

    fun snapshots(): Map<String, Snapshot> = LinkedHashMap(snapshots)

    /** Read [targets] as needed and drop everyone else. Returns true when any snapshot changed. */
    fun poll(src: ConfigSource, targets: List<Target>, force: Boolean = false): Boolean {
        val before = snapshots()
        val ids = targets.map { it.id }.toSet()
        snapshots.keys.retainAll(ids); stamps.keys.retainAll(ids)
        val anyway = force || (++polls % 5 == 0)
        for (t in targets) read(src, t.id, anyway)
        return snapshots != before
    }

    /**
     * `ui.reload`: re-read [id] now. At most once every 2 s per widget (§17.9); extra taps do nothing ([Reload.Ignored]).
     * [Reload.Updated] when the values changed, so TalkBack can say `Updated` or `No new data`.
     */
    fun reload(src: ConfigSource, target: Target): Reload {
        val prev = snapshots[target.id]
        if (prev != null && clock() - prev.readAt < 2_000) return Reload.Ignored
        read(src, target.id, true)
        return if (snapshots[target.id]?.data?.hash != prev?.data?.hash) Reload.Updated else Reload.Same
    }

    private fun read(src: ConfigSource, id: String, anyway: Boolean) {
        val name = WidgetData.fileName(id) ?: return
        val prev = snapshots[id]
        val stamp = try { src.sidecarStamp(WidgetData.DIR, name) } catch (_: Exception) { null }
        if (!anyway && prev != null && stamp != null && stamp == stamps[id]) return
        val parsed = try {
            src.readSidecar(WidgetData.DIR, name, WidgetData.MAX_BYTES)?.let(WidgetData::parse)
        } catch (_: Exception) { null }
        stamps[id] = stamp
        snapshots[id] = when (parsed) {
            is WidgetData.Parsed.Ok -> Snapshot(parsed.data, false, clock())
            else -> Snapshot(prev?.data, true, clock())
        }
    }

    /** `warn_data_missing` for every card whose last read failed. */
    fun warnings(targets: List<Target>): List<String> =
        targets.filter { snapshots[it.id]?.problem == true }.map { WidgetData.missingWarning(it.path, it.id) }
}
