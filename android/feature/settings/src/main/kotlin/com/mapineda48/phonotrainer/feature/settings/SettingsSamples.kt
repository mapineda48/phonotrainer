package com.mapineda48.phonotrainer.feature.settings

import com.mapineda48.phonotrainer.core.domain.model.ClipStorage
import com.mapineda48.phonotrainer.core.domain.model.ColorChoice
import com.mapineda48.phonotrainer.core.domain.model.ComputerStatus
import com.mapineda48.phonotrainer.core.domain.model.DeviceId
import com.mapineda48.phonotrainer.core.domain.model.HostPort
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.LibraryId
import com.mapineda48.phonotrainer.core.domain.model.NotificationPermission
import com.mapineda48.phonotrainer.core.domain.model.PairedComputer
import com.mapineda48.phonotrainer.core.domain.model.PauseReason
import com.mapineda48.phonotrainer.core.domain.model.ProbeOutcome
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import com.mapineda48.phonotrainer.core.domain.model.StorageBudget
import com.mapineda48.phonotrainer.core.domain.model.StorageUsage
import com.mapineda48.phonotrainer.core.domain.model.UserSettings
import java.time.Instant

/** Sample states of every page of :feature:settings, for the previews (design 12.2) and the screen tests. */
internal object SettingsSamples {
  val now: Instant = Instant.parse("2026-10-18T10:00:00Z")

  val coffee = JobId("job-coffee")
  val park = JobId("job-park")
  val market = JobId("job-market")

  val computer = PairedComputer(
    serverId = ServerId("srv_example1"),
    name = "Study desk",
    deviceId = DeviceId("device-1"),
    deviceName = "Galaxy phone",
    address = HostPort("192.0.2.10", 8765),
    libraryId = LibraryId("lib_example1"),
    pairedAt = Instant.parse("2026-10-01T09:00:00Z"),
    lastSeen = now.minusSeconds(120),
    lastSync = now.minusSeconds(600),
    apiVersion = 6,
    capabilities = null,
  )

  val settings = SettingsUiState(
    settings = UserSettings(excludedFromMix = setOf(market)),
    computer = ComputerStatus.Connected("Study desk", analyzing = 0, inLine = 0, lowOnSpace = false),
    storage = StorageLine(1_400_000_000, StorageBudget.Limit(2_000_000_000)),
    notifications = NotificationPermission.GRANTED,
    clips = listOf(
      MixClip(coffee, "Coffee shop small talk", inMix = true),
      MixClip(park, "A walk in the park", inMix = true),
      MixClip(market, "At the market", inMix = false),
    ),
  )

  val settingsCvd = settings.copy(
    settings = settings.settings.copy(colors = ColorChoice.COLOR_VISION_FRIENDLY),
    computer = ComputerStatus.NotConnected,
    storage = StorageLine(0, StorageBudget.FreeSpaceOnly),
    notifications = NotificationPermission.DENIED,
    clips = emptyList(),
  )

  val connected = YourComputerUiState(
    status = ComputerStatus.Connected("Study desk", analyzing = 0, inLine = 0, lowOnSpace = false),
    paired = computer,
    lastSynced = computer.lastSync,
    checkedAt = now.minusSeconds(30),
    now = now,
  )

  val unreachable = connected.copy(
    status = ComputerStatus.NotReachable("Study desk", lastSeen = computer.lastSeen, addressMayHaveChanged = true, lastProbe = ProbeOutcome.NoAnswer(3)),
    checking = true,
    forget = ForgetOutcome.Failed("Study desk"),
  )

  val notConnected = YourComputerUiState(now = now)

  val forgotten = YourComputerUiState(now = now, forget = ForgetOutcome.Forgotten("Study desk"))

  val usage = StorageUsage(
    clipsBytes = 120_000_000,
    videosBytes = 1_250_000_000,
    waitingCopiesBytes = 30_000_000,
    cacheBytes = 12_000_000,
    budget = StorageBudget.Limit(2_000_000_000),
    freeBytes = 21_000_000_000,
    floorBytes = 1_000_000_000,
    budgetReached = false,
    belowFloor = false,
    perClip = listOf(
      ClipStorage(coffee, "Coffee shop small talk", studyPackBytes = 21_000_000, videoBytes = 640_000_000, kept = true),
      ClipStorage(park, "A walk in the park", studyPackBytes = 2_100_000, videoBytes = 17_000_000, kept = false),
      ClipStorage(market, "At the market", studyPackBytes = 1_800_000, videoBytes = 0, kept = false),
    ),
  )

  val storage = StorageUiState(read = StorageRead.Ready(usage))

  val storageLimits = StorageUiState(
    read = StorageRead.Ready(usage.copy(budgetReached = true, budget = StorageBudget.Limit(1_000_000_000))),
    downloadVideos = false,
    paused = PauseReason.NOT_CONNECTED,
    backgroundRestricted = true,
  )

  val storageFirstRun = StorageUiState(
    read = StorageRead.Ready(StorageUsage(0, 0, 0, 0, StorageBudget.Limit(2_000_000_000), 21_000_000_000, 1_000_000_000, false, false, emptyList())),
  )

  val app = Licences.app("1.0.0")

  val catalogue = LicenceCatalogue(
    app = app,
    bundled = listOf(
      LicenceEntry(
        id = "bundled:Charis",
        kind = LicenceKind.FONT,
        name = "Charis",
        version = "7.000",
        licences = listOf(LicenceRef("OFL-1.1", "OFL-1.1", null, "licenses/charis-OFL.txt")),
        holder = "Copyright (c) 1997-2025 SIL Global",
        source = "https://github.com/silnrsi/font-charis/releases/tag/v7.000",
        files = 2,
      ),
      LicenceEntry(
        id = "bundled:Lucide",
        kind = LicenceKind.ICONS,
        name = "Lucide",
        version = "1.51.0",
        licences = listOf(LicenceRef("ISC AND MIT", "ISC AND MIT", null, "licenses/lucide-LICENSE.txt")),
        holder = "Lucide Icons and Contributors",
        source = "https://lucide.dev",
        icons = 122,
      ),
    ),
    libraries = listOf(
      LicenceEntry(
        id = "androidx.activity:activity",
        kind = LicenceKind.LIBRARY,
        name = "Activity",
        version = "1.13.0",
        licences = listOf(LicenceRef("Apache-2.0", "The Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt", Licences.textAsset("Apache-2.0"))),
        holder = "The Android Open Source Project",
      ),
      LicenceEntry(
        id = "androidx.datastore:datastore-preferences-external-protobuf",
        kind = LicenceKind.LIBRARY,
        name = "Preferences External Protobuf",
        version = "1.2.1",
        licences = listOf(LicenceRef("BSD-3-Clause", "BSD-3-Clause", "https://opensource.org/licenses/BSD-3-Clause", Licences.textAsset("BSD-3-Clause"))),
        holder = "The Android Open Source Project",
      ),
    ),
  )

  val licenceText = LicenceTextUi(
    entry = catalogue.libraries[1],
    sections = listOf(
      LicenceSection(
        catalogue.libraries[1].licences.first(),
        listOf(
          "Copyright (c) <year> <owner>.",
          "Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:",
        ),
      ),
    ),
  )
}
