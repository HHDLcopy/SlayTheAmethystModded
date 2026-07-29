package io.stamethyst.backend.easytier

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The patcher rewrites raw ELF bytes, so the fixtures below are synthetic
 * ELF64/little-endian objects shaped like the EasyTier prebuilts: a `.dynstr`
 * segment with alignment padding after it and a `PT_DYNAMIC` whose owning
 * `PT_LOAD` ends exactly where the dynamic array ends.
 */
class EasyTierNativeElfPatcherTest {
    private val root = Files.createTempDirectory("easytier-elf-patcher-").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun ensureSiblingDependencyLookup_injectsOriginRunpath() {
        val library = writeFixture("libeasytier_android_jni.so")
        val originalSize = library.length()

        val patched = EasyTierNativeElfPatcher.ensureSiblingDependencyLookup(
            library,
            setOf("libeasytier_ffi.so"),
        )

        assertTrue(patched)
        // A larger file would change the mapping layout and would also defeat the
        // size-based staleness check in the loader.
        assertEquals(originalSize, library.length())

        val image = Elf(library)
        assertEquals("\$ORIGIN", image.runpath())
        assertEquals(STRING_TABLE.size + RUNPATH.size, image.tagValue(DT_STRSZ)?.toInt())
        assertEquals(DYNAMIC_SIZE + 16L, image.dynamicFileSize())
        assertEquals(DYNAMIC_SIZE + 16L, image.dynamicMemSize())
        assertEquals(DYNAMIC_SIZE + 16L, image.dynamicLoadFileSize())
        // The array must stay NUL terminated or the linker would read past it.
        assertEquals(DT_NULL, image.lastTag())
        assertArrayEquals(NEEDED, image.needed().toTypedArray())
        // bionic maps both sections through the section headers and rejects the
        // library when `.dynamic`'s size disagrees with PT_DYNAMIC, so the
        // section table has to track the growth as well.
        assertEquals(DYNAMIC_SIZE + 16L, image.sectionSize(SHT_DYNAMIC))
        assertEquals(
            STRING_TABLE.size.toLong() + RUNPATH.size,
            image.sectionSize(SHT_STRTAB)
        )
    }

    @Test
    fun ensureSiblingDependencyLookup_isIdempotent() {
        val library = writeFixture("libeasytier_android_jni.so")
        val siblings = setOf("libeasytier_ffi.so")

        assertTrue(EasyTierNativeElfPatcher.ensureSiblingDependencyLookup(library, siblings))
        val afterFirstPass = library.readBytes()

        assertFalse(EasyTierNativeElfPatcher.ensureSiblingDependencyLookup(library, siblings))
        assertArrayEquals(afterFirstPass, library.readBytes())
    }

    @Test
    fun ensureSiblingDependencyLookup_skipsLibraryWithoutSiblingDependency() {
        val library = writeFixture("libeasytier_ffi.so")
        val original = library.readBytes()

        // The ffi library depends on libc only, so it needs no search path.
        assertFalse(
            EasyTierNativeElfPatcher.ensureSiblingDependencyLookup(
                library,
                setOf("libsomething_else.so"),
            )
        )
        assertArrayEquals(original, library.readBytes())
    }

    @Test
    fun ensureSiblingDependencyLookup_ignoresNonElfPayload() {
        val library = File(root, "not-an-elf.so").apply { writeBytes(ByteArray(4096) { 0x41 }) }
        val original = library.readBytes()

        assertFalse(
            EasyTierNativeElfPatcher.ensureSiblingDependencyLookup(
                library,
                setOf("libeasytier_ffi.so"),
            )
        )
        assertArrayEquals(original, library.readBytes())
    }

    private fun writeFixture(name: String): File =
        File(root, name).apply { writeBytes(buildElf()) }

