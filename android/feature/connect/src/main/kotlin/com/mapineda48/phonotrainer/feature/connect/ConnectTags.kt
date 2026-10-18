package com.mapineda48.phonotrainer.feature.connect

/** Test tags of :feature:connect ("connect.<screen>.<element>", work-plan 2.6). */
object ConnectTags {
  /** The Connect page's list (P-06). */
  const val PAGE = "connect.connect.page"

  /** The instruction card for the computer side and its two commands' "Copy" buttons. */
  const val STEPS = "connect.connect.steps"
  const val COPY_START = "connect.connect.copyStart"
  const val COPY_PAIR = "connect.connect.copyPair"

  /** "Looking for your computer…". */
  const val LOOKING = "connect.connect.looking"

  /** The D-39 note above the list when two answers share a name or an identity (F-02-AC10). */
  const val CONFLICT = "connect.connect.conflict"

  /** "Enter the address instead" inside the conflict note. */
  const val CONFLICT_ADDRESS = "connect.connect.conflictAddress"

  /** The list of computers that answered. */
  const val LIST = "connect.connect.list"

  /** The row of the computer with [key] (`FoundComputer` server id and address, see `ComputerRow.key`). */
  fun row(key: String): String = "connect.connect.row.$key"

  /** Every row carries this tag on its words too, so a test can count rows without knowing their keys. */
  const val ROW = "connect.connect.row"

  /** "Search again". */
  const val SEARCH_AGAIN = "connect.connect.searchAgain"

  /** "Enter the address instead" under the list or in the nothing-found block. */
  const val ENTER_ADDRESS = "connect.connect.enterAddress"

  /** "No computer answered." with its checklist. */
  const val NOTHING_FOUND = "connect.connect.nothingFound"

  /** The checklist (numbered) of the nothing-found block and of Connection details. */
  const val CHECKLIST = "connect.connect.checklist"

  /** "Connect to your home Wi-Fi first." and "Open Wi-Fi settings". */
  const val NOT_ON_WIFI = "connect.connect.notOnWifi"
  const val OPEN_WIFI = "connect.connect.openWifi"

  /** The address field, "Check" and the answer under it. */
  const val ADDRESS = "connect.connect.address"
  const val CHECK = "connect.connect.check"
  const val ADDRESS_RESULT = "connect.connect.addressResult"

  /** The code: the six cells (one field), the error line, the phone's name and "Connect". */
  const val CODE = "connect.connect.code"
  const val CODE_SECTION = "connect.connect.codeSection"
  const val CODE_ERROR = "connect.connect.codeError"
  const val DEVICE_NAME = "connect.connect.deviceName"
  const val CONNECT = "connect.connect.connect"

  /** Success: "Connected to {name}" and its button ("Go to Home", or "Done" for a new address). */
  const val SUCCESS = "connect.connect.success"
  const val SUCCESS_ACTION = "connect.connect.successAction"

  /** First run: "Not now" and its sentence. */
  const val NOT_NOW = "connect.connect.notNow"

  /** Connection details (P-07). */
  const val DETAILS = "connect.details.page"
  const val DETAILS_WHY = "connect.details.why"
  const val DETAILS_LAST_PROBE = "connect.details.lastProbe"
  const val DETAILS_CHECK_NOW = "connect.details.checkNow"
  const val DETAILS_ADDRESS_CHANGED = "connect.details.addressChanged"
  const val DETAILS_FACTS = "connect.details.facts"
  const val DETAILS_ADDRESS = "connect.details.address"
  const val DETAILS_PHONE_ADDRESS = "connect.details.phoneAddress"
  const val DETAILS_SERVER_ID = "connect.details.serverId"
  const val DETAILS_API = "connect.details.api"
  const val DETAILS_COUNTS = "connect.details.counts"
  const val DETAILS_BACKGROUND = "connect.details.background"
  const val DETAILS_DECODERS = "connect.details.decoders"
  const val DETAILS_COPY = "connect.details.copy"
  const val DETAILS_COPIED = "connect.details.copied"

  /** Kept from the contract freeze: the link to Connection details. */
  const val LINK_DETAILS = "connect.connect.details"
}
