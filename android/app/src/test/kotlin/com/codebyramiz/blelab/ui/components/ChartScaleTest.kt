package com.codebyramiz.blelab.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChartScaleTest {
    @Test
    fun flatSignalKeepsMinimumSpanCentredOnData() {
        val range = ChartScale.fit(listOf(21f, 21f, 21f), minSpan = 4f)
        assertThat(range.span).isAtLeast(4f)
        assertThat((range.min + range.max) / 2).isWithin(0.001f).of(21f)
    }

    @Test
    fun wideSignalIsNotCompressed() {
        val range = ChartScale.fit(listOf(0f, 100f), minSpan = 4f)
        assertThat(range.min).isLessThan(0f)
        assertThat(range.max).isGreaterThan(100f)
    }

    @Test
    fun emptySamplesGiveMinimumSpan() {
        assertThat(ChartScale.fit(emptyList(), minSpan = 4f).span).isWithin(0.001f).of(4f)
    }

    @Test
    fun fractionMapsRangeEnds() {
        val range = YRange(10f, 20f)
        assertThat(range.fraction(10f)).isEqualTo(0f)
        assertThat(range.fraction(20f)).isEqualTo(1f)
    }

    @Test
    fun symmetricExtentUsesLargestAbsoluteValueAcrossAxes() {
        assertThat(ChartScale.symmetricExtent(listOf(listOf(0.1f, -2f), listOf(1f)), minExtent = 1f)).isEqualTo(2f)
    }

    @Test
    fun symmetricExtentRespectsMinimum() {
        assertThat(ChartScale.symmetricExtent(listOf(listOf(0.1f)), minExtent = 1f)).isEqualTo(1f)
        assertThat(ChartScale.symmetricExtent(emptyList(), minExtent = 1f)).isEqualTo(1f)
    }
}