    /** Mirrors the prebuilt layout closely enough to exercise every guard. */
    private fun buildElf(): ByteArray {
        val image = ByteArray(FILE_SIZE)
        val buffer = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(0, 0x7F.toByte())
        buffer.put(1, 'E'.code.toByte())
        buffer.put(2, 'L'.code.toByte())
        buffer.put(3, 'F'.code.toByte())
        buffer.put(4, 2)
        buffer.put(5, 1)
        buffer.put(6, 1)
        buffer.putShort(0x10, 3)
        buffer.putShort(0x12, 0xB7)
        buffer.putInt(0x14, 1)
        buffer.putLong(0x20, PHOFF)
        buffer.putShort(0x34, 64)
        buffer.putShort(0x36, PHENTSIZE.toShort())
        buffer.putShort(0x38, 2)

        putProgramHeader(
            buffer,
            index = 0,
            type = PT_LOAD,
            offset = STRING_TABLE_OFFSET,
            vaddr = STRING_TABLE_OFFSET,
            fileSize = STRING_SEGMENT_SIZE,
            memSize = STRING_SEGMENT_SIZE,
        )
        putProgramHeader(
            buffer,
            index = 1,
            type = PT_DYNAMIC,
            offset = DYNAMIC_OFFSET,
            vaddr = DYNAMIC_OFFSET,
            fileSize = DYNAMIC_SIZE,
            memSize = DYNAMIC_SIZE,
        )
        // The dynamic array shares its file range with a PT_LOAD, exactly like
        // the shipped libraries, so the patcher can extend both together.
        putProgramHeader(
            buffer,
            index = 2,
            type = PT_LOAD,
            offset = DYNAMIC_OFFSET,
            vaddr = DYNAMIC_OFFSET,
            fileSize = DYNAMIC_SIZE,
            memSize = DYNAMIC_SIZE,
        )
        buffer.putShort(0x38, 3)

        STRING_TABLE.copyInto(image, STRING_TABLE_OFFSET.toInt())

        var slot = DYNAMIC_OFFSET
        NEEDED_INDEXES.forEach { index ->
            buffer.putLong(slot.toInt(), DT_NEEDED)
            buffer.putLong((slot + 8).toInt(), index)
            slot += 16
        }
        buffer.putLong(slot.toInt(), DT_STRTAB)
        buffer.putLong((slot + 8).toInt(), STRING_TABLE_OFFSET)
        slot += 16
        buffer.putLong(slot.toInt(), DT_STRSZ)
        buffer.putLong((slot + 8).toInt(), STRING_TABLE.size.toLong())
        slot += 16
        buffer.putLong(slot.toInt(), DT_NULL)
        buffer.putLong((slot + 8).toInt(), 0L)

        // bionic maps `.dynamic` and `.dynstr` through the section headers and
        // cross-checks the dynamic one against PT_DYNAMIC, so the fixture needs
        // a section table for the patcher to accept it.
        buffer.putLong(0x28, SHOFF)
        buffer.putShort(0x3A, SHENTSIZE.toShort())
        buffer.putShort(0x3C, 3)
        putSectionHeader(
            buffer,
            index = STRING_SECTION_INDEX,
            type = SHT_STRTAB,
            flags = SHF_ALLOC,
            offset = STRING_TABLE_OFFSET,
            size = STRING_TABLE.size.toLong(),
            link = 0,
        )
        putSectionHeader(
            buffer,
            index = 2,
            type = SHT_DYNAMIC,
            flags = SHF_ALLOC,
            offset = DYNAMIC_OFFSET,
            size = DYNAMIC_SIZE,
            link = STRING_SECTION_INDEX,
        )

        return image
    }

    private fun putSectionHeader(
        buffer: ByteBuffer,
        index: Int,
        type: Long,
        flags: Long,
        offset: Long,
        size: Long,
        link: Int,
    ) {
        val base = (SHOFF + index * SHENTSIZE).toInt()
        buffer.putInt(base + 0x04, type.toInt())
        buffer.putLong(base + 0x08, flags)
        buffer.putLong(base + 0x10, offset)
        buffer.putLong(base + 0x18, offset)
        buffer.putLong(base + 0x20, size)
        buffer.putInt(base + 0x28, link)
    }

    private fun putProgramHeader(
        buffer: ByteBuffer,
        index: Int,
        type: Long,
        offset: Long,
        vaddr: Long,
        fileSize: Long,
        memSize: Long,
    ) {
        val base = (PHOFF + index * PHENTSIZE).toInt()
        buffer.putInt(base, type.toInt())
        buffer.putInt(base + 4, 6)
        buffer.putLong(base + 8, offset)
        buffer.putLong(base + 16, vaddr)
        buffer.putLong(base + 24, vaddr)
        buffer.putLong(base + 32, fileSize)
        buffer.putLong(base + 40, memSize)
        buffer.putLong(base + 48, 0x1000L)
    }

