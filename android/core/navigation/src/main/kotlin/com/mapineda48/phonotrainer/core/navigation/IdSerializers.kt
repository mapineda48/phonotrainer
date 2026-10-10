package com.mapineda48.phonotrainer.core.navigation

import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.RequestId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// :core:domain has no serialization (it is the JVM contract), so the identifiers that keys carry are written here
// as plain strings.

internal object JobIdSerializer : KSerializer<JobId> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("phono.JobId", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: JobId) = encoder.encodeString(value.value)

  override fun deserialize(decoder: Decoder): JobId = JobId(decoder.decodeString())
}

internal object RequestIdSerializer : KSerializer<RequestId> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("phono.RequestId", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: RequestId) = encoder.encodeString(value.value)

  override fun deserialize(decoder: Decoder): RequestId = RequestId(decoder.decodeString())
}

internal object ChangeLabelSerializer : KSerializer<ChangeLabel> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("phono.ChangeLabel", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: ChangeLabel) = encoder.encodeString(value.value)

  override fun deserialize(decoder: Decoder): ChangeLabel = ChangeLabel(decoder.decodeString())
}
