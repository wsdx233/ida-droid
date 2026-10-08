package dev.idadroid.proot

import java.io.File
import java.io.InputStream

/** ELF metadata only: unknown formats do not imply architecture compatibility. */
internal object ElfAbi {
    data class Header(val abi: String?, val machine: Int, val bits: Int) {
        val description: String get() = abi ?: "ELF${bits}, machine=$machine"
    }

    fun read(file: File): Header? = runCatching { file.inputStream().use { read(it) } }.getOrNull()

    fun read(input: InputStream): Header? {
        val header = ByteArray(20)
        var offset = 0
        while (offset < header.size) {
            val count = input.read(header, offset, header.size - offset)
            if (count <= 0) return null
            offset += count
        }
        if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) return null
        val bits = when (header[4].toInt()) { 1 -> 32; 2 -> 64; else -> return null }
        val low = header[18].toInt() and 0xff
        val high = header[19].toInt() and 0xff
        val machine = when (header[5].toInt()) {
            1 -> low or (high shl 8)
            2 -> (low shl 8) or high
            else -> return null
        }
        // Android's supported ABI list includes available 32-bit and native-bridge ABIs.
        val abi = if (header[5].toInt() != 1) null else when {
            machine == 183 && bits == 64 -> "arm64-v8a"
            machine == 40 && bits == 32 -> "armeabi-v7a"
            machine == 62 && bits == 64 -> "x86_64"
            machine == 3 && bits == 32 -> "x86"
            machine == 243 && bits == 64 -> "riscv64"
            else -> null
        }
        return Header(abi, machine, bits)
    }

    fun supported(header: Header, supportedAbis: List<String>): Boolean =
        header.abi != null && supportedAbis.any {
            it == header.abi || (header.abi == "armeabi-v7a" && it == "armeabi")
        }
}
