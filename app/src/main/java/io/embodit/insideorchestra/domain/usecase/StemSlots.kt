package io.embodit.insideorchestra.domain.usecase

/**
 * The ten stems' mixer groups across piece switches (v0.1.6, user on the headset: "no sound after switching to a new
 * song"). Groups [G] are made on the first load and stay open for every later piece; a switch only drops the players.
 * v0.1.5 closed the groups on a switch and made new ones under the same names, and the new piece played into the
 * closed groups: positions advanced, nothing came out. [close] (scene detach / quit) is the only place groups close.
 * Pure: the SDK side passes its factories, the tests pass fakes.
 */
class StemSlots<K, G>(private val keys: List<K>, private val newGroup: (K) -> G, private val closeGroup: (G) -> Unit) {
    private val groups = LinkedHashMap<K, G>()

    /** How many times the groups were made: 1 after the first load, whatever the number of piece switches. */
    var generation = 0
        private set

    /** The groups for a load: made on the first call, the same open objects afterwards. */
    fun forLoad(): Map<K, G> {
        if (groups.isEmpty()) {
            for (k in keys) groups[k] = newGroup(k)
            generation++
        }
        return groups
    }

    operator fun get(k: K): G? = groups[k]

    /** Scene detach / quit. */
    fun close() {
        for (g in groups.values) closeGroup(g)
        groups.clear()
    }
}
