package com.mapineda48.phonotrainer.core.sync.catalogue

import com.mapineda48.phonotrainer.core.database.library.dao.ClipDao
import com.mapineda48.phonotrainer.core.database.library.entity.ClipEntity

// SQLite before 3.32 (Android 10's) allows at most 999 bound variables in one statement: queries over a list of job ids
// go in chunks, so a computer with years of analyses never makes a listing fail.

private const val CHUNK = 500

internal suspend fun ClipDao.getMany(jobIds: Collection<String>): List<ClipEntity> =
  jobIds.distinct().chunked(CHUNK).flatMap { getAll(it) }

internal suspend fun ClipDao.deleteMany(jobIds: Collection<String>): Int =
  jobIds.distinct().chunked(CHUNK).sumOf { delete(it) }

internal suspend fun ClipDao.markDeletedOnComputerMany(jobIds: Collection<String>): Int =
  jobIds.distinct().chunked(CHUNK).sumOf { markDeletedOnComputer(it) }
