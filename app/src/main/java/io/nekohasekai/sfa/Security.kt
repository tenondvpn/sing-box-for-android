package io.nekohasekai.sfa

import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.util.encoders.Hex
import java.math.BigInteger
import java.security.Security

object CryptoUtils {
    init {
        // 注册 BouncyCastle
        if (Security.getProvider("BC") == null) {
            Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
    }

    private val CURVE_PARAMS = ECNamedCurveTable.getParameterSpec("secp256k1")
    private val DOMAIN_PARAMS = ECDomainParameters(CURVE_PARAMS.curve, CURVE_PARAMS.g, CURVE_PARAMS.n, CURVE_PARAMS.h)

    // Keccak256 哈希
    fun keccak256(input: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(input, 0, input.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    class Ecdsa {
        private var privateKey: ECPrivateKeyParameters? = null
        private var publicKey: ECPublicKeyParameters? = null

        fun setPrivateKey(privateKeyBytes: ByteArray): Boolean {
            if (privateKeyBytes.size != 32) return false
            try {
                val d = BigInteger(1, privateKeyBytes)
                this.privateKey = ECPrivateKeyParameters(d, DOMAIN_PARAMS)

                // 推导公钥 Q = d * G
                val q: ECPoint = DOMAIN_PARAMS.g.multiply(d)
                this.publicKey = ECPublicKeyParameters(q, DOMAIN_PARAMS)
                return true
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
        }

        fun getPublicKeyCompressed(): String {
            val q = publicKey?.q ?: return ""
            // true 表示压缩格式 (02/03 + X)
            val encoded = q.getEncoded(true)
            return Hex.toHexString(encoded)
        }

        // 模拟 Go 的 crypto.Sign (R + S)
        // 注意：Go 的 crypto.Sign 通常返回 65 字节 [R|S|V]。
        // 这里为了简化实现，我们生成标准的 [R|S] (64字节)。
        // 如果服务器严格校验 RecID (V)，需要更复杂的逻辑计算 V。
        fun sign(msgHashHex: String): String? {
            val priv = privateKey ?: return null
            try {
                val hashBytes = Hex.decode(msgHashHex)

                val signer = ECDSASigner(HMacDSAKCalculator(org.bouncycastle.crypto.digests.SHA256Digest()))
                signer.init(true, priv)
                val components = signer.generateSignature(hashBytes)

                val r = components[0]
                val s = components[1]

                // 规范化 s (防止延展性攻击，虽然 BouncyCastle 通常处理得很好，但这是比特币/以太坊标准)
                val halfN = DOMAIN_PARAMS.n.shiftRight(1)
                val finalS = if (s > halfN) DOMAIN_PARAMS.n.subtract(s) else s

                // 格式化为 32 字节 + 32 字节
                val rBytes = to32Bytes(r)
                val sBytes = to32Bytes(finalS)

                // 模拟 Go 的 Sign 返回，这里暂时追加 00 作为 V (RecId)，因为标准 DSA 算不出 V
                // 如果服务端必须校验 V，需要引入 web3j 库来做签名
                val vBytes = ByteArray(1) { 0 }

                return Hex.toHexString(rBytes + sBytes + vBytes)
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        fun getAddress(): String {
            // 简单的模拟地址生成，实际以太坊地址是 Keccak(Pub_Uncompressed).sub(12)
            return "0x" + getPublicKeyCompressed().take(40) // 仅作占位符
        }

        private fun to32Bytes(num: BigInteger): ByteArray {
            val array = num.toByteArray()
            if (array.size == 32) return array
            // 处理 BigInteger 符号位可能导致多于或少于 32 字节的情况
            val result = ByteArray(32)
            if (array.size > 32) {
                System.arraycopy(array, array.size - 32, result, 0, 32)
            } else {
                System.arraycopy(array, 0, result, 32 - array.size, array.size)
            }
            return result
        }
    }
}