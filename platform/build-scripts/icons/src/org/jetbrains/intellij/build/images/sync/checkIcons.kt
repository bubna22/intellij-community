// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.intellij.build.images.sync

import org.jetbrains.intellij.build.images.imageSize
import org.jetbrains.intellij.build.images.isImage
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.serialization.JpsSerializationManager
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.util.function.Consumer
import java.util.stream.Collectors

/**
 * @param devRepoDir developers' git repo
 * @param iconsRepoDir designers' git repo
 * @param skipDirsPattern dir name pattern to skip unnecessary icons
 */
fun checkIcons(
  devRepoDir: String, iconsRepoDir: String,
  skipDirsPattern: String?, doSync: Boolean = false,
  loggerImpl: Consumer<String> = Consumer { println(it) },
  errorHandler: Consumer<String> = Consumer { throw IllegalStateException(it) }
) {
  logger = loggerImpl
  val iconsRepo = findGitRepoRoot(iconsRepoDir)
  val icons = readIconsRepo(iconsRepo, iconsRepoDir)
  val devRepoRoot = findGitRepoRoot(devRepoDir)
  val devRepoVcsRoots = vcsRoots(devRepoRoot)
  val devIcons = readDevRepo(devRepoRoot, devRepoDir, devRepoVcsRoots, skipDirsPattern)
  val devIconsBackup = HashMap(devIcons)
  val addedByDesigners = mutableListOf<String>()
  val modified = mutableListOf<String>()
  val consistent = mutableListOf<String>()
  icons.forEach { icon, gitObject ->
    if (!devIcons.containsKey(icon)) {
      addedByDesigners += icon
    }
    else if (gitObject.hash != devIcons[icon]?.hash) {
      modified += icon
    }
    else {
      consistent += icon
    }
    devIcons.remove(icon)
  }
  val addedByDev = devIcons.keys
  val modifiedByDev = modifiedByDev(modified, icons, devIconsBackup)
  val removedByDev = removedByDev(addedByDesigners, icons, devRepoVcsRoots, File(devRepoDir))
  val modifiedByDesigners = modified.filter { !modifiedByDev.contains(it) }
  val removedByDesigners = removedByDesigners(
    addedByDev, devIconsBackup, iconsRepo,
    File(iconsRepoDir).relativeTo(iconsRepo).path.let {
      if (it.isEmpty()) "" else "$it/"
    }
  )
  if (doSync) callSafely {
    syncAdded(addedByDev, devIconsBackup, iconsRepo, File(iconsRepoDir))
    syncModified(modifiedByDev, icons, devIconsBackup)
    syncRemoved(removedByDev, icons)
  }
  report(
    devIconsBackup.size, icons.size, skippedDirs.size,
    addedByDev, removedByDev, modifiedByDev,
    addedByDesigners, removedByDesigners, modifiedByDesigners,
    consistent, errorHandler, doSync
  )
}

private fun readIconsRepo(iconsRepo: File, iconsRepoDir: String) =
  listGitObjects(iconsRepo, iconsRepoDir) {
    // read icon hashes
    isIcon(it)
  }

private fun readDevRepo(devRepoRoot: File,
                        devRepoDir: String,
                        devRepoVcsRoots: List<File>,
                        skipDirsPattern: String?
): MutableMap<String, GitObject> {
  val testRoots = searchTestRoots(devRepoRoot.absolutePath)
  log("Found ${testRoots.size} test roots")
  if (skipDirsPattern != null) log("Using pattern $skipDirsPattern to skip dirs")
  val skipDirsRegex = skipDirsPattern?.toRegex()
  val devRepoIconFilter = { file: File ->
    // read icon hashes skipping test roots
    !inTestRoot(file, testRoots, skipDirsRegex) && isIcon(file)
  }
  val devIcons = if (devRepoVcsRoots.size == 1
                     && devRepoVcsRoots.contains(devRepoRoot)) {
    // read icons from devRepoRoot
    listGitObjects(devRepoRoot, devRepoDir, devRepoIconFilter)
  }
  else {
    // read icons from multiple repos in devRepoRoot
    listGitObjects(devRepoRoot, devRepoVcsRoots, devRepoIconFilter)
  }
  return devIcons.toMutableMap()
}

private fun searchTestRoots(devRepoDir: String) = try {
  JpsSerializationManager.getInstance()
    .loadModel(devRepoDir, null)
    .project.modules.flatMap {
    it.getSourceRoots(JavaSourceRootType.TEST_SOURCE) +
    it.getSourceRoots(JavaResourceRootType.TEST_RESOURCE)
  }.mapTo(mutableSetOf()) { it.file }
}
catch (e: IOException) {
  log(e.message!!)
  emptySet<File>()
}

private val mutedStream = PrintStream(object : OutputStream() {
  override fun write(b: ByteArray) {}

  override fun write(b: ByteArray, off: Int, len: Int) {}

  override fun write(b: Int) {}
})

private fun isIcon(file: File): Boolean {
  val err = System.err
  System.setErr(mutedStream)
  return try {
    // image
    isImage(file) && imageSize(file)?.let { size ->
      val pixels = if (file.name.contains("@2x")) 64 else 32
      // small
      size.height <= pixels && size.width <= pixels
    } ?: false
  }
  catch (e: Exception) {
    log("WARNING: $file: ${e.message}")
    false
  }
  finally {
    System.setErr(err)
  }
}

private val skippedDirs = mutableSetOf<File>()

private fun inTestRoot(file: File, testRoots: Set<File>, skipDirsRegex: Regex?): Boolean {
  val inTestRoot = file.isDirectory &&
                   // is test root
                   (testRoots.contains(file) ||
                    // or matches pattern
                    skipDirsRegex != null && file.name.matches(skipDirsRegex))
  if (inTestRoot) skippedDirs += file
  return inTestRoot
         // or check parent
         || file.parentFile != null && inTestRoot(file.parentFile, testRoots, skipDirsRegex)
}

private fun removedByDesigners(
  addedByDev: MutableCollection<String>,
  devIcons: Map<String, GitObject>,
  iconsRepo: File, iconsDir: String) = addedByDev.parallelStream()
  // latest changes are made by designers
  .filter { latestChangeTime(devIcons[it]) < latestChangeTime("$iconsDir$it", iconsRepo) }
  .collect(Collectors.toList())
  .also { addedByDev.removeAll(it) }

private fun removedByDev(
  addedByDesigners: MutableCollection<String>,
  icons: Map<String, GitObject>,
  devRepos: Collection<File>, devRepoDir: File) =
  addedByDesigners.parallelStream().filter { path ->
    devRepos.mapNotNull {
      val file = File(devRepoDir, path).relativeTo(it)
      if (file.path == file.normalize().path)
        latestChangeTime(file.path, it)
      else null
    }.firstOrNull { it > 0 }?.let { latestChangeTimeByDev ->
      // latest changes are made by developers
      latestChangeTime(icons[path]) < latestChangeTimeByDev
    } ?: false
  }.collect(Collectors.toList()).also {
    addedByDesigners.removeAll(it)
  }

private fun modifiedByDev(
  modified: Collection<String>,
  icons: Map<String, GitObject>,
  devIcons: Map<String, GitObject>) = modified.parallelStream()
  // latest changes are made by developers
  .filter { latestChangeTime(icons[it]) < latestChangeTime(devIcons[it]) }
  .collect(Collectors.toList())

private fun latestChangeTime(obj: GitObject?) =
  latestChangeTime(obj!!.file, obj.repo).also {
    if (it <= 0) throw IllegalStateException(obj.toString())
  }