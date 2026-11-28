package com.nova.tv

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OrbitPasswordStoreTest {
    @Test fun failedSetAndClearLeaveTheOldPasswordActiveIncludingAfterReload() {
        var disk: String? = null
        var fail = false
        fun store() = OrbitPasswordStore({ disk }, { value -> if (fail) error("disk failure") else disk = value }, { null })
        val password = store()
        assertTrue(password.save("old password"))
        fail = true
        assertFalse(password.save("new password"))
        assertTrue(password.verify("old password"))
        assertFalse(password.verify("new password"))
        assertFalse(password.save(""))
        assertTrue(password.hasPassword)
        assertTrue(store().verify("old password"))
        fail = false
        assertTrue(password.save(""))
        assertFalse(password.hasPassword)
        assertFalse(store().hasPassword)
    }

    @Test fun failedInitialSetDoesNotCreatePassword() {
        val password = OrbitPasswordStore({ null }, { error("disk failure") }, { null })
        assertFalse(password.save("new"))
        assertFalse(password.hasPassword)
    }

    @Test fun legacyCredentialRemainsUsableAndClearOverridesLegacy() {
        var record: String? = null
        val initial = OrbitPasswordStore({ record }, { record = it }, { null })
        assertTrue(initial.save("legacy"))
        val legacy = record
        record = null
        val migrated = OrbitPasswordStore({ record }, { record = it }, { legacy })
        assertTrue(migrated.verify("legacy"))
        assertTrue(migrated.save(""))
        assertFalse(OrbitPasswordStore({ record }, { record = it }, { legacy }).hasPassword)
    }

    @Test fun unreadableRecordFailsClosed() {
        val password = OrbitPasswordStore({ error("read failed") }, {}, { null })
        assertTrue(password.hasPassword)
        assertFalse(password.verify("anything"))
    }

    @Test fun atomicWriterReplacesAnExistingRecord() {
        val directory = Files.createTempDirectory("orbit-password-test").toFile()
        val file = directory.resolve("password")
        try {
            writeOrbitPasswordRecord(file, "old")
            writeOrbitPasswordRecord(file, "new")
            assertEquals("new", file.readText())
            assertEquals(listOf("password"), directory.list()?.toList())
        } finally { file.delete(); directory.delete() }
    }
}
