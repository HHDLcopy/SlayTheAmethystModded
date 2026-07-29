package io.stamethyst.backend.easytier

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Repairs staged EasyTier ELF shared objects so bionic can resolve their
 * `DT_NEEDED` siblings from the private staging directory.
 *
 * Background: `libeasytier_ffi.so` ships without a `DT_SONAME`. Since API 23
 * bionic no longer falls back to the file basename, so a library loaded through
 * `System.load(<absolute path>)` is registered with an empty soname and can
 * never satisfy another library's `DT_NEEDED "libeasytier_ffi.so"` entry.
 * `libeasytier_android_jni.so` declares exactly that dependency, so once the
 * prebuilt libraries are shipped outside the APK (and are therefore absent from
 * the APK's `nativeLibraryDir`, the only directory on the app namespace search
 * path) the linker reports `library "libeasytier_ffi.so" not found`.
 *
 * The repair injects `DT_RUNPATH=$ORIGIN` into the dependent library. bionic
 * expands `$ORIGIN` to `dirname(realpath)` and consults the needing library's
 * `DT_RUNPATH` when opening a `DT_NEEDED` entry, which resolves to the sibling
 * copy in the staging directory. The already loaded copy is then de-duplicated
 * by device/inode, so no library is mapped twice.
 *
 * The edit only rewrites bytes the dynamic linker actually reads and fits
 * entirely inside existing alignment padding, so the file size never changes.
 */
internal object EasyTierNativeElfPatcher {
    private const val EI_CLASS = 4
    private const val EI_DATA = 5
    private const val ELF_CLASS_64 = 2
    private const val ELF_DATA_LSB = 1
    private const val ELF_HEADER_MIN_SIZE = 64L

    private const val E_PHOFF = 0x20L
    private const val E_PHENTSIZE = 0x36L
    private const val E_PHNUM = 0x38L
    private const val E_SHOFF = 0x28L
    private const val E_SHENTSIZE = 0x3AL
    private const val E_SHNUM = 0x3CL

    private const val SH_TYPE = 0x04L
    private const val SH_FLAGS = 0x08L
    private const val SH_OFFSET = 0x18L
    private const val SH_SIZE = 0x20L
    private const val SH_LINK = 0x28L
    private const val SECTION_HEADER_MIN_SIZE = 64

    private const val SHT_STRTAB = 3L
    private const val SHT_NOBITS = 8L
    private const val SHT_DYNAMIC = 6L
    private const val SHF_ALLOC = 0x2L

    private const val PT_LOAD = 1L
    private const val PT_DYNAMIC = 2L

    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L
    private const val DT_STRSZ = 10L
    private const val DT_RPATH = 15L
    private const val DT_RUNPATH = 29L

    private const val DYNAMIC_ENTRY_SIZE = 16L
    private const val MAX_DYNAMIC_ENTRIES = 4096

    /** Written into `.dynstr` padding, NUL terminated, so eight bytes are required. */
    private val RUNPATH_BYTES = "\$ORIGIN".toByteArray(Charsets.US_ASCII) + 0