    /** Minimal reader used to assert on the patched image. */
    private class Elf(file: File) {
        private val bytes = file.readBytes()
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val headers: List<LongArray>
        private val entries: List<Pair<Long, Long>>
        private val sections: List<LongArray>

        init {
            val shoff = buffer.getLong(0x28)
            val shentsize = buffer.getShort(0x3A).toLong()
            val shnum = buffer.getShort(0x3C).toInt()
            sections = (0 until shnum).map { index ->
                val base = (shoff + index * shentsize).toInt()
                longArrayOf(
                    buffer.getInt(base + 0x04).toLong(),
                    buffer.getLong(base + 0x18),
                    buffer.getLong(base + 0x20),
                )
            }
            val phoff = buffer.getLong(0x20)
            val phentsize = buffer.getShort(0x36).toLong()
            val phnum = buffer.getShort(0x38).toInt()
            headers = (0 until phnum).map { index ->
                val base = (phoff + index * phentsize).toInt()
                longArrayOf(
                    buffer.getInt(base).toLong(),
                    buffer.getLong(base + 8),
                    buffer.getLong(base + 16),
                    buffer.getLong(base + 32),
                    buffer.getLong(base + 40),
                )
            }
            val dynamic = headers.first { it[0] == PT_DYNAMIC }
            val collected = ArrayList<Pair<Long, Long>>()
            var cursor = dynamic[1]
            while (cursor < dynamic[1] + dynamic[3]) {
                val tag = buffer.getLong(cursor.toInt())
                collected += tag to buffer.getLong((cursor + 8).toInt())
                cursor += 16
            }
            entries = collected
        }

        fun tagValue(tag: Long): Long? = entries.firstOrNull { it.first == tag }?.second

        fun lastTag(): Long = entries.last().first

        fun dynamicFileSize(): Long = headers.first { it[0] == PT_DYNAMIC }[3]

        fun dynamicMemSize(): Long = headers.first { it[0] == PT_DYNAMIC }[4]

        fun dynamicLoadFileSize(): Long {
            val dynamic = headers.first { it[0] == PT_DYNAMIC }
            return headers.first { it[0] == PT_LOAD && it[2] == dynamic[2] }[3]
        }

        fun sectionSize(type: Long): Long = sections.first { it[0] == type }[2]

        fun runpath(): String? = tagValue(DT_RUNPATH)?.let(::string)

        fun needed(): List<String> =
            entries.filter { it.first == DT_NEEDED }.map { string(it.second) }

        private fun string(index: Long): String {
            val base = (tagValue(DT_STRTAB)!! + index).toInt()
            var end = base
            while (bytes[end] != 0.toByte()) {
                end++
            }
            return String(bytes, base, end - base, Charsets.US_ASCII)
        }
    }

    private companion object {
        const val PHOFF = 0x40L
        const val PHENTSIZE = 56L
        const val PT_LOAD = 1L
        const val PT_DYNAMIC = 2L
        const val DT_NULL = 0L
        const val DT_NEEDED = 1L
        const val DT_STRTAB = 5L
        const val DT_STRSZ = 10L
        const val DT_RUNPATH = 29L

        const val STRING_TABLE_OFFSET = 0x1000L
        /** Padding after `.dynstr` is what the injected string is written into. */
        const val STRING_SEGMENT_SIZE = 0x100L
        const val DYNAMIC_OFFSET = 0x2000L
        const val FILE_SIZE = 0x3000

        const val SHOFF = 0x2800L
        const val SHENTSIZE = 64L
        const val SHT_STRTAB = 3L
        const val SHT_DYNAMIC = 6L
        const val SHF_ALLOC = 0x2L

        /** Index 0 is the reserved SHN_UNDEF entry, so `.dynstr` lands at 1. */
        const val STRING_SECTION_INDEX = 1

        val RUNPATH = "\$ORIGIN\u0000".toByteArray(Charsets.US_ASCII)
        val NEEDED = arrayOf("libeasytier_ffi.so", "liblog.so")
        val STRING_TABLE: ByteArray =
            byteArrayOf(0) + NEEDED.joinToString("\u0000", postfix = "\u0000")
                .toByteArray(Charsets.US_ASCII)
        val NEEDED_INDEXES = listOf(1L, 1L + NEEDED[0].length + 1)
        val DYNAMIC_SIZE = ((NEEDED.size + 3) * 16).toLong()
    }
}
