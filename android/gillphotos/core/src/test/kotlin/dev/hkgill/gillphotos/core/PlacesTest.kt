package dev.hkgill.gillphotos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlacesTest {
    private val places = Places(
        sequenceOf(
            "Bondi\t-33.893\t151.263\tAU",
            "Bondi Beach\t-33.891\t151.278\tAU",
            "Sydney\t-33.868\t151.207\tAU",
            "Manly\t-33.798\t151.284\tAU",
            "Suva\t-18.142\t178.441\tFJ",
            "Taveuni\t-16.85\t-179.97\tFJ",
            "broken line",
        ),
    )

    @Test fun readsGoodLines() = assertEquals(6, places.size)

    @Test fun nearestSuburb() {
        assertEquals("Bondi Beach", places.nearest(-33.8915, 151.2767)?.name) // on the sand
        assertEquals("Sydney", places.nearest(-33.87, 151.21)?.name)
    }

    @Test fun acrossASquareEdge() = assertEquals("Manly", places.nearest(-33.80, 151.29)?.name)

    @Test fun acrossTheDateLine() = assertEquals("Taveuni", places.nearest(-16.85, 179.99)?.name)

    @Test fun nothingNearby() = assertNull(places.nearest(-40.0, 160.0))

    @Test fun distance() = assertEquals(1.4, Places.km(-33.893, 151.263, -33.891, 151.278), 0.1)
}
