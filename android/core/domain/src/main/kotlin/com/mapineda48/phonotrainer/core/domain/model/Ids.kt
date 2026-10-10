package com.mapineda48.phonotrainer.core.domain.model

// Identifiers and addresses of architecture 3.4, transcribed as is.

@JvmInline value class JobId(val value: String) // server job id, e.g. "20261004-133519-a77c"

@JvmInline value class RequestId(val value: String) // client UUID; also the request's first idempotency key

@JvmInline value class ServerId(val value: String)

@JvmInline value class LibraryId(val value: String)

@JvmInline value class DeviceId(val value: String)

@JvmInline value class ChangeLabel(val value: String) // "flapping", one of the 16 reference labels

data class HostPort(val host: String, val port: Int) // IPv4 literal only (architecture 6.2)

data class ShortId(val jobId: JobId, val firstSegment: Int)

data class WordRef(val jobId: JobId, val segment: Int, val word: Int)
