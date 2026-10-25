package dev.hkgill.gillphotos.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a photo's GPS position into the nearest named place, with no network: a list of places (GeoNames' towns and
 * suburbs of 1,000+ people) kept in one-degree squares, so a lookup only checks the squares around the point.
 */
class Places(lines: Sequence<String>) {
    class Place(val name: String, val lat: Double, val lon: Double, val country: String)

    private val cells = HashMap<Long, MutableList<Place>>()
    var size = 0
        private set

    init {
        for (line in lines) {
            val c = line.split('\t')
            if (c.size < 4) continue
            val p = Place(c[0], c[1].toDoubleOrNull() ?: continue, c[2].toDoubleOrNull() ?: continue, c[3])
            cells.getOrPut(key(cell(p.lat), cell(p.lon))) { ArrayList() } += p
            size++
        }
    }

    /** The closest place within [maxKm], or null out at sea or in the wilds. */
    fun nearest(lat: Double, lon: Double, maxKm: Double = 30.0): Place? {
        val la = cell(lat)
        val lo = cell(lon)
        var best: Place? = null
        var bestKm = maxKm
        for (dLat in -1..1) for (dLon in -1..1) {
            val wrapped = Math.floorMod(lo + dLon + 180, 360) - 180
            for (p in cells[key(la + dLat, wrapped)] ?: continue) {
                val km = km(lat, lon, p.lat, p.lon)
                if (km < bestKm) { bestKm = km; best = p }
            }
        }
        return best
    }

    private fun cell(deg: Double) = floor(deg).toInt()
    private fun key(lat: Int, lon: Int) = lat.toLong() * 1000 + lon

    companion object {
        /** Great-circle distance. */
        fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = Math.PI / 180
            val a = sin((lat2 - lat1) * r / 2).let { it * it } +
                cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).let { it * it }
            return 2 * 6371 * asin(sqrt(a))
        }
    }
}
