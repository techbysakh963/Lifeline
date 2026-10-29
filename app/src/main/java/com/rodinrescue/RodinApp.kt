package com.rodinrescue

import android.app.Application
import com.rodinrescue.data.AuditLog
import com.rodinrescue.data.SecretStore
import com.rodinrescue.data.Settings
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class RodinApp : Application() {
    lateinit var settings: Settings; private set
    lateinit var secrets: SecretStore; private set
    lateinit var audit: AuditLog; private set
    lateinit var devices: com.rodinrescue.data.DeviceStore; private set
    lateinit var conversations: com.rodinrescue.data.ConversationStore; private set

    override fun onCreate() {
        super.onCreate()
        // Android ships a stripped-down "BC"; sshj needs the full BouncyCastle (Ed25519, curve25519 KEX, etc.).
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        settings = Settings(this); secrets = SecretStore(this); audit = AuditLog(filesDir)
        devices = com.rodinrescue.data.DeviceStore(filesDir); conversations = com.rodinrescue.data.ConversationStore(filesDir)
        com.rodinrescue.data.Migrations(settings, devices, conversations, audit).run()
        settings.autoDeleteDays.takeIf { it > 0 }?.let { d -> Thread { com.rodinrescue.data.Storage.prune(filesDir, d) }.start() }
    }
}
