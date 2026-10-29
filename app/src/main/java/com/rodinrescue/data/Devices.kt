package com.rodinrescue.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A computer this phone has been set up to reach. Not secret: the phone's private key lives in SecretStore. */
@Serializable
data class DeviceProfile(
    val id: String,
    val name: String,
    val user: String,
    val lastIp: String,
    val port: Int = 22,
    val hostname: String = "",
    val fingerprints: List<String> = emptyList(),
    val createdAt: Long,
    val lastUsed: Long,
    /** What Lifeline's setup changed on that laptop (null = unknown / set up before this was recorded). */
    val stateRecorded: Boolean = false,
    val installedSshd: Boolean? = null,
    val sshdWasEnabled: Boolean? = null,
    val ufwIface: String? = null,
    val osInfo: String = ""
)

/** JSON-file store of device profiles. One laptop = one profile, matched by any shared SSH host-key fingerprint. */
class DeviceStore(dir: File) {
    private val file = File(dir, "devices.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val ser = ListSerializer(DeviceProfile.serializer())

    @Synchronized fun all(): List<DeviceProfile> =
        if (!file.exists()) emptyList() else runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList()).sortedByDescending { it.lastUsed }

    @Synchronized fun get(id: String) = all().firstOrNull { it.id == id }

    /** The profile for a laptop presenting [fps], or for [user]@[hostname] when no fingerprint is known. */
    @Synchronized fun find(fps: Collection<String>, user: String, hostname: String): DeviceProfile? = all().firstOrNull { d ->
        (fps.isNotEmpty() && d.fingerprints.any { it in fps }) || (fps.isEmpty() && hostname.isNotBlank() && d.user == user && d.hostname == hostname)
    }

    @Synchronized fun save(d: DeviceProfile) = write(all().filterNot { it.id == d.id } + d)
    @Synchronized fun delete(id: String) = write(all().filterNot { it.id == id })

    private fun write(list: List<DeviceProfile>) {
        val tmp = File(file.parentFile, "devices.json.tmp"); tmp.writeText(json.encodeToString(ser, list)); tmp.renameTo(file)
    }

    companion object { fun newId() = java.util.UUID.randomUUID().toString() }
}
