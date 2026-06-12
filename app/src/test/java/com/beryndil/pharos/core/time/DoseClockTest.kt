package com.beryndil.pharos.core.time

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DoseClockTest {
    private val ny = ZoneId.of("America/New_York")

    @Test
    fun next8amIsTomorrowWhenAlready9am() {
        val now = ZonedDateTime.of(2026, 1, 15, 9, 0, 0, 0, ny).toInstant()
        val next = DoseClock.nextOccurrence(LocalTime.of(8, 0), ny, now)
        val expected = ZonedDateTime.of(2026, 1, 16, 8, 0, 0, 0, ny).toInstant()
        assertEquals(expected, next)
    }

    @Test
    fun springForwardKeeps8amWallClock() {
        val now = ZonedDateTime.of(2026, 3, 7, 20, 0, 0, 0, ny).toInstant()
        val next = DoseClock.nextOccurrence(LocalTime.of(8, 0), ny, now)
        val asLocal = ZonedDateTime.ofInstant(next, ny)
        assertEquals(8, asLocal.hour)
        assertEquals(8, asLocal.dayOfMonth)
        assertEquals(3, asLocal.monthValue)
    }
}
