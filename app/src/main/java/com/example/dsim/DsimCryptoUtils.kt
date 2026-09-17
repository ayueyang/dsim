package com.example.dsim

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 云端 MQTT 载荷的对称加解密。
 *
 * ## 线上格式
 *
 * V2（当前写入格式，认证加密）：
 * ```
 * [4B 魔数 "DSM2"][16B 随机盐][12B GCM IV][密文][16B GCM 认证标签]
 * 密钥 = PBKDF2-HMAC-SHA256(口令, 盐, PBKDF2_ROUNDS 轮, 256 bit)
 * ```
 * 盐与 IV 每条报文重新随机生成并随报文传输，因此各端只需共享口令即可互解。
 *
 * V1（历史格式，仅保留读取能力）：
 * ```
 * [16B IV][AES/CBC/PKCS5Padding 密文]
 * 密钥 = SHA-256(口令)
 * ```
 * V1 没有完整性校验，密钥也只做单轮哈希。**不得用于新数据**，仅用于平滑升级
 * 期间读取尚未升级的对端发来的报文。
 *
 * ## 升级注意事项
 *
 * 写入一律使用 V2，读取同时支持 V1 / V2。因此升级期间：
 * - 新版本可以正常读取旧版本发来的报文；
 * - 旧版本**无法**读取新版本发来的报文，会解密失败并丢弃。
 *
 * 结论：所有已配对设备应一并升级，否则会出现单向消息丢失。
 *
 * ## 为什么自行实现 PBKDF2
 *
 * `SecretKeyFactory("PBKDF2WithHmacSHA256")` 需要 API 26，而本项目 minSdk = 24。
 * 若按 API 级别回退到 `PBKDF2WithHmacSHA1`，同一口令在不同 Android 版本上会
 * 派生出**不同**密钥，导致跨设备静默解密失败。故此处按 RFC 8018 §5.2 固定实现
 * HMAC-SHA256 版本，保证所有设备结果一致。
 */
object DsimCryptoUtils {

    /** 加密失败时返回的哨兵值。调用方必须在发送前比对该值。 */
    const val ENCRYPTION_ERROR = "ENCRYPTION_ERROR"

    private const val AEAD_MAGIC = "DSM2"
    private const val AEAD_SALT_SIZE = 16
    private const val AEAD_IV_SIZE = 12
    private const val AEAD_TAG_BITS = 128
    private const val AEAD_KEY_BITS = 256
    private const val AEAD_HEADER_SIZE = 4 + AEAD_SALT_SIZE + AEAD_IV_SIZE

    private const val MAC_ALGORITHM = "HmacSHA256"
    private const val KEY_ALGORITHM = "AES"
    private const val AEAD_TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * PBKDF2 迭代轮数。取值在"抗离线爆破"与"单次加解密耗时"之间折中：
     * 中端机上单次派生约 0.2~0.5 秒，而设备快照心跳周期为 30 秒，占空比可忽略。
     */
    private const val PBKDF2_ROUNDS = 120_000

    private const val LEGACY_TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val LEGACY_IV_SIZE = 16

    private val magicBytes = AEAD_MAGIC.toByteArray(Charsets.US_ASCII)
    private val secureRandom = SecureRandom()

    /**
     * 加密载荷，返回 Base64（无换行）字符串。
     *
     * @param secret 用户口令，即共享密钥的种子。注意传入的是**口令**，不是 MQTT Topic；
     *               Topic 只决定报文投递到哪个频道，不参与密钥派生。
     * @return 密文；失败时返回 [ENCRYPTION_ERROR]。
     */
    fun encryptMessage(plaintext: String, secret: String): String {
        return try {
            val salt = ByteArray(AEAD_SALT_SIZE).also { secureRandom.nextBytes(it) }
            val iv = ByteArray(AEAD_IV_SIZE).also { secureRandom.nextBytes(it) }

            val keySpec = SecretKeySpec(deriveAeadKey(secret, salt), KEY_ALGORITHM)
            val cipher = Cipher.getInstance(AEAD_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(AEAD_TAG_BITS, iv))
            val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val combined = magicBytes + salt + iv + sealed
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            ENCRYPTION_ERROR
        }
    }

