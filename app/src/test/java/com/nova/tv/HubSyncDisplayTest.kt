package com.nova.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class HubSyncDisplayTest {
    @Test fun partialSyncDoesNotExposeInternalRecordsAndStillSignalsIncomplete() {
        val status = SyncStatus("推送部分完成", "保留 2 条 history · internal-key")
        assertEquals("部分更改暂未同步，请检查数据后重试。", visibleSyncDetail(status))
    }

    @Test fun ordinaryFailureAndSuccessDetailsRemainVisible() {
        assertEquals("Hub 暂不可用", visibleSyncDetail(SyncStatus("拉取失败", "Hub 暂不可用")))
        assertEquals("云端数据已更新", visibleSyncDetail(SyncStatus("已拉取", "云端数据已更新")))
    }
}
