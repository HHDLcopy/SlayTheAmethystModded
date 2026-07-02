package io.stamethyst.backend.mods

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.HashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object MtsLoaderCrashPatcher {
    private const val LOADER_CLASS_ENTRY = "com/evacipated/cardcrawl/modthespire/Loader.class"
    private const val PACKAGE_JAR_CLASS_ENTRY = "com/evacipated/cardcrawl/modthespire/PackageJar.class"
    private const val PREPACKAGED_LAUNCHER_CLASS_ENTRY =
        "com/evacipated/cardcrawl/modthespire/PackageJar\$PrepackagedLauncher.class"
    private const val RUN_MODS_METHOD_NAME = "runMods"
    private const val RUN_MODS_METHOD_DESC = "([Ljava/io/File;)V"
    private const val CLOSE_WINDOW_METHOD_NAME = "closeWindow"
    private const val CLOSE_WINDOW_METHOD_DESC = "()V"
    private const val MAIN_METHOD_NAME = "main"
    private const val MAIN_METHOD_DESC = "([Ljava/lang/String;)V"
    private const val BUST_ENUMS_METHOD_NAME = "bustEnums"
    private const val BUST_ENUMS_METHOD_DESC = "()V"
    private const val FIND_ENTRIES_METHOD_NAME = "findEntries"
    private const val FIND_ENTRIES_METHOD_DESC =
        "(Lcom/evacipated/cardcrawl/modthespire/PackageJar\$Entries;Ljava/io/InputStream;Ljava/util/function/Function;)V"
    private const val COPY_JAR_CONTENTS_METHOD_NAME = "copyJarContents"
    private const val COPY_JAR_CONTENTS_STREAM_METHOD_DESC =
        "(Ljava/util/jar/JarOutputStream;Lcom/evacipated/cardcrawl/modthespire/PackageJar\$Entries;Ljava/io/InputStream;Ljava/lang/String;Lcom/evacipated/cardcrawl/modthespire/PackageJar\$Entry\$Type;)V"
    private const val PACKAGE_JAR_METHOD_NAME = "packageJar"
    private const val PACKAGE_JAR_METHOD_DESC =
        "(Lcom/evacipated/cardcrawl/modthespire/MTSClassPool;Ljava/lang/String;)V"
    private const val MTS_CLASS_POOL_OWNER = "com/evacipated/cardcrawl/modthespire/MTSClassPool"
    private const val GET_OUT_JAR_CLASSES_METHOD_NAME = "getOutJarClasses"
    private const val GET_OUT_JAR_CLASSES_METHOD_DESC = "()Ljava/util/Set;"
    private const val FILE_LIST_OVERRIDE_CLASS = "io/stamethyst/bridge/MtsModFileListOverride"
    private const val FILE_LIST_OVERRIDE_METHOD_NAME = "resolve"
    private const val FILE_LIST_OVERRIDE_METHOD_DESC = "([Ljava/io/File;)[Ljava/io/File;"
    private const val PATCH_CACHE_BOOTSTRAP_CLASS = "io/stamethyst/bridge/MtsPatchCacheBootstrap"
    private const val PATCH_CACHE_BOOTSTRAP_METHOD_NAME = "launchIfCurrent"
    private const val PATCH_CACHE_BOOTSTRAP_METHOD_DESC = "()Z"
    private const val PATCH_CACHE_PREPARE_PACKAGE_URLS_METHOD_NAME = "preparePrepackagedPackageUrls"
    private const val PATCH_CACHE_PREPARE_PACKAGE_URLS_METHOD_DESC = "()V"
    private const val PATCH_CACHE_PREPARE_METHOD_NAME = "preparePrepackagedLaunch"
    private const val PATCH_CACHE_PREPARE_METHOD_DESC = "()V"
    private const val PATCH_CACHE_BUST_ENUMS_FROM_CACHE_METHOD_NAME = "bustPrepackagedEnumsFromCache"
    private const val PATCH_CACHE_BUST_ENUMS_FROM_CACHE_METHOD_DESC = "()Z"
    private const val CALL_INITIALIZERS_METHOD_NAME = "callInitializers"
    private const val CALL_INITIALIZERS_METHOD_DESC = "()V"
    private const val PATCH_CACHE_STORE_CLASS = "io/stamethyst/bridge/MtsPatchCacheStore"
    private const val PATCH_CACHE_STORE_METHOD_NAME = "store"
    private const val LEGACY_PATCH_CACHE_STORE_METHOD_DESC = "(Ljava/lang/Object;)V"
    private const val PATCH_CACHE_STORE_METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V"
    private const val PATCH_CACHE_BEGIN_COMPILE_CAPTURE_METHOD_NAME = "beginCompileCapture"
    private const val PATCH_CACHE_BEGIN_COMPILE_CAPTURE_METHOD_DESC = "()V"
    private const val PATCH_CACHE_FINISH_COMPILE_CAPTURE_METHOD_NAME = "finishCompileCapture"
    private const val PATCH_CACHE_FINISH_COMPILE_CAPTURE_METHOD_DESC = "()V"
    private const val GET_MODIFIED_CLASSES_METHOD_NAME = "getModifiedClasses"
    private const val GET_MODIFIED_CLASSES_METHOD_DESC = "()Ljava/util/Set;"
    private const val COMPILE_PATCHES_METHOD_OWNER = "com/evacipated/cardcrawl/modthespire/Patcher"
    private const val COMPILE_PATCHES_METHOD_NAME = "compilePatches"
    private const val COMPILE_PATCHES_METHOD_DESC =
        "(Lcom/evacipated/cardcrawl/modthespire/MTSClassLoader;Lcom/evacipated/cardcrawl/modthespire/MTSClassPool;)Ljavassist/ClassPath;"
    private const val AMETHYST_PATCH_MARKER_METHOD_NAME = "amethyst\$loaderPatchV2"
    private const val AMETHYST_PATCH_MARKER_METHOD_DESC = "()I"
    private const val AMETHYST_PACKAGE_PATCH_MARKER_METHOD_NAME = "amethyst\$packagePatchV2"
    private const val AMETHYST_PACKAGE_PATCH_MARKER_METHOD_DESC = "()I"
    private const val AMETHYST_PREPACKAGED_PATCH_MARKER_METHOD_NAME_V2 = "amethyst\$prepackagedLauncherPatchV2"
    private const val AMETHYST_PREPACKAGED_PATCH_MARKER_METHOD_NAME_V3 = "amethyst\$prepackagedLauncherPatchV3"
    private const val AMETHYST_PREPACKAGED_PATCH_MARKER_METHOD_NAME = "amethyst\$prepackagedLauncherPatchV4"
    private const val AMETHYST_PREPACKAGED_PATCH_MARKER_METHOD_DESC = "()I"
    private const val LOADER_OWNER = "com/evacipated/cardcrawl/modthespire/Loader"
    private const val MOD_SELECT_WINDOW_DESC =
        "Lcom/evacipated/cardcrawl/modthespire/ui/ModSelectWindow;"
    private const val PREPACKAGED_LAUNCHER_OWNER =
        "com/evacipated/cardcrawl/modthespire/PackageJar\$PrepackagedLauncher"
    private const val DESKTOP_LAUNCHER_OWNER = "com/megacrit/cardcrawl/desktop/DesktopLauncher"
    private const val PACKAGE_FIELD_NAME = "PACKAGE"
    private const val PACKAGE_FIELD_DESC = "Z"
    private const val LOADER_WINDOW_FIELD_NAME = "ex"

    private const val AMETHYST_PATCH_MARKER_METHOD_NAME_V3 = "amethyst\$loaderPatchV3"
    private const val AMETHYST_PATCH_MARKER_METHOD_NAME_V4 = "amethyst\$loaderPatchV4"

    private val SWALLOWED_FAILURE_TYPES = setOf(
        "com/evacipated/cardcrawl/modthespire/MissingDependencyException",
        "com/evacipated/cardcrawl/modthespire/DuplicateModIDException",
        "com/evacipated/cardcrawl/modthespire/MissingModIDException",
        "java/lang/Exception"
    )

    @Throws(IOException::class)
    fun ensurePatchedMtsJar(mtsJar: File?): Boolean {
        if (mtsJar == null || !mtsJar.isFile) {
            throw IOException("ModTheSpire.jar not found")
        }

        val originalLoaderBytes = JarFileIoUtils.readJarEntryBytes(mtsJar, LOADER_CLASS_ENTRY)
            ?: throw IOException("Invalid ModTheSpire.jar: missing $LOADER_CLASS_ENTRY")
        val originalPackageJarBytes = JarFileIoUtils.readJarEntryBytes(mtsJar, PACKAGE_JAR_CLASS_ENTRY)
            ?: throw IOException("Invalid ModTheSpire.jar: missing $PACKAGE_JAR_CLASS_ENTRY")
        val originalPrepackagedLauncherBytes = JarFileIoUtils.readJarEntryBytes(mtsJar, PREPACKAGED_LAUNCHER_CLASS_ENTRY)
            ?: throw IOException("Invalid ModTheSpire.jar: missing $PREPACKAGED_LAUNCHER_CLASS_ENTRY")
        val patchedLoaderBytes = patchLoaderBytes(originalLoaderBytes)
        val patchedPackageJarBytes = patchPackageJarBytes(originalPackageJarBytes)
        val patchedPrepackagedLauncherBytes = patchPrepackagedLauncherBytes(originalPrepackagedLauncherBytes)
        if (patchedLoaderBytes.contentEquals(originalLoaderBytes) &&
            patchedPackageJarBytes.contentEquals(originalPackageJarBytes) &&
            patchedPrepackagedLauncherBytes.contentEquals(originalPrepackagedLauncherBytes)
        ) {
            return false
        }

        val tempJar = File(mtsJar.absolutePath + ".patching.tmp")
        val seenNames = HashSet<String>()
        FileInputStream(mtsJar).use { fileInput ->
            ZipInputStream(fileInput).use { zipIn ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        while (true) {
                            val entry = zipIn.nextEntry ?: break
                            val name = entry.name
                            if (entry.isDirectory || !seenNames.add(name)) {
                                zipIn.closeEntry()
                                continue
                            }
                            val outEntry = ZipEntry(name)
                            if (entry.time > 0L) {
                                outEntry.time = entry.time
                            }
                            zipOut.putNextEntry(outEntry)
                            if (name == LOADER_CLASS_ENTRY) {
                                zipOut.write(patchedLoaderBytes)
                            } else if (name == PACKAGE_JAR_CLASS_ENTRY) {
                                zipOut.write(patchedPackageJarBytes)
                            } else if (name == PREPACKAGED_LAUNCHER_CLASS_ENTRY) {
                                zipOut.write(patchedPrepackagedLauncherBytes)
                            } else {
                                JarFileIoUtils.copyStream(zipIn, zipOut)
                            }
                            zipOut.closeEntry()
                            zipIn.closeEntry()
                        }
                    }
                }
            }
        }

        if (!isPatchedLoaderClass(
                JarFileIoUtils.readJarEntryBytes(tempJar, LOADER_CLASS_ENTRY)
                    ?: throw IOException("Patched MTS jar is missing Loader.class")
            )
        ) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("Failed to patch ModTheSpire startup handling and file list override")
        }
        if (!isPatchedPackageJarClass(
                JarFileIoUtils.readJarEntryBytes(tempJar, PACKAGE_JAR_CLASS_ENTRY)
                    ?: throw IOException("Patched MTS jar is missing PackageJar.class")
            )
        ) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("Failed to patch ModTheSpire package cache handling")
        }
        if (!isPatchedPrepackagedLauncherClass(
                JarFileIoUtils.readJarEntryBytes(tempJar, PREPACKAGED_LAUNCHER_CLASS_ENTRY)
                    ?: throw IOException("Patched MTS jar is missing PackageJar\$PrepackagedLauncher.class")
            )
        ) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("Failed to patch ModTheSpire prepackaged launcher cache handling")
        }

        if (mtsJar.exists() && !mtsJar.delete()) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("Failed to replace ${mtsJar.absolutePath}")
        }
        if (!tempJar.renameTo(mtsJar)) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("Failed to move ${tempJar.absolutePath} -> ${mtsJar.absolutePath}")
        }
        mtsJar.setLastModified(System.currentTimeMillis())
        return true
    }

    internal fun isPatchedLoaderClass(loaderBytes: ByteArray): Boolean {
        val classNode = readClassNode(loaderBytes)
        val runModsMethod = classNode.methods.firstOrNull { method ->
            method.name == RUN_MODS_METHOD_NAME && method.desc == RUN_MODS_METHOD_DESC
        } ?: return false
        val startupFailuresAreNotSwallowed = runModsMethod.tryCatchBlocks.none { tryCatch ->
            SWALLOWED_FAILURE_TYPES.contains(tryCatch.type)
        }
        return startupFailuresAreNotSwallowed &&
            hasFileListOverrideCall(runModsMethod) &&
            hasPatchCacheLaunchHook(runModsMethod) &&
            hasOutJarPrimingHook(runModsMethod) &&
            hasPatchCacheStoreHook(runModsMethod) &&
            hasCloseWindowNullGuard(classNode) &&
            hasCurrentPatchMarker(classNode)
    }

    internal fun hasPatchCacheLaunchHook(loaderBytes: ByteArray): Boolean {
        val runModsMethod = readRunModsMethod(loaderBytes) ?: return false
        return hasPatchCacheLaunchHook(runModsMethod)
    }

    internal fun hasPatchCacheStoreHook(loaderBytes: ByteArray): Boolean {
        val runModsMethod = readRunModsMethod(loaderBytes) ?: return false
        return hasPatchCacheStoreHook(runModsMethod)
    }

    internal fun hasPatchCacheStoreHookWithCompiledClassPathArg(loaderBytes: ByteArray): Boolean {
        val runModsMethod = readRunModsMethod(loaderBytes) ?: return false
        val iterator = runModsMethod.instructions.iterator()
        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == PATCH_CACHE_STORE_CLASS &&
                instruction.name == PATCH_CACHE_STORE_METHOD_NAME &&
                instruction.desc == PATCH_CACHE_STORE_METHOD_DESC
            ) {
                val compiledClassPathArg = instruction.previous as? VarInsnNode ?: return false
                val classPoolArg = compiledClassPathArg.previous as? VarInsnNode ?: return false
                return classPoolArg.opcode == Opcodes.ALOAD &&
                    classPoolArg.`var` == 3 &&
                    compiledClassPathArg.opcode == Opcodes.ALOAD &&
                    compiledClassPathArg.`var` == 4
            }
        }
        return false
    }

    internal fun hasOutJarPrimingHook(loaderBytes: ByteArray): Boolean {
        val runModsMethod = readRunModsMethod(loaderBytes) ?: return false
        return hasOutJarPrimingHook(runModsMethod)
    }

    internal fun hasCloseWindowNullGuard(loaderBytes: ByteArray): Boolean {
        return hasCloseWindowNullGuard(readClassNode(loaderBytes))
    }

    internal fun isPatchedPackageJarClass(packageJarBytes: ByteArray): Boolean {
        return hasPackagePatchMarker(readClassNode(packageJarBytes))
    }

    internal fun isPatchedPrepackagedLauncherClass(prepackagedLauncherBytes: ByteArray): Boolean {
        val classNode = readClassNode(prepackagedLauncherBytes)
        val mainMethod = classNode.methods.firstOrNull { method ->
            method.name == MAIN_METHOD_NAME && method.desc == MAIN_METHOD_DESC
        } ?: return false
        return hasPrepackagedPackageUrlsHook(mainMethod) &&
            hasPrepackagedPrepareHook(mainMethod) &&
            hasPrepackagedEnumCacheHook(mainMethod) &&
            hasCallInitializersCall(mainMethod) &&
            hasPrepackagedPatchMarker(classNode)
    }

    internal fun patchLoaderBytes(loaderBytes: ByteArray): ByteArray {
        val classNode = readClassNode(loaderBytes)
        val runModsMethod = classNode.methods.firstOrNull { method ->
            method.name == RUN_MODS_METHOD_NAME && method.desc == RUN_MODS_METHOD_DESC
        } ?: throw IOException("Unsupported ModTheSpire.jar: Loader.runMods(File[]) not found")
        val closeWindowMethod = classNode.methods.firstOrNull { method ->
            method.name == CLOSE_WINDOW_METHOD_NAME && method.desc == CLOSE_WINDOW_METHOD_DESC
        } ?: throw IOException("Unsupported ModTheSpire.jar: Loader.closeWindow() not found")

        val originalCatchCount = runModsMethod.tryCatchBlocks.size
        runModsMethod.tryCatchBlocks.removeAll { tryCatch ->
            SWALLOWED_FAILURE_TYPES.contains(tryCatch.type)
        }
        val removedSwallowedFailures = runModsMethod.tryCatchBlocks.size != originalCatchCount
        val alreadyOverridesFileList = hasFileListOverrideCall(runModsMethod)
        if (!alreadyOverridesFileList) {
            insertFileListOverride(runModsMethod)
        }
        val alreadyHasPatchCacheLaunchHook = hasPatchCacheLaunchHook(runModsMethod)
        if (!alreadyHasPatchCacheLaunchHook) {
            insertPatchCacheLaunchHook(runModsMethod)
        }
        val alreadyHasOutJarPrimingHook = hasOutJarPrimingHook(runModsMethod)
        if (!alreadyHasOutJarPrimingHook) {
            insertOutJarPrimingHook(runModsMethod)
        }
        val removedLegacyPatchCacheStoreHook =
            removePatchCacheStoreHooks(runModsMethod, LEGACY_PATCH_CACHE_STORE_METHOD_DESC)
        val alreadyHasPatchCacheStoreHook = hasPatchCacheStoreHook(runModsMethod)
        if (!alreadyHasPatchCacheStoreHook) {
            insertPatchCacheStoreHook(runModsMethod)
        }
        val alreadyHasCloseWindowNullGuard = hasCloseWindowNullGuard(classNode)
        if (!alreadyHasCloseWindowNullGuard) {
            insertCloseWindowNullGuard(closeWindowMethod)
        }
        val alreadyHasCurrentPatchMarker = hasCurrentPatchMarker(classNode)
        if (!alreadyHasCurrentPatchMarker) {
            insertCurrentPatchMarker(classNode)
        }
        if (!removedSwallowedFailures &&
            alreadyOverridesFileList &&
            alreadyHasPatchCacheLaunchHook &&
            alreadyHasOutJarPrimingHook &&
            !removedLegacyPatchCacheStoreHook &&
            alreadyHasPatchCacheStoreHook &&
            alreadyHasCloseWindowNullGuard &&
            alreadyHasCurrentPatchMarker
        ) {
            return loaderBytes
        }

        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }

    internal fun patchPackageJarBytes(packageJarBytes: ByteArray): ByteArray {
        val classNode = readClassNode(packageJarBytes)
        if (hasPackagePatchMarker(classNode)) {
            return packageJarBytes
        }

        val findEntriesMethod = classNode.methods.firstOrNull { method ->
            method.name == FIND_ENTRIES_METHOD_NAME && method.desc == FIND_ENTRIES_METHOD_DESC
        } ?: throw IOException("Unsupported ModTheSpire.jar: PackageJar.findEntries not found")
        val copyJarContentsMethod = classNode.methods.firstOrNull { method ->
            method.name == COPY_JAR_CONTENTS_METHOD_NAME &&
                method.desc == COPY_JAR_CONTENTS_STREAM_METHOD_DESC
        } ?: throw IOException("Unsupported ModTheSpire.jar: PackageJar.copyJarContents(InputStream) not found")
        val packageJarMethod = classNode.methods.firstOrNull { method ->
            method.name == PACKAGE_JAR_METHOD_NAME && method.desc == PACKAGE_JAR_METHOD_DESC
        } ?: throw IOException("Unsupported ModTheSpire.jar: PackageJar.packageJar not found")

        insertNullInputReturn(findEntriesMethod, inputStreamLocal = 1)
        insertNullInputReturn(copyJarContentsMethod, inputStreamLocal = 2)
        insertNullSetFallbackAfterGetOutJarClasses(packageJarMethod)
        insertPackagePatchMarker(classNode)

        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }

    internal fun patchPrepackagedLauncherBytes(prepackagedLauncherBytes: ByteArray): ByteArray {
        val classNode = readClassNode(prepackagedLauncherBytes)
        val mainMethod = classNode.methods.firstOrNull { method ->
            method.name == MAIN_METHOD_NAME && method.desc == MAIN_METHOD_DESC
        } ?: throw IOException("Unsupported ModTheSpire.jar: PackageJar.PrepackagedLauncher.main not found")

        val alreadyHasCurrentPatchMarker = hasPrepackagedPatchMarker(classNode)
        val removedLegacyHooks = if (alreadyHasCurrentPatchMarker) {
            false
        } else {
            removePrepackagedBootstrapHooks(mainMethod)
        }
        val alreadyHasPackageUrlsHook = hasPrepackagedPackageUrlsHook(mainMethod)
        if (!alreadyHasPackageUrlsHook) {
            insertPrepackagedPackageUrlsHook(mainMethod)
        }
        val alreadyHasPrepareHook = hasPrepackagedPrepareHook(mainMethod)
        if (!alreadyHasPrepareHook) {
            insertPrepackagedPrepareHook(mainMethod)
        }
        val alreadyHasEnumCacheHook = hasPrepackagedEnumCacheHook(mainMethod)
        if (!alreadyHasEnumCacheHook) {
            insertPrepackagedEnumCacheHook(mainMethod)
        }
        val alreadyHasCallInitializers = hasCallInitializersCall(mainMethod)
        if (!alreadyHasCallInitializers) {
            insertCallInitializersCall(mainMethod)
        }
        if (!alreadyHasCurrentPatchMarker) {
            insertPrepackagedPatchMarker(classNode)
        }
        if (!removedLegacyHooks &&
            alreadyHasPackageUrlsHook &&
            alreadyHasPrepareHook &&
            alreadyHasEnumCacheHook &&
            alreadyHasCallInitializers &&
            alreadyHasCurrentPatchMarker
        ) {
            return prepackagedLauncherBytes
        }

        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }

    private fun insertFileListOverride(runModsMethod: MethodNode) {
        val instructions = InsnList()
        instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
        instructions.add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                FILE_LIST_OVERRIDE_CLASS,
                FILE_LIST_OVERRIDE_METHOD_NAME,
                FILE_LIST_OVERRIDE_METHOD_DESC,
                false
            )
        )
        instructions.add(VarInsnNode(Opcodes.ASTORE, 0))
        runModsMethod.instructions.insert(instructions)
        runModsMethod.maxStack = maxOf(runModsMethod.maxStack, 1)
    }

    private fun insertPatchCacheLaunchHook(runModsMethod: MethodNode) {
        val continueLabel = LabelNode()
        val instructions = InsnList()
        instructions.add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                PATCH_CACHE_BOOTSTRAP_CLASS,
                PATCH_CACHE_BOOTSTRAP_METHOD_NAME,
                PATCH_CACHE_BOOTSTRAP_METHOD_DESC,
                false
            )
        )
        instructions.add(JumpInsnNode(Opcodes.IFEQ, continueLabel))
        instructions.add(InsnNode(Opcodes.RETURN))
        instructions.add(continueLabel)
        runModsMethod.instructions.insert(instructions)
        runModsMethod.maxStack = maxOf(runModsMethod.maxStack, 1)
    }

    private fun insertPatchCacheStoreHook(runModsMethod: MethodNode) {
        val packageCheck = runModsMethod.instructions.iterator().asSequence().firstOrNull { instruction ->
            instruction is FieldInsnNode &&
                instruction.opcode == Opcodes.GETSTATIC &&
                instruction.owner == LOADER_OWNER &&
                instruction.name == PACKAGE_FIELD_NAME &&
                instruction.desc == PACKAGE_FIELD_DESC
        } ?: throw IOException("Unsupported ModTheSpire.jar: Loader.runMods PACKAGE branch not found")

        val instructions = InsnList()
        instructions.add(VarInsnNode(Opcodes.ALOAD, 3))
        instructions.add(VarInsnNode(Opcodes.ALOAD, 4))
        instructions.add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                PATCH_CACHE_STORE_CLASS,
                PATCH_CACHE_STORE_METHOD_NAME,
                PATCH_CACHE_STORE_METHOD_DESC,
                false
            )
        )
        runModsMethod.instructions.insertBefore(packageCheck, instructions)
        runModsMethod.maxStack = maxOf(runModsMethod.maxStack, 2)
    }

    private fun removePatchCacheStoreHooks(runModsMethod: MethodNode, desc: String): Boolean {
        val toRemove = mutableListOf<org.objectweb.asm.tree.AbstractInsnNode>()
        val iterator = runModsMethod.instructions.iterator()
        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == PATCH_CACHE_STORE_CLASS &&
                instruction.name == PATCH_CACHE_STORE_METHOD_NAME &&
                instruction.desc == desc
            ) {
                val previous = instruction.previous
                if (previous is VarInsnNode && previous.opcode == Opcodes.ALOAD) {
                    toRemove += previous
                }
                toRemove += instruction
            }
        }
        toRemove.forEach { runModsMethod.instructions.remove(it) }
        return toRemove.isNotEmpty()
    }

    private fun insertOutJarPrimingHook(runModsMethod: MethodNode) {
        val compilePatchesCall = runModsMethod.instructions.iterator().asSequence().firstOrNull { instruction ->
            instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == COMPILE_PATCHES_METHOD_OWNER &&
                instruction.name == COMPILE_PATCHES_METHOD_NAME &&
                instruction.desc == COMPILE_PATCHES_METHOD_DESC
        } ?: throw IOException("Unsupported ModTheSpire.jar: Loader.runMods compilePatches call not found")

        val before = InsnList()
        before.add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                PATCH_CACHE_STORE_CLASS,
                PATCH_CACHE_BEGIN_COMPILE_CAPTURE_METHOD_NAME,
                PATCH_CACHE_BEGIN_COMPILE_CAPTURE_METHOD_DESC,
                false
            )
        )
        runModsMethod.instructions.insertBefore(compilePatchesCall, before)

        val after = InsnList()
        after.add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                PATCH_CACHE_STORE_CLASS,
                PATCH_CACHE_FINISH_COMPILE_CAPTURE_METHOD_NAME,
                PATCH_CACHE_FINISH_COMPILE_CAPTURE_METHOD_DESC,
                false
            )
        )
        runModsMethod.instructions.insert(compilePatchesCall, after)
        runModsMethod.maxStack = maxOf(runModsMethod.maxStack, 2)
    }

    private fun insertCurrentPatchMarker(classNode: ClassNode) {
        classNode.methods.removeAll { method ->
            (method.name == AMETHYST_PATCH_MARKER_METHOD_NAME ||
                method.name == AMETHYST_PATCH_MARKER_METHOD_NAME_V3 ||
                method.name == AMETHYST_PATCH_MARKER_METHOD_NAME_V4) &&
                method.desc == AMETHYST_PATCH_MARKER_METHOD_DESC
        }
        val marker = MethodNode(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            AMETHYST_PATCH_MARKER_METHOD_NAME_V4,
            AMETHYST_PATCH_MARKER_METHOD_DESC,
            null,
            null
        )
        marker.instructions.add(InsnNode(Opcodes.ICONST_4))
        marker.instructions.add(InsnNode(Opcodes.IRETURN))
        marker.maxStack = 1
        marker.maxLocals = 0
        classNode.methods.add(marker)
    }

    private fun insertCloseWindowNullGuard(closeWindowMethod: MethodNode) {
        val continueLabel = LabelNode()
        val instructions = InsnList()
        instructions.add(
            FieldInsnNode(
                Opcodes.GETSTATIC,
                LOADER_OWNER,
                LOADER_WINDOW_FIELD_NAME,
                MOD_SELECT_WINDOW_DESC
            )
        )
        instructions.add(JumpInsnNode(Opcodes.IFNONNULL, continueLabel))
        instructions.add(InsnNode(Opcodes.RETURN))
        instructions.add(continueLabel)
        closeWindowMethod.instructions.insert(instructions)
        closeWindowMethod.maxStack = maxOf(closeWindowMethod.maxStack, 1)
    }

    private fun insertPackagePatchMarker(classNode: ClassNode) {
        classNode.methods.removeAll { method ->
            method.name == AMETHYST_PACKAGE_PATCH_MARKER_METHOD_NAME &&
                method.desc == AMETHYST_PACKAGE_PATCH_MARKER_METHOD_DESC
        }
        val marker = MethodNode(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            AMETHYST_PACKAGE_PATCH_MARKER_METHOD_NAME,
            AMETHYST_PACKAGE_PATCH_MARKER_METHOD_DESC,
            null,
            null
        )
        marker.instructions.add(InsnNode(Opcodes.ICONST_1))
        marker.instructions.add(InsnNode(Opcodes.IRETURN))
        marker.maxStack = 1
        marker.maxLocals = 0
        classNode.methods.add(marker)
    }

    private fun insertPrepackagedPatchMarker(classNode: ClassNode) {
        classNode.methods.removeAll { method ->
            (method.name == AMETHYST_PREPACKAGED_PATCH_MARKER_METHOD_NAME ||
                method.name == AMETHYST_PREPACKAGED_PATCH_MARKER_METHOD_NAME_V2 ||
                method.name == AMETHYST_PREPACKAGED_PATCH_MARKER_METHOD_NAME_V3) &&
                method.desc == AMETHYST_PREPACKAGED_PATCH_MARKER_METHOD_DESC
        }
        val marker = MethodNode(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            AMETHYST_PREPACKAGED_PATCH_MARKER_METHOD_NAME,
            AMETHYST_PREPACKAGED_PATCH_MARKER_METHOD_DESC,
            null,
            null
        )
        marker.instructions.add(InsnNode(Opcodes.ICONST_1))
        marker.instructions.add(InsnNode(Opcodes.IRETURN))
        marker.maxStack = 1
        marker.maxLocals = 0
        classNode.methods.add(marker)
    }

    private fun insertPrepackagedPackageUrlsHook(mainMethod: MethodNode) {
        val iterator = mainMethod.instructions.iterator()
        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == PREPACKAGED_LAUNCHER_OWNER &&
                instruction.name == BUST_ENUMS_METHOD_NAME &&
                instruction.desc == BUST_ENUMS_METHOD_DESC
            ) {
                val instructions = InsnList()
                instructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        PATCH_CACHE_BOOTSTRAP_CLASS,
                        PATCH_CACHE_PREPARE_PACKAGE_URLS_METHOD_NAME,
                        PATCH_CACHE_PREPARE_PACKAGE_URLS_METHOD_DESC,
                        false
                    )
                )
                mainMethod.instructions.insertBefore(instruction, instructions)
                return
            }
        }
        throw IOException("Unsupported ModTheSpire.jar: PrepackagedLauncher.bustEnums call not found")
    }

    private fun insertPrepackagedEnumCacheHook(mainMethod: MethodNode) {
        val iterator = mainMethod.instructions.iterator()
        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == PREPACKAGED_LAUNCHER_OWNER &&
                instruction.name == BUST_ENUMS_METHOD_NAME &&
                instruction.desc == BUST_ENUMS_METHOD_DESC
            ) {
                val continueWithOriginalScan = LabelNode()
                val afterOriginalScan = LabelNode()
                val instructions = InsnList()
                instructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        PATCH_CACHE_BOOTSTRAP_CLASS,
                        PATCH_CACHE_BUST_ENUMS_FROM_CACHE_METHOD_NAME,
                        PATCH_CACHE_BUST_ENUMS_FROM_CACHE_METHOD_DESC,
                        false
                    )
                )
                instructions.add(JumpInsnNode(Opcodes.IFEQ, continueWithOriginalScan))
                instructions.add(JumpInsnNode(Opcodes.GOTO, afterOriginalScan))
                instructions.add(continueWithOriginalScan)
                mainMethod.instructions.insertBefore(instruction, instructions)
                mainMethod.instructions.insert(instruction, afterOriginalScan)
                mainMethod.maxStack = maxOf(mainMethod.maxStack, 1)
                return
            }
        }
        throw IOException("Unsupported ModTheSpire.jar: PrepackagedLauncher.bustEnums call not found")
    }

    private fun insertCallInitializersCall(mainMethod: MethodNode) {
        val iterator = mainMethod.instructions.iterator()
        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == DESKTOP_LAUNCHER_OWNER &&
                instruction.name == MAIN_METHOD_NAME &&
                instruction.desc == MAIN_METHOD_DESC
            ) {
                val instructions = InsnList()
                instructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        PREPACKAGED_LAUNCHER_OWNER,
                        CALL_INITIALIZERS_METHOD_NAME,
                        CALL_INITIALIZERS_METHOD_DESC,
                        false
                    )
                )
                mainMethod.instructions.insertBefore(instruction, instructions)
                return
            }
        }
        throw IOException("Unsupported ModTheSpire.jar: PrepackagedLauncher DesktopLauncher.main call not found")
    }

    private fun insertPrepackagedPrepareHook(mainMethod: MethodNode) {
        val iterator = mainMethod.instructions.iterator()
        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == PREPACKAGED_LAUNCHER_OWNER &&
                instruction.name == BUST_ENUMS_METHOD_NAME &&
                instruction.desc == BUST_ENUMS_METHOD_DESC
            ) {
                val instructions = InsnList()
                instructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        PATCH_CACHE_BOOTSTRAP_CLASS,
                        PATCH_CACHE_PREPARE_METHOD_NAME,
                        PATCH_CACHE_PREPARE_METHOD_DESC,
                        false
                    )
                )
                mainMethod.instructions.insertBefore(instruction, instructions)
                return
            }
        }
        throw IOException("Unsupported ModTheSpire.jar: PrepackagedLauncher.bustEnums call not found")
    }

    private fun removePrepackagedBootstrapHooks(mainMethod: MethodNode): Boolean {
        val toRemove = mutableListOf<org.objectweb.asm.tree.AbstractInsnNode>()
        val iterator = mainMethod.instructions.iterator()
        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == PATCH_CACHE_BOOTSTRAP_CLASS &&
                (
                    instruction.name == PATCH_CACHE_PREPARE_PACKAGE_URLS_METHOD_NAME ||
                        instruction.name == PATCH_CACHE_PREPARE_METHOD_NAME ||
                        instruction.name == PATCH_CACHE_BUST_ENUMS_FROM_CACHE_METHOD_NAME
                    )
            ) {
                toRemove.add(instruction)
            }
        }
        toRemove.forEach(mainMethod.instructions::remove)
        return toRemove.isNotEmpty()
    }

    private fun insertNullSetFallbackAfterGetOutJarClasses(method: MethodNode) {
        val iterator = method.instructions.iterator()
        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKEVIRTUAL &&
                instruction.owner == MTS_CLASS_POOL_OWNER &&
                instruction.name == GET_OUT_JAR_CLASSES_METHOD_NAME &&
                instruction.desc == GET_OUT_JAR_CLASSES_METHOD_DESC
            ) {
                val next = instruction.next
                if (next is VarInsnNode && next.opcode == Opcodes.ASTORE) {
                    val continueLabel = LabelNode()
                    val instructions = InsnList()
                    instructions.add(VarInsnNode(Opcodes.ALOAD, next.`var`))
                    instructions.add(JumpInsnNode(Opcodes.IFNONNULL, continueLabel))
                    instructions.add(
                        MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "java/util/Collections",
                            "emptySet",
                            "()Ljava/util/Set;",
                            false
                        )
                    )
                    instructions.add(VarInsnNode(Opcodes.ASTORE, next.`var`))
                    instructions.add(continueLabel)
                    method.instructions.insert(next, instructions)
                    method.maxStack = maxOf(method.maxStack, 1)
                    return
                }
            }
        }
        throw IOException("Unsupported ModTheSpire.jar: PackageJar.getOutJarClasses assignment not found")
    }

    private fun insertNullInputReturn(method: MethodNode, inputStreamLocal: Int) {
        val continueLabel = LabelNode()
        val instructions = InsnList()
        instructions.add(VarInsnNode(Opcodes.ALOAD, inputStreamLocal))
        instructions.add(JumpInsnNode(Opcodes.IFNONNULL, continueLabel))
        instructions.add(InsnNode(Opcodes.RETURN))
        instructions.add(continueLabel)
        method.instructions.insert(instructions)
        method.maxStack = maxOf(method.maxStack, 1)
    }

    private fun hasCurrentPatchMarker(classNode: ClassNode): Boolean {
        return classNode.methods.any { method ->
            method.name == AMETHYST_PATCH_MARKER_METHOD_NAME_V4 &&
                method.desc == AMETHYST_PATCH_MARKER_METHOD_DESC
        }
    }

    private fun hasCloseWindowNullGuard(classNode: ClassNode): Boolean {
        val closeWindowMethod = classNode.methods.firstOrNull { method ->
            method.name == CLOSE_WINDOW_METHOD_NAME && method.desc == CLOSE_WINDOW_METHOD_DESC
        } ?: return false
        val meaningfulInstructions = closeWindowMethod.instructions.iterator().asSequence()
            .filter { instruction -> instruction.opcode >= 0 }
            .take(4)
            .toList()
        if (meaningfulInstructions.size < 3) {
            return false
        }
        val first = meaningfulInstructions[0] as? FieldInsnNode ?: return false
        val second = meaningfulInstructions[1] as? JumpInsnNode ?: return false
        val third = meaningfulInstructions[2] as? InsnNode ?: return false
        return first.opcode == Opcodes.GETSTATIC &&
            first.owner == LOADER_OWNER &&
            first.name == LOADER_WINDOW_FIELD_NAME &&
            first.desc == MOD_SELECT_WINDOW_DESC &&
            second.opcode == Opcodes.IFNONNULL &&
            third.opcode == Opcodes.RETURN
    }

    private fun hasPackagePatchMarker(classNode: ClassNode): Boolean {
        return classNode.methods.any { method ->
            method.name == AMETHYST_PACKAGE_PATCH_MARKER_METHOD_NAME &&
                method.desc == AMETHYST_PACKAGE_PATCH_MARKER_METHOD_DESC
        }
    }

    private fun hasPrepackagedPatchMarker(classNode: ClassNode): Boolean {
        return classNode.methods.any { method ->
            method.name == AMETHYST_PREPACKAGED_PATCH_MARKER_METHOD_NAME &&
                method.desc == AMETHYST_PREPACKAGED_PATCH_MARKER_METHOD_DESC
        }
    }

    private fun hasFileListOverrideCall(runModsMethod: MethodNode): Boolean {
        val iterator = runModsMethod.instructions.iterator()
        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == FILE_LIST_OVERRIDE_CLASS &&
                instruction.name == FILE_LIST_OVERRIDE_METHOD_NAME &&
                instruction.desc == FILE_LIST_OVERRIDE_METHOD_DESC
            ) {
                return true
            }
        }
        return false
    }

    private fun hasPatchCacheLaunchHook(runModsMethod: MethodNode): Boolean {
        return hasStaticCall(
            runModsMethod = runModsMethod,
            owner = PATCH_CACHE_BOOTSTRAP_CLASS,
            name = PATCH_CACHE_BOOTSTRAP_METHOD_NAME,
            desc = PATCH_CACHE_BOOTSTRAP_METHOD_DESC
        )
    }

    private fun hasPatchCacheStoreHook(runModsMethod: MethodNode): Boolean {
        return hasStaticCall(
            runModsMethod = runModsMethod,
            owner = PATCH_CACHE_STORE_CLASS,
            name = PATCH_CACHE_STORE_METHOD_NAME,
            desc = PATCH_CACHE_STORE_METHOD_DESC
        )
    }

    private fun hasOutJarPrimingHook(runModsMethod: MethodNode): Boolean {
        var hasBegin = false
        var hasFinish = false
        val iterator = runModsMethod.instructions.iterator()
        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == PATCH_CACHE_STORE_CLASS
            ) {
                if (instruction.name == PATCH_CACHE_BEGIN_COMPILE_CAPTURE_METHOD_NAME &&
                    instruction.desc == PATCH_CACHE_BEGIN_COMPILE_CAPTURE_METHOD_DESC
                ) {
                    hasBegin = true
                } else if (instruction.name == PATCH_CACHE_FINISH_COMPILE_CAPTURE_METHOD_NAME &&
                    instruction.desc == PATCH_CACHE_FINISH_COMPILE_CAPTURE_METHOD_DESC
                ) {
                    hasFinish = true
                }
            }
        }
        return hasBegin && hasFinish
    }

    internal fun hasPrepackagedPrepareHook(prepackagedLauncherBytes: ByteArray): Boolean {
        val classNode = readClassNode(prepackagedLauncherBytes)
        val mainMethod = classNode.methods.firstOrNull { method ->
            method.name == MAIN_METHOD_NAME && method.desc == MAIN_METHOD_DESC
        } ?: return false
        return hasPrepackagedPrepareHook(mainMethod)
    }

    internal fun hasPrepackagedPackageUrlsHook(prepackagedLauncherBytes: ByteArray): Boolean {
        val classNode = readClassNode(prepackagedLauncherBytes)
        val mainMethod = classNode.methods.firstOrNull { method ->
            method.name == MAIN_METHOD_NAME && method.desc == MAIN_METHOD_DESC
        } ?: return false
        return hasPrepackagedPackageUrlsHook(mainMethod)
    }

    internal fun hasPrepackagedCallInitializersCall(prepackagedLauncherBytes: ByteArray): Boolean {
        val classNode = readClassNode(prepackagedLauncherBytes)
        val mainMethod = classNode.methods.firstOrNull { method ->
            method.name == MAIN_METHOD_NAME && method.desc == MAIN_METHOD_DESC
        } ?: return false
        return hasCallInitializersCall(mainMethod)
    }

    internal fun hasPrepackagedEnumCacheHook(prepackagedLauncherBytes: ByteArray): Boolean {
        val classNode = readClassNode(prepackagedLauncherBytes)
        val mainMethod = classNode.methods.firstOrNull { method ->
            method.name == MAIN_METHOD_NAME && method.desc == MAIN_METHOD_DESC
        } ?: return false
        return hasPrepackagedEnumCacheHook(mainMethod)
    }

    private fun hasPrepackagedPrepareHook(mainMethod: MethodNode): Boolean {
        return hasStaticCall(
            runModsMethod = mainMethod,
            owner = PATCH_CACHE_BOOTSTRAP_CLASS,
            name = PATCH_CACHE_PREPARE_METHOD_NAME,
            desc = PATCH_CACHE_PREPARE_METHOD_DESC
        )
    }

    private fun hasPrepackagedPackageUrlsHook(mainMethod: MethodNode): Boolean {
        return hasStaticCall(
            runModsMethod = mainMethod,
            owner = PATCH_CACHE_BOOTSTRAP_CLASS,
            name = PATCH_CACHE_PREPARE_PACKAGE_URLS_METHOD_NAME,
            desc = PATCH_CACHE_PREPARE_PACKAGE_URLS_METHOD_DESC
        )
    }

    private fun hasPrepackagedEnumCacheHook(mainMethod: MethodNode): Boolean {
        return hasStaticCall(
            runModsMethod = mainMethod,
            owner = PATCH_CACHE_BOOTSTRAP_CLASS,
            name = PATCH_CACHE_BUST_ENUMS_FROM_CACHE_METHOD_NAME,
            desc = PATCH_CACHE_BUST_ENUMS_FROM_CACHE_METHOD_DESC
        )
    }

    private fun hasCallInitializersCall(mainMethod: MethodNode): Boolean {
        val iterator = mainMethod.instructions.iterator()
        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == PREPACKAGED_LAUNCHER_OWNER &&
                instruction.name == CALL_INITIALIZERS_METHOD_NAME &&
                instruction.desc == CALL_INITIALIZERS_METHOD_DESC
            ) {
                return true
            }
        }
        return false
    }

    private fun hasStaticCall(
        runModsMethod: MethodNode,
        owner: String,
        name: String,
        desc: String
    ): Boolean {
        val iterator = runModsMethod.instructions.iterator()
        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == owner &&
                instruction.name == name &&
                instruction.desc == desc
            ) {
                return true
            }
        }
        return false
    }

    private fun readClassNode(loaderBytes: ByteArray): ClassNode {
        val classNode = ClassNode()
        ClassReader(loaderBytes).accept(classNode, 0)
        return classNode
    }

    private fun readRunModsMethod(loaderBytes: ByteArray): MethodNode? {
        val classNode = readClassNode(loaderBytes)
        return classNode.methods.firstOrNull { method ->
            method.name == RUN_MODS_METHOD_NAME && method.desc == RUN_MODS_METHOD_DESC
        }
    }
}