    /**
     * 解密载荷，自动识别 V2 / V1 两种格式。
     *
     * @return 明文；Base64 非法、格式残缺、口令不符或完整性校验失败时返回 `null`。
     */
    fun decryptMessage(ciphertextBase64: String, secret: String): String? {
        val combined = try {
            Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        if (combined.size < LEGACY_IV_SIZE) {
            return null
        }

        return if (isAeadEnvelope(combined)) {
            decryptAead(combined, secret)
        } else {
            decryptLegacy(combined, secret)
        }
    }

    /**
     * 判断是否为 V2 报文。只看魔数，不做认证。
     *
     * V1 报文的开头是随机 IV，理论上可能恰好等于魔数（概率 2^-32），
     * 此时会被误判并导致该条报文解密失败——概率可接受，不做额外兜底。
     */
    private fun isAeadEnvelope(combined: ByteArray): Boolean {
        if (combined.size <= AEAD_HEADER_SIZE) {
            return false
        }
        for (index in magicBytes.indices) {
            if (combined[index] != magicBytes[index]) {
                return false
            }
        }
        return true
    }

    private fun decryptAead(combined: ByteArray, secret: String): String? {
        return try {
            val salt = combined.copyOfRange(magicBytes.size, magicBytes.size + AEAD_SALT_SIZE)
            val iv = combined.copyOfRange(magicBytes.size + AEAD_SALT_SIZE, AEAD_HEADER_SIZE)
            val sealed = combined.copyOfRange(AEAD_HEADER_SIZE, combined.size)

            val keySpec = SecretKeySpec(deriveAeadKey(secret, salt), KEY_ALGORITHM)
            val cipher = Cipher.getInstance(AEAD_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(AEAD_TAG_BITS, iv))

            String(cipher.doFinal(sealed), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 读取 V1 历史报文，密钥派生为 `SHA-256(口令)`。
     *
     * 旧实现里还有一步 `topic.padEnd(KEY_SIZE, 'd')`，但 SHA-256 的输出长度恒为
     * 32 字节、与输入长度无关，该补位对结果没有任何影响，属于无副作用的死代码。
     * 这里直接省略，派生结果与旧实现逐位一致，因此仍能解开历史报文。
     */
    private fun decryptLegacy(combined: ByteArray, secret: String): String? {
        return try {
            val iv = combined.copyOfRange(0, LEGACY_IV_SIZE)
            val body = combined.copyOfRange(LEGACY_IV_SIZE, combined.size)

            val digest = MessageDigest.getInstance("SHA-256")
            val keyBytes = digest.digest(secret.toByteArray(Charsets.UTF_8))

            val cipher = Cipher.getInstance(LEGACY_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, KEY_ALGORITHM),
                IvParameterSpec(iv)
            )

            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** RFC 8018 §5.2：PBKDF2-HMAC-SHA256，输出固定 [AEAD_KEY_BITS] 位。 */
    private fun deriveAeadKey(secret: String, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), MAC_ALGORITHM))

        val hashLength = mac.macLength
        val blockCount = (AEAD_KEY_BITS + hashLength * 8 - 1) / (hashLength * 8)
        val output = ByteArray(blockCount * hashLength)

        var offset = 0
        for (blockIndex in 1..blockCount) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (blockIndex ushr 24).toByte(),
                    (blockIndex ushr 16).toByte(),
                    (blockIndex ushr 8).toByte(),
                    blockIndex.toByte()
                )
            )

            var iteration = mac.doFinal()
            val accumulated = iteration.copyOf()
            repeat(PBKDF2_ROUNDS - 1) {
                iteration = mac.doFinal(iteration)
                for (index in accumulated.indices) {
                    accumulated[index] =
                        (accumulated[index].toInt() xor iteration[index].toInt()).toByte()
                }
            }

            accumulated.copyInto(output, offset)
            offset += hashLength
        }

        return output.copyOf(AEAD_KEY_BITS / 8)
    }
}
