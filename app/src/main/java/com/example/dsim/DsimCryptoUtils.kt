package com.example.dsim

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 云端 MQTT 载荷的对称加解密。
 *
 * ## 线上格式
 *
 * ```
 * [4B 魔数 "DSM2"][16B 随机盐][12B GCM IV][密文][16B GCM 认证标签]
 * 密钥 = PBKDF2-HMAC-SHA256(口令, 盐, PBKDF2_ROUNDS 轮, 256 bit)
 * ```
 *
 * AES-256-GCM（认证加密），GCM 标签保证完整性，篡改会直接解密失败。
 * 盐与 IV 每条报文重新随机生成并随报文传输，因此各端只需共享口令即可互解。
 *
 * ## 改格式时的规矩
 *
 * 格式版本靠 **魔数** 识别（不用单字节版本号：报文开头是随机数据，单字节有 1/256 概率误判）。
 * 今后若要改格式：
 * 1. 换一个新魔数；
 * 2. **只有当线上真的存在跑旧格式的设备时**，才保留旧格式的读取分支
 *    （本项目当前无发布、无用户，任何"旧格式兼容"都是死代码，不要预先加）。
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
     * 中端机上单次派生约 0.2~0.5 秒，而设备快照心跳周期为 20 秒，占空比可忽略。
     */
    private const val PBKDF2_ROUNDS = 120_000

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
     * 解密载荷。
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

        // 头部 = 魔数(4) + 盐(16) + IV(12)，之后至少还要有一段密文
        if (combined.size <= AEAD_HEADER_SIZE) {
            return null
        }
        for (index in magicBytes.indices) {
            if (combined[index] != magicBytes[index]) {
                return null   // 魔数不符：不是本应用发出的报文
            }
        }

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
