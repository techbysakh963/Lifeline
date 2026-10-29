package com.rodinrescue.setup

import com.rodinrescue.data.SecretStore
import net.schmizz.sshj.common.Buffer
import java.security.KeyPairGenerator
import java.util.Base64

/** The phone's own SSH identity (RSA-3072, generated on first use, private key stored Keystore-encrypted). Never leaves the phone. */
class KeyManager(private val secrets: SecretStore?) {
    data class Identity(val privatePem: String, val publicLine: String)

    @Synchronized
    fun identity(): Identity {
        val priv = secrets?.get("phone_key_priv"); val pub = secrets?.get("phone_key_pub")
        if (priv != null && pub != null) return Identity(priv, pub)
        return generate().also { secrets?.put("phone_key_priv", it.privatePem); secrets?.put("phone_key_pub", it.publicLine) }
    }

    fun reset() { secrets?.put("phone_key_priv", null); secrets?.put("phone_key_pub", null) }

    companion object {
        fun generate(): Identity {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
            val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.private.encoded)
            val pem = "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
            val blob = Buffer.PlainBuffer().putPublicKey(kp.public).compactData
            return Identity(pem, "ssh-rsa " + Base64.getEncoder().encodeToString(blob) + " rodin-rescue")
        }
    }
}
