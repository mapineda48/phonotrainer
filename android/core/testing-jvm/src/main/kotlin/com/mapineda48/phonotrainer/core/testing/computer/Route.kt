package com.mapineda48.phonotrainer.core.testing.computer

import mockwebserver3.MockResponse

/** The rows of server-contract 6.10 (the paths after `/api/phone/`; `{}` is one path parameter). */
internal enum class Route(
  val method: String,
  path: String,
  val signed: Boolean,
  val jsonBody: Boolean,
  private val needs: (FakeCapabilities) -> Boolean,
  val handler: ComputerCore.(Call) -> MockResponse,
) {
  HELLO("GET", "hello", signed = false, jsonBody = false, { true }, ComputerCore::hello),
  PAIR("POST", "pair", signed = false, jsonBody = true, { true }, ComputerCore::pair),
  HEALTH("GET", "health", signed = true, jsonBody = false, { true }, ComputerCore::health),
  UNPAIR_SELF("DELETE", "devices/self", signed = true, jsonBody = false, { true }, ComputerCore::unpairSelf),
  LIBRARY("GET", "library", signed = true, jsonBody = false, { true }, ComputerCore::library),
  CREATE_LINK("POST", "jobs/link", signed = true, jsonBody = true, { true }, ComputerCore::createLink),
  JOB("GET", "jobs/{}", signed = true, jsonBody = false, { true }, ComputerCore::jobRoute),
  DELETE_JOB("DELETE", "jobs/{}", signed = true, jsonBody = false, { true }, ComputerCore::deleteJobRoute),
  ARTIFACT("GET", "jobs/{}/artifacts/{}", signed = true, jsonBody = false, { true }, ComputerCore::artifact),
  CANCEL("POST", "jobs/{}/cancel", signed = true, jsonBody = false, { true }, ComputerCore::cancel),
  RERUN("POST", "jobs/{}/rerun", signed = true, jsonBody = true, { true }, ComputerCore::rerun),
  VIDEO_PHONE(
    "POST", "jobs/{}/renditions/video_phone", signed = true, jsonBody = false, { it.phoneVideo },
    ComputerCore::requestPhoneVideo,
  ),
  CONTOUR("GET", "jobs/{}/contour", signed = true, jsonBody = false, { it.recordCompare }, ComputerCore::contour),
  COMPARE("POST", "jobs/{}/compare", signed = true, jsonBody = false, { it.recordCompare }, ComputerCore::compare),
  REQUEST_LOOKUP("GET", "requests/{}", signed = true, jsonBody = false, { true }, ComputerCore::requestLookup),
  DECLARE_UPLOAD("POST", "uploads", signed = true, jsonBody = true, { true }, ComputerCore::declareUpload),
  PUT_UPLOAD("PUT", "uploads/{}", signed = true, jsonBody = false, { true }, ComputerCore::putUpload),
  UPLOAD_STATUS("GET", "uploads/{}", signed = true, jsonBody = false, { it.resumableUploads }, ComputerCore::uploadStatus),
  PATCH_UPLOAD("PATCH", "uploads/{}", signed = true, jsonBody = false, { it.resumableUploads }, ComputerCore::patchUpload),
  DELETE_UPLOAD("DELETE", "uploads/{}", signed = true, jsonBody = false, { true }, ComputerCore::deleteUpload),
  REFERENCE("GET", "reference", signed = true, jsonBody = false, { true }, ComputerCore::reference),
  EVENTS("GET", "events", signed = true, jsonBody = false, { true }, ComputerCore::events),
  ;

  private val pattern = path.split('/')

  /** Routes whose body is a file (uploads, a recorded take): not bound by the 16 KiB JSON limit (4.7). */
  val fileBody: Boolean get() = this == PUT_UPLOAD || this == PATCH_UPLOAD || this == COMPARE

  fun available(capabilities: FakeCapabilities): Boolean = needs(capabilities)

  companion object {
    /** The route for [method] and the decoded segments after `/api/phone/`; the literal rows win over `{}`. */
    fun match(method: String, segments: List<String>): Route? = entries
      .filter { it.method == method && it.pattern.size == segments.size }
      .filter { route -> route.pattern.zip(segments).all { (want, have) -> want == "{}" || want == have } }
      .minByOrNull { route -> route.pattern.count { it == "{}" } }
  }
}
