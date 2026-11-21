package com.nova.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitCoverMetadataTest {
    @Test fun extractsAndFormatsBothValues() {
        assertEquals(OrbitCoverMetadata("8.6 分", "6 集"), orbitCoverMetadata("aihuangdou", "评分 8.6 分 · 更新中 · 6集 · 简介"))
    }
    @Test fun yellowFruitItemKeyShowsProvidedRatingAndEpisodeCount() {
        val content = "评分 8.5 分 · 更新中 · 6集"
        val expected = OrbitCoverMetadata("8.5 分", "6 集")
        assertEquals(expected, orbitCoverMetadata("huangguoai", content))
        assertEquals(expected, orbitCoverMetadata("other", content, "huangguoai:latest:15326"))
        assertEquals(OrbitCoverMetadata(null, null), orbitCoverMetadata("other", content, "huangguoaix:latest:15326"))
    }
    @Test fun missingAndInvalidFieldsAreNotInvented() {
        assertEquals(OrbitCoverMetadata(null, "6 集"), orbitCoverMetadata("aihuangdou", "评分 12.1 分 · 更新中 · 6集 · 简介"))
        assertEquals(OrbitCoverMetadata("10 分", null), orbitCoverMetadata("aihuangdou", "评分 10 分 · 0集"))
        assertEquals(OrbitCoverMetadata(null, null), orbitCoverMetadata("aihuangdou", null))
        assertEquals(OrbitCoverMetadata(null, "6 集"), orbitCoverMetadata("aihuangdou", "评分 无 · 更新中 · 6集"))
        assertEquals(OrbitCoverMetadata(null, null), orbitCoverMetadata("aihuangdou", "简介提到评分 8.6 分 · 6集"))
    }
    @Test fun pluginIsolationAndIndividualLabels() {
        val content = "评分 8.6 分 · 6集"
        assertEquals(OrbitCoverMetadata(null, null), orbitCoverMetadata("other", content))
        assertEquals(OrbitCoverMetadata(null, null), orbitCoverMetadata(null, content))
        assertEquals(OrbitCoverMetadata("8.6 分", null), orbitCoverMetadata("aihuangdou", "评分 8.6 分"))
        assertEquals(OrbitCoverMetadata(null, "6 集"), orbitCoverMetadata("aihuangdou", "6集"))
    }
}
