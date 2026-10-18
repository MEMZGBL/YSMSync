package moe.meowrealms.ysmsync.model

import com.elfmcys.yesstevemodel.util.DigestUtil
import it.unimi.dsi.fastutil.Pair
import it.unimi.dsi.fastutil.bytes.ByteArrays
import rip.ysm.legacy.AESUtil
import rip.ysm.legacy.ByteInteger
import rip.ysm.legacy.DeflateUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.Collections
import java.util.Random
import java.util.zip.DataFormatException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 旧版（crypto v1 / v2）`.ysm` 文件的解密与解包。
 *
 * 原实现是 YSM 模组内的 `rip.ysm.legacy.YesModelUtils`，其中还包含一个仅用于
 * 本地文件、依赖 Minecraft `ResourceLocation` 的重载，服务端用不到也无法在新版
 * 服务端加载。这里只保留纯字节流处理逻辑，算法与原实现逐行一致。
 */
object YsmLegacyReader {

    private const val HEAD = 1498629968 // "YSGP"
    private const val ENCRYPTION_METHOD = "AES"

    /**
     * 判定 `.ysm` 文件的加密版本：
     * 3 = 新格式（[rip.ysm.security.YsmCrypt] 处理）；1/2 = 旧格式；-1 = 未知。
     */
    @JvmStatic
    fun getYsmCryptoVersion(fileData: ByteArray?): Int {
        if (fileData == null || fileData.size < 8) return -1

        // UTF-8 BOM + "YSGP"
        if (fileData[0] == 0xEF.toByte() && fileData[1] == 0xBB.toByte() &&
            fileData[2] == 0xBF.toByte() && fileData[3] == 'Y'.code.toByte() &&
            fileData[4] == 'S'.code.toByte() && fileData[5] == 'G'.code.toByte() &&
            fileData[6] == 'P'.code.toByte()
        ) {
            return 3
        }

        if (fileData[0] == 'Y'.code.toByte() && fileData[1] == 'S'.code.toByte() &&
            fileData[2] == 'G'.code.toByte() && fileData[3] == 'P'.code.toByte()
        ) {
            val cryptoVersion = ByteBuffer.wrap(fileData, 4, 4).order(ByteOrder.BIG_ENDIAN).int
            if (cryptoVersion == 2) return 2
            if (cryptoVersion == 1) return 1
        }

        return -1
    }

    /** 将旧版 `.ysm` 文件解包为「文件名 -> 原始文件内容」。 */
    @JvmStatic
    @Throws(IOException::class)
    fun input(data: ByteArray): Map<String, ByteArray> {
        if (data.size < 24) return Collections.emptyMap()

        val head = ByteInteger.bytes2Int(data, 0)
        val version = ByteInteger.bytes2Int(data, 4)

        return inputInternal(data, head, version)
    }

    private fun inputInternal(data: ByteArray, head: Int, version: Int): Map<String, ByteArray> {
        if (head != HEAD) return Collections.emptyMap()
        if (version != 1 && version != 2) return Collections.emptyMap()

        // [0,4) head | [4,8) version | [8,24) md5 | [24,) payload
        val md5 = ByteArrays.copy(data, 8, 16)
        val payload = ByteArrays.copy(data, 24, data.size - 24)

        if (!md5.contentEquals(DigestUtil.md5(payload))) return Collections.emptyMap()

        val outputs = HashMap<String, ByteArray>()
        val inputStream = ByteArrayInputStream(payload)

        while (inputStream.available() > 0) {
            try {
                val entry = if (version == 1) ysmToFile(inputStream) else ysmToFileNew(inputStream)
                outputs[entry.left()] = entry.right()
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: GeneralSecurityException) {
                e.printStackTrace()
            } catch (e: DataFormatException) {
                e.printStackTrace()
            }
        }

        return outputs
    }

    @Throws(IOException::class, GeneralSecurityException::class, DataFormatException::class)
    private fun ysmToFile(inputStream: ByteArrayInputStream): Pair<String, ByteArray> {
        val name = readString(inputStream)
        val size = readInt(inputStream)

        val passwordBytes = ByteArray(16)
        val ivBytes = ByteArray(16)
        inputStream.read(passwordBytes)
        inputStream.read(ivBytes)

        val key = SecretKeySpec(passwordBytes, ENCRYPTION_METHOD)
        val iv = IvParameterSpec(ivBytes)

        val fileData = ByteArray(size)
        inputStream.read(fileData)

        val decryptData = AESUtil.decrypt(key, iv, fileData)
        val rawData = DeflateUtil.decompressBytes(decryptData.toByteArray())

        return Pair.of(name, rawData)
    }

    @Throws(IOException::class, GeneralSecurityException::class, DataFormatException::class)
    private fun ysmToFileNew(inputStream: ByteArrayInputStream): Pair<String, ByteArray> {
        val fileName = readBase64String(inputStream)
        val fileSize = readInt(inputStream)
        val cipherSecretKeySize = readInt(inputStream)

        val cipherSecretKey = ByteArray(cipherSecretKeySize)
        val ivBytes = ByteArray(16)
        val fileData = ByteArray(fileSize)

        inputStream.read(cipherSecretKey)
        inputStream.read(ivBytes)
        inputStream.read(fileData)

        val keyFromMd5 = getKeyFromMd5(fileData)
        val secretSecretKey = AESUtil.getKey(keyFromMd5)
        val iv = IvParameterSpec(ivBytes)

        val decryptSecretKey = AESUtil.decrypt(secretSecretKey, iv, cipherSecretKey).toByteArray()
        val key = AESUtil.getKey(decryptSecretKey)
        val decryptData = AESUtil.decrypt(key, iv, fileData)
        val rawData = DeflateUtil.decompressBytes(decryptData.toByteArray())

        return Pair.of(fileName, rawData)
    }

    private fun getKeyFromMd5(fileData: ByteArray): ByteArray {
        val md5 = DigestUtil.md5(fileData)
        val random = Random(toLong(md5))
        val keys = ByteArray(16)
        random.nextBytes(keys)
        return keys
    }

    private fun toLong(bytes: ByteArray): Long {
        var value = 0L
        for (b in bytes) {
            value = (value shl 8) + (b.toInt() and 0xFF)
        }
        return value
    }

    @Throws(IOException::class)
    private fun readString(inputStream: ByteArrayInputStream): String {
        val size = readInt(inputStream)
        val stringBytes = ByteArray(size)
        inputStream.read(stringBytes)
        return String(stringBytes, StandardCharsets.UTF_8)
    }

    @Throws(IOException::class)
    private fun readBase64String(inputStream: ByteArrayInputStream): String {
        val size = readInt(inputStream)
        val stringBytes = ByteArray(size)
        inputStream.read(stringBytes)
        return String(Base64.getDecoder().decode(stringBytes), StandardCharsets.UTF_8)
    }

    @Throws(IOException::class)
    private fun readInt(inputStream: ByteArrayInputStream): Int {
        val sizeBytes = ByteArray(4)
        inputStream.read(sizeBytes)
        return ByteInteger.bytes2Int(sizeBytes, 0)
    }
}