    /**
     * Ensures [library] can locate the given [siblingNames] next to itself.
     *
     * Returns `true` when the file was modified. Returns `false` when no change
     * is required or when the ELF layout leaves no room for the edit; in that
     * case the subsequent `dlopen` failure is surfaced verbatim to the caller
     * instead of being masked here.
     */
    fun ensureSiblingDependencyLookup(library: File, siblingNames: Set<String>): Boolean {
        if (siblingNames.isEmpty()) {
            return false
        }
        return try {
            RandomAccessFile(library, "rw").use { handle ->
                patch(handle, siblingNames)
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun patch(handle: RandomAccessFile, siblingNames: Set<String>): Boolean {
        if (handle.length() < ELF_HEADER_MIN_SIZE || !isLittleEndianElf64(handle)) {
            return false
        }

        val programHeaders = readProgramHeaders(handle) ?: return false
        val dynamic = programHeaders.firstOrNull { it.type == PT_DYNAMIC } ?: return false
        val loads = programHeaders.filter { it.type == PT_LOAD }
        if (loads.isEmpty()) {
            return false
        }

        val entries = readDynamicEntries(handle, dynamic) ?: return false
        // An existing search path is either already correct or was authored
        // upstream; either way this repair must not clobber it.
        if (entries.any { it.tag == DT_RUNPATH || it.tag == DT_RPATH }) {
            return false
        }

        val stringTableVaddr = entries.firstOrNull { it.tag == DT_STRTAB }?.value ?: return false
        val stringTableSize = entries.firstOrNull { it.tag == DT_STRSZ }?.value ?: return false
        val stringTableOffset = fileOffsetOf(loads, stringTableVaddr) ?: return false

        // bionic reads `.dynamic` and `.dynstr` through the section headers and
        // rejects the library outright when their sizes disagree with the
        // program headers, so both descriptions have to be grown together.
        val sections = readSectionHeaders(handle) ?: return false
        val dynamicSection = sections.firstOrNull { it.type == SHT_DYNAMIC } ?: return false
        if (dynamicSection.offset != dynamic.offset || dynamicSection.size != dynamic.fileSize) {
            return false
        }
        val stringSection = sections.getOrNull(dynamicSection.link) ?: return false
        if (stringSection.type != SHT_STRTAB ||
            stringSection.offset != stringTableOffset ||
            stringSection.size != stringTableSize
        ) {
            return false
        }

        val needed = entries.filter { it.tag == DT_NEEDED }
            .mapNotNull { readString(handle, stringTableOffset, stringTableSize, it.value) }
        if (needed.none(siblingNames::contains)) {
            return false
        }

        val terminator = entries.lastOrNull() ?: return false
        if (terminator.tag != DT_NULL) {
            return false
        }

        // The new string goes into the padding that follows `.dynstr`, and the
        // extra dynamic entry consumes the padding that follows `PT_DYNAMIC`.
        val stringOffset = stringTableOffset + stringTableSize
        val stringSegment = loads.firstOrNull { it.contains(stringTableVaddr) } ?: return false
        val stringRoom = stringSegment.offset + stringSegment.fileSize - stringOffset
        if (stringRoom < RUNPATH_BYTES.size) {
            return false
        }
        val dynamicEnd = dynamic.offset + dynamic.fileSize
        if (!isZeroFilled(handle, stringOffset, RUNPATH_BYTES.size.toLong()) ||
            !isZeroFilled(handle, dynamicEnd, DYNAMIC_ENTRY_SIZE)
        ) {
            return false
        }

        val dynamicSegment = loads.firstOrNull { it.contains(dynamic.vaddr) } ?: return false
        // Only grow a segment whose mapped tail is exactly the dynamic section,
        // otherwise the extra entry would overlap unrelated mapped content.
        if (dynamicSegment.offset + dynamicSegment.fileSize != dynamicEnd ||
            dynamicSegment.vaddr + dynamicSegment.memSize != dynamic.vaddr + dynamic.memSize
        ) {
            return false
        }

        // Refuse to claim bytes that belong to a mapped section; only the
        // linker-invisible tail padding may be reused.
        if (overlapsAllocatedSection(sections, stringOffset, RUNPATH_BYTES.size.toLong()) ||
            overlapsAllocatedSection(sections, dynamicEnd, DYNAMIC_ENTRY_SIZE)
        ) {
            return false
        }

        writeBytes(handle, stringOffset, RUNPATH_BYTES)
        writeDynamicEntry(handle, terminator.offset, DT_RUNPATH, stringTableSize)
        writeDynamicEntry(handle, dynamicEnd, DT_NULL, 0L)
        writeDynamicEntry(
            handle,
            entries.first { it.tag == DT_STRSZ }.offset,
            DT_STRSZ,
            stringTableSize + RUNPATH_BYTES.size,
        )
        growSegment(handle, dynamic, DYNAMIC_ENTRY_SIZE)
        growSegment(handle, dynamicSegment, DYNAMIC_ENTRY_SIZE)
        // bionic maps `.dynamic` and `.dynstr` using these sizes and validates
        // the dynamic one against PT_DYNAMIC, so both must track the growth.
        growSection(handle, dynamicSection, DYNAMIC_ENTRY_SIZE)
        growSection(handle, stringSection, RUNPATH_BYTES.size.toLong())
        handle.fd.sync()
        return true
    }

    private fun isLittleEndianElf64(handle: RandomAccessFile): Boolean {
        val identifier = ByteArray(16)
        handle.seek(0L)
        handle.readFully(identifier)
        return identifier[0] == 0x7F.toByte() &&
            identifier[1] == 'E'.code.toByte() &&
            identifier[2] == 'L'.code.toByte() &&
            identifier[3] == 'F'.code.toByte() &&
            identifier[EI_CLASS].toInt() == ELF_CLASS_64 &&
            identifier[EI_DATA].toInt() == ELF_DATA_LSB
    }

    private fun readProgramHeaders(handle: RandomAccessFile): List<ProgramHeader>? {
        val tableOffset = readLong(handle, E_PHOFF)
        val entrySize = readShort(handle, E_PHENTSIZE)
        val entryCount = readShort(handle, E_PHNUM)
        if (tableOffset <= 0L || entrySize < 56 || entryCount <= 0) {
            return null
        }
        if (tableOffset + entrySize.toLong() * entryCount > handle.length()) {
            return null
        }
        return (0 until entryCount).map { index ->
            val base = tableOffset + index.toLong() * entrySize
            ProgramHeader(
                offset = readLong(handle, base + 8),
                vaddr = readLong(handle, base + 16),
                fileSize = readLong(handle, base + 32),
                memSize = readLong(handle, base + 40),
                type = readInt(handle, base),
                headerOffset = base,
            )
        }
    }

    private fun readSectionHeaders(handle: RandomAccessFile): List<SectionHeader>? {
        val tableOffset = readLong(handle, E_SHOFF)
        val entrySize = readShort(handle, E_SHENTSIZE)
        val entryCount = readShort(handle, E_SHNUM)
        if (tableOffset <= 0L || entrySize < SECTION_HEADER_MIN_SIZE || entryCount <= 0) {
            return null
        }
        if (tableOffset + entrySize.toLong() * entryCount > handle.length()) {
            return null
        }
        return (0 until entryCount).map { index ->
            val base = tableOffset + index.toLong() * entrySize
            SectionHeader(
                type = readInt(handle, base + SH_TYPE),
                flags = readLong(handle, base + SH_FLAGS),
                offset = readLong(handle, base + SH_OFFSET),
                size = readLong(handle, base + SH_SIZE),
                link = readInt(handle, base + SH_LINK).toInt(),
                headerOffset = base,
            )
        }
    }

    private fun readDynamicEntries(
        handle: RandomAccessFile,
        dynamic: ProgramHeader,
    ): List<DynamicEntry>? {
        if (dynamic.fileSize < DYNAMIC_ENTRY_SIZE ||
            dynamic.offset + dynamic.fileSize > handle.length()
        ) {
            return null
        }
        val capacity = minOf(dynamic.fileSize / DYNAMIC_ENTRY_SIZE, MAX_DYNAMIC_ENTRIES.toLong())
        val entries = ArrayList<DynamicEntry>()
        for (index in 0 until capacity) {
            val offset = dynamic.offset + index * DYNAMIC_ENTRY_SIZE
            val entry = DynamicEntry(
                tag = readLong(handle, offset),
                value = readLong(handle, offset + 8),
                offset = offset,
            )
            entries.add(entry)
            if (entry.tag == DT_NULL) {
                return entries
            }
        }
        return null
    }

    private fun readString(
        handle: RandomAccessFile,
        tableOffset: Long,
        tableSize: Long,
        index: Long,
    ): String? {
        if (index < 0L || index >= tableSize) {
            return null
        }
        val limit = minOf(tableSize - index, 256L).toInt()
        val buffer = ByteArray(limit)
        handle.seek(tableOffset + index)
        handle.readFully(buffer)
        val end = buffer.indexOf(0)
        if (end <= 0) {
            return null
        }
        return String(buffer, 0, end, Charsets.US_ASCII)
    }

    private fun fileOffsetOf(loads: List<ProgramHeader>, vaddr: Long): Long? =
        loads.firstOrNull { it.contains(vaddr) }?.let { it.offset + (vaddr - it.vaddr) }

    private fun isZeroFilled(handle: RandomAccessFile, offset: Long, length: Long): Boolean {
        if (offset < 0L || offset + length > handle.length()) {
            return false
        }
        val buffer = ByteArray(length.toInt())
        handle.seek(offset)
        handle.readFully(buffer)
        return buffer.all { it == 0.toByte() }
    }

    /**
     * True when [offset] intersects a section the linker maps. Sections without
     * `SHF_ALLOC` (`.comment`, `.shstrtab`) are never mapped, so their bytes may
     * be reused as long as they are unused padding.
     */
    private fun overlapsAllocatedSection(
        sections: List<SectionHeader>,
        offset: Long,
        length: Long,
    ): Boolean = sections.any { section ->
        section.isAllocated &&
            section.type != SHT_NOBITS &&
            section.size > 0L &&
            offset < section.offset + section.size &&
            section.offset < offset + length
    }

    private fun growSegment(handle: RandomAccessFile, header: ProgramHeader, delta: Long) {
        writeLong(handle, header.headerOffset + 32, header.fileSize + delta)
        writeLong(handle, header.headerOffset + 40, header.memSize + delta)
    }

    private fun growSection(handle: RandomAccessFile, header: SectionHeader, delta: Long) {
        writeLong(handle, header.headerOffset + SH_SIZE, header.size + delta)
    }

    private fun writeDynamicEntry(
        handle: RandomAccessFile,
        offset: Long,
        tag: Long,
        value: Long,
    ) {
        writeLong(handle, offset, tag)
        writeLong(handle, offset + 8, value)
    }

    private fun writeBytes(handle: RandomAccessFile, offset: Long, bytes: ByteArray) {
        handle.seek(offset)
        handle.write(bytes)
    }

    private fun readShort(handle: RandomAccessFile, offset: Long): Int {
        val buffer = ByteArray(2)
        handle.seek(offset)
        handle.readFully(buffer)
        return (buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)
    }

    private fun readInt(handle: RandomAccessFile, offset: Long): Long {
        val buffer = ByteArray(4)
        handle.seek(offset)
        handle.readFully(buffer)
        var value = 0L
        for (index in 3 downTo 0) {
            value = (value shl 8) or (buffer[index].toLong() and 0xFF)
        }
        return value
    }

    private fun readLong(handle: RandomAccessFile, offset: Long): Long {
        val buffer = ByteArray(8)
        handle.seek(offset)
        handle.readFully(buffer)
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (buffer[index].toLong() and 0xFF)
        }
        return value
    }

    private fun writeLong(handle: RandomAccessFile, offset: Long, value: Long) {
        val buffer = ByteArray(8)
        for (index in 0 until 8) {
            buffer[index] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
        writeBytes(handle, offset, buffer)
    }

    private data class ProgramHeader(
        val offset: Long,
        val vaddr: Long,
        val fileSize: Long,
        val memSize: Long,
        val type: Long,
        val headerOffset: Long,
    ) {
        fun contains(address: Long): Boolean = address >= vaddr && address < vaddr + fileSize
    }

    private data class SectionHeader(
        val type: Long,
        val flags: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val headerOffset: Long,
    ) {
        val isAllocated: Boolean get() = (flags and SHF_ALLOC) != 0L
    }

    private data class DynamicEntry(val tag: Long, val value: Long, val offset: Long)
}
