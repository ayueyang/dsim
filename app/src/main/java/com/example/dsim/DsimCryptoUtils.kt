package com.example.dsim

import android.util.Base64
import androidx.annotation.WorkerThread
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 云端 MQTT 载荷的对称加解密。
 *
 * ## 线上格式（DSM3）
 *
 * ```
 * [4B 魔数 "DSM3"][12B 随机 IV][密文][16B GCM 认证标签]
 * 主密钥 = PBKDF2-HMAC-SHA256(口令, APP_SALT, PBKDF2_ROUNDS 轮, 256 bit)   —— 每个口令只算一次
 * AAD    = 魔数（防止把 DSM3 报文改头换面塞给别的解析器）
 * ```
 *
 * AES-256-GCM（认证加密），GCM 标签保证完整性，篡改会直接解密失败。IV 每条报文随机生成，
 * 同一主密钥下 96 bit 随机 IV 的碰撞概率在本应用的报文量级上可以忽略。
 *
 * ## 为什么 DSM3 取代 DSM2
 *
 * DSM2 给**每条报文**随机盐并重新跑 12 万轮 PBKDF2，中端机 0.2~0.5 秒 CPU；配合 20 秒心跳
 * 和同 topic 自身回声，常驻占用可观。抗离线爆破的慢 KDF 应当只保护"口令 → 主密钥"这一步，
 * 而不是每条消息。DSM3 把盐固定为应用常量：对单个口令的爆破代价与 DSM2 完全相同（仍是 12 万轮），
 * 失去的只是"跨应用彩虹表"防护，而 12 万轮本身已让预计算不可行。
 *
 * DSM2 读取分支已删除（项目无发布、无用户，兼容分支是死代码，见 REFACTORING.md N2）。
 *
 * ## 改格式时的规矩
 *
 * 格式版本靠**魔数**识别。今后若要改格式：换新魔数；只有当线上真的存在旧格式设备时才保留旧读取分支。
 *
 * ## 线程
 *
 * 首次遇到某个口令时派生主密钥约 0.2~0.5 秒，之后同口令为微秒级。所有入口标注 [WorkerThread]，
 * 禁止主线程调用。
 *
 * ## 为什么自行实现 PBKDF2
 *
 * `SecretKeyFactory("PBKDF2WithHmacSHA256")` 需要 API 26，而 minSdk = 24。若按 API 级别回退到
 * SHA1 版本，同一口令在不同 Android 版本上会派生出不同密钥。故按 RFC 8018 §5.2 固定实现 SHA256 版本。
 */
object DsimCryptoUtils {

    private const val TAG = "dSIM_Crypto"

    private const val AEAD_MAGIC = "DSM3"
    private const val AEAD_IV_SIZE = 12
    private const val AEAD_TAG_BITS = 128
    private const val AEAD_KEY_BITS = 256
    private const val AEAD_HEADER_SIZE = 4 + AEAD_IV_SIZE

    private const val MAC_ALGORITHM = "HmacSHA256"
    private const val KEY_ALGORITHM = "AES"
    private const val AEAD_TRANSFORMATION = "AES/GCM/NoPadding"

    /** PBKDF2 迭代轮数。只在"口令 → 主密钥"时执行一次，不再按报文计。 */
    private const val PBKDF2_ROUNDS = 120_000

    /** 应用级固定盐。改动它等于换密钥，所有设备必须一起升级。 */
    private val appSalt = "dSIM/v3/master-key".toByteArray(Charsets.US_ASCII)

    /** 缓存上限：同一进程通常只会见到 1~2 个口令（切组时）。 */
    private const val KEY_CACHE_LIMIT = 4

    private val magicBytes = AEAD_MAGIC.toByteArray(Charsets.US_ASCII)
    private val secureRandom = SecureRandom()
    private val keyCache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > KEY_CACHE_LIMIT
    }

    /**
     * 加密载荷，返回 Base64（无换行）字符串。
     *
     * @param secret 用户口令，即共享密钥的种子。传入的是**口令**，不是 MQTT Topic；
     *               Topic 只决定报文投递到哪个频道，不参与密钥派生。
     * @return 密文；失败时返回 `null`（已记录日志）。调用方用 `?: return` 处理，不要再比对哨兵字符串。
     */
    @WorkerThread
    fun encryptOrNull(plaintext: String, secret: String): String? {
        return try {
            Base64.encodeToString(seal(plaintext.toByteArray(Charsets.UTF_8), secret), Base64.NO_WRAP)
        } catch (e: Exception) {
            DsimLog.e(TAG, "encrypt failed", e)
            null
        }
    }

    /**
     * 解密载荷。
     *
     * @return 明文；Base64 非法、格式残缺、魔数不符、口令不符或完整性校验失败时返回 `null`。
     */
    @WorkerThread
    fun decryptMessage(ciphertextBase64: String, secret: String): String? {
        val combined = try {
            Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            DsimLog.w(TAG, "decrypt: invalid base64")
            return null
        }
        return open(combined, secret)?.let { String(it, Charsets.UTF_8) }
    }

    /** 字节级加密，供单测直接调用（不依赖 android.util.Base64）。 */
    @WorkerThread
    internal fun seal(plaintext: ByteArray, secret: String): ByteArray {
        val iv = ByteArray(AEAD_IV_SIZE).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AEAD_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey(secret), KEY_ALGORITHM), GCMParameterSpec(AEAD_TAG_BITS, iv))
        cipher.updateAAD(magicBytes)
        val sealed = cipher.doFinal(plaintext)
        return magicBytes + iv + sealed
    }

    /** 字节级解密；任何失败返回 null。 */
    @WorkerThread
    internal fun open(combined: ByteArray, secret: String): ByteArray? {
        if (combined.size <= AEAD_HEADER_SIZE) return null
        for (index in magicBytes.indices) {
            if (combined[index] != magicBytes[index]) return null
        }
        return try {
            val iv = combined.copyOfRange(magicBytes.size, AEAD_HEADER_SIZE)
            val sealed = combined.copyOfRange(AEAD_HEADER_SIZE, combined.size)
            val cipher = Cipher.getInstance(AEAD_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(masterKey(secret), KEY_ALGORITHM), GCMParameterSpec(AEAD_TAG_BITS, iv))
            cipher.updateAAD(magicBytes)
            cipher.doFinal(sealed)
        } catch (e: Exception) {
            // Wrong password or tampered payload both surface as AEADBadTagException; keep it quiet
            // but visible: one line, no stack, so a hostile broker cannot flood the log.
            DsimLog.w(TAG, "decrypt failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 主密钥：每个口令派生一次并缓存。 */
    @WorkerThread
    internal fun masterKey(secret: String): ByteArray {
        synchronized(keyCache) { keyCache[secret]?.let { return it } }
        val derived = deriveKey(secret, appSalt)
        synchronized(keyCache) { keyCache[secret] = derived }
        return derived
    }

    /** 仅供测试：清空缓存以便测量"首次派生"路径。 */
    internal fun clearKeyCacheForTest() = synchronized(keyCache) { keyCache.clear() }

    /** 主密钥是否已缓存（测试与诊断用，不派生）。 */
    internal fun isKeyCached(secret: String): Boolean = synchronized(keyCache) { keyCache.containsKey(secret) }

    /** RFC 8018 §5.2：PBKDF2-HMAC-SHA256，输出固定 [AEAD_KEY_BITS] 位。 */
    private fun deriveKey(secret: String, salt: ByteArray): ByteArray {
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
