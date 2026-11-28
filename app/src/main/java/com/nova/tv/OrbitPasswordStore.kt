package com.nova.tv

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The persisted record is replaced atomically; a failed write never changes active credentials. */
internal class OrbitPasswordStore(
    private val readRecord: () -> String?,
    private val writeRecord: (String) -> Unit,
    private val legacyRecord: () -> String?
) {
    private var record = try { readRecord() ?: legacyRecord() ?: "none" } catch (_: Exception) { "invalid" }
    val hasPassword: Boolean @Synchronized get() = record != "none"

    private fun hash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    @Synchronized fun verify(password: String): Boolean = try {
        val parts = record.split(':')
        if (parts.size != 2) false else MessageDigest.isEqual(
            Base64.getDecoder().decode(parts[1]), hash(password, Base64.getDecoder().decode(parts[0])))
    } catch (_: Exception) { false }

    @Synchronized fun save(password: String): Boolean = try {
        val next = if (password.isEmpty()) "none" else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            "${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(hash(password, salt))}"
        }
        writeRecord(next)
        record = next
        true
    } catch (_: Exception) { false }
}

internal fun writeOrbitPasswordRecord(file: File, value: String) {
    val temporary = File.createTempFile("orbit-password-", ".tmp", file.parentFile)
    try {
        FileOutputStream(temporary).use { stream ->
            stream.write(value.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}
