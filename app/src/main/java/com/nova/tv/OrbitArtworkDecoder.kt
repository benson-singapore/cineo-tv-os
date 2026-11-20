package com.nova.tv

import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Matches the site's crypto-image-worker.js; credentials are UTF-8, not hex. */
internal object OrbitArtworkDecoder {
    private val hosts = setOf("pic.qnlycc.cn", "pic.drleao.cn", "new.drleao.cn", "image.drleao.cn", "pic.aluxvl.cn", "aihuangdou.com", "new2.drleao.cn")

    fun supports(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme in setOf("http", "https") && uri.host?.lowercase() in hosts && uri.userInfo == null
    }.getOrDefault(false)

    fun webpFallback(url: String): String? = runCatching {
        val uri = URI(url)
        if (!supports(url) || uri.host.equals("pic.qnlycc.cn", true) || uri.host.equals("new2.drleao.cn", true) ||
            uri.rawPath.startsWith("/static/") ||
            !Regex("\\.(jpe?g|png|bmp)$", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath)) null
        else "https://new2.drleao.cn${uri.rawPath}.webp"
    }.getOrNull()

    fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty())
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        // Public page configuration observed 2026-11-11. Recheck if the site rotates it.
        cipher.init(Cipher.DECRYPT_MODE,
            SecretKeySpec("f5d965df75336270".toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec("97b60394abc2fbe1".toByteArray(Charsets.UTF_8)))
        val aligned = bytes.copyOf(((bytes.size + 15) / 16) * 16)
        return cipher.doFinal(aligned).copyOf(bytes.size)
    }
}
