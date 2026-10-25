package dev.hkgill.gillphotos.core

/**
 * Groups face vectors into people. Faces the user has named stay with that person. Every other face joins the
 * closest group if it's similar enough ([join]), or starts a group of its own; then groups that look like the same
 * person merge ([merge]), biggest first. Unnamed groups smaller than [minFaces] are dropped: strangers in the
 * background. The thresholds are cosines between unit-length SFace vectors (OpenCV calls 0.363 the same person) and
 * still need tuning on a real library.
 */
object People {
    /** A face to group; [person] is set when the user has named it. */
    class Face(val id: Long, val vector: FloatArray, val person: Long? = null)

    /** Faces that belong to a named person, and the unnamed groups (face ids), biggest first. */
    class Grouping(val named: Map<Long, Long>, val groups: List<List<Long>>)

    private class Group(val person: Long?, first: FloatArray) {
        val members = ArrayList<Long>()
        val sum = first.copyOf()
        val centre = normalize(first.copyOf())

        fun add(id: Long, v: FloatArray) {
            members += id
            for (i in sum.indices) sum[i] += v[i]
            recentre()
        }

        fun absorb(other: Group) {
            members += other.members
            for (i in sum.indices) sum[i] += other.sum[i]
            recentre()
        }

        private fun recentre() {
            sum.copyInto(centre)
            normalize(centre)
        }
    }

    fun group(faces: List<Face>, join: Float = 0.45f, merge: Float = 0.5f, minFaces: Int = 3): Grouping {
        val groups = ArrayList<Group>()
        val byPerson = HashMap<Long, Group>()
        for (f in faces) {
            val p = f.person ?: continue
            val g = byPerson[p]
            if (g == null) byPerson[p] = Group(p, f.vector).also { it.members += f.id; groups += it } else g.add(f.id, f.vector)
        }
        for (f in faces) {
            if (f.person != null) continue
            val best = groups.maxByOrNull { dot(it.centre, f.vector) }
            if (best != null && dot(best.centre, f.vector) >= join) best.add(f.id, f.vector)
            else groups += Group(null, f.vector).also { it.members += f.id }
        }

        // Merge, biggest first: each unnamed group goes into the closest group kept so far, if it's close enough.
        // Two named people never merge.
        val kept = ArrayList<Group>()
        for (g in groups.sortedWith(compareBy<Group>({ it.person == null }, { -it.members.size }))) {
            val target = if (g.person != null) null else kept.maxByOrNull { dot(it.centre, g.centre) }
            if (target != null && dot(target.centre, g.centre) >= merge) target.absorb(g) else kept += g
        }

        val named = HashMap<Long, Long>()
        for (g in kept) g.person?.let { p -> g.members.forEach { named[it] = p } }
        val unnamed = kept.filter { it.person == null && it.members.size >= minFaces }
            .sortedByDescending { it.members.size }
            .map { it.members.toList() }
        return Grouping(named, unnamed)
    }
}
