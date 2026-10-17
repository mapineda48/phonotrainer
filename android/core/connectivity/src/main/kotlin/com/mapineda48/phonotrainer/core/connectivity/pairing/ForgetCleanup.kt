package com.mapineda48.phonotrainer.core.connectivity.pairing

import androidx.room.withTransaction
import com.mapineda48.phonotrainer.core.connectivity.Hex
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.domain.model.ServerId
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/**
 * `previous_token_proof` of "Connect again" (server-contract 6.2, security review L-1):
 * `hex(HMAC-SHA256(K_old, "pt-reactivate-v1|" + code))` with the key of the pairing being replaced.
 */
internal object ReactivationProof {
  private const val HMAC = "HmacSHA256"

  fun of(oldKey: ByteArray, code: String): String {
    val mac = Mac.getInstance(HMAC)
    mac.init(SecretKeySpec(oldKey, HMAC))
    return Hex.encode(mac.doFinal("pt-reactivate-v1|$code".toByteArray(Charsets.UTF_8)))
  }
}

/**
 * What "Forget this computer" changes in the library database (architecture 6.3, spec 7.9): the computer's active
 * clips are tagged `FORGOTTEN_COMPUTER` and stay; the requests that computer had accepted (they carry its job id)
 * stop being followed and are removed; waiting requests stay, untargeted, for the next computer paired; the
 * operations waiting for that computer (cancel, remove, re-run) are dropped, since they belong to its jobs.
 */
internal interface ForgetCleanup {
  suspend fun forget(serverId: ServerId)
}

/**
 * [ForgetCleanup] in one transaction of `library.db`. The `clip`, `request` and `request_op` queries belong to WP-08's
 * and WP-09's DAOs, which have no query for this; until they do (change request in WP-07's hand-off), the four
 * statements are written here against the frozen schema of architecture 4.2. A removed request's copy under
 * `requests/<id>/` is swept as an orphan by `:core:requests` at the next start.
 */
internal class RoomForgetCleanup @Inject constructor(private val database: LibraryDatabase) : ForgetCleanup {
  override suspend fun forget(serverId: ServerId) {
    database.withTransaction {
      val sql = database.openHelper.writableDatabase
      sql.execSQL(
        "UPDATE clip SET origin = 'FORGOTTEN_COMPUTER' WHERE origin = 'ACTIVE' AND server_id = ?",
        arrayOf<Any>(serverId.value),
      )
      sql.execSQL("DELETE FROM request WHERE job_id IS NOT NULL")
      sql.execSQL("UPDATE request SET target_server_id = NULL WHERE job_id IS NULL")
      sql.execSQL("DELETE FROM request_op WHERE state = 'PENDING'")
    }
  }
}
