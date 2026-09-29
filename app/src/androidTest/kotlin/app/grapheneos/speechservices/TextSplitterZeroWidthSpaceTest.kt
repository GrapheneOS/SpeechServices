package app.grapheneos.speechservices

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.grapheneos.speechservices.tts.TextSplitter
import app.grapheneos.speechservices.tts.normalizeZeroWidthSpaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextSplitterZeroWidthSpaceTest {
    @Test
    fun embeddedZeroWidthSpaceKeepsWordBoundary() {
        assertEquals("We are now here.", normalizeZeroWidthSpaces("We are now\u200Bhere."))
    }

    @Test
    fun otherInvisibleCodePointsAreKept() {
        assertEquals("2⁢3", normalizeZeroWidthSpaces("2⁢3"))
        assertEquals("a­b‍c", normalizeZeroWidthSpaces("a­b‍c"))
    }

    @Test
    fun splitterYieldsNoChunksForMarkerOnlyInput() {
        assertFalse(TextSplitter("\u200B").hasNext())
        assertFalse(TextSplitter("\u200B \u200B").hasNext())
    }

    @Test
    fun splitterReplacesLeadingZeroWidthSpace() {
        // Some apps prefix announcements with U+200B ZERO WIDTH SPACE to wake
        // up TTS engines; it must not be spoken as "zero width space".
        val splitter = TextSplitter("\u200BTurn left")
        assertEquals("Turn left", splitter.next())
        assertFalse(splitter.hasNext())
    }
}
