package com.mapineda48.phonotrainer.security

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mapineda48.phonotrainer.R
import com.mapineda48.phonotrainer.core.database.learning.LearningDatabase
import com.mapineda48.phonotrainer.core.database.library.LibraryDatabase
import com.mapineda48.phonotrainer.core.datastore.DataStoreFiles
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Architecture 4.1 and 20.1 (work-plan WP-02 Proof): backup and device transfer are an allowlist of `learning.db` and
 * the settings file, nothing else (never the pairing file with the credential, the device queue or media), and cloud
 * backup is skipped on devices that cannot encrypt it. The names are checked against the constants the app opens the
 * files with, so a renamed file cannot silently fall out of the backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupRulesTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  private val allowlist = setOf(
    Rule("include", "database", LearningDatabase.FILE_NAME),
    Rule("include", "file", "datastore/${DataStoreFiles.SETTINGS}.preferences_pb"),
  )

  private data class Rule(val tag: String, val domain: String?, val path: String?)

  @Test
  fun theAllowlistNamesTheFilesTheAppReallyUses() {
    assertThat(allowlist.map { it.path })
      .containsExactly("learning.db", "datastore/settings.preferences_pb")
    val everyPath = allowlist.joinToString { it.path.orEmpty() }
    assertThat(everyPath).doesNotContain(DataStoreFiles.PAIRING)
    assertThat(everyPath).doesNotContain(LibraryDatabase.FILE_NAME)
  }

  @Test
  fun dataExtractionRulesAreTheAllowlistInBothSections() {
    val sections = parse(R.xml.data_extraction_rules)

    assertThat(sections.keys).containsExactly("cloud-backup", "device-transfer")
    assertThat(sections.getValue("cloud-backup").rules).containsExactlyElementsIn(allowlist)
    assertThat(sections.getValue("device-transfer").rules).containsExactlyElementsIn(allowlist)
    assertThat(sections.getValue("cloud-backup").attributes)
      .containsEntry("disableIfNoEncryptionCapabilities", "true")
  }

  @Test
  fun fullBackupContentIsTheSameAllowlist() {
    val sections = parse(R.xml.backup_rules)

    assertThat(sections.keys).containsExactly("full-backup-content")
    assertThat(sections.getValue("full-backup-content").rules).containsExactlyElementsIn(allowlist)
  }

  @Test
  fun backupIsOn() {
    assertThat(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP).isNotEqualTo(0)
  }

  private class Section(val attributes: Map<String, String>, val rules: MutableList<Rule> = mutableListOf())

  /** Each section element (`cloud-backup`, `device-transfer`, or the root `full-backup-content`) and its rules. */
  private fun parse(xml: Int): Map<String, Section> {
    val sections = linkedMapOf<String, Section>()
    var current: Section? = null
    context.resources.getXml(xml).use { parser ->
      while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType != XmlPullParser.START_TAG) continue
        val attributes = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
        when (parser.name) {
          "data-extraction-rules" -> Unit
          "cloud-backup", "device-transfer", "full-backup-content" ->
            current = Section(attributes).also { sections[parser.name] = it }
          else -> checkNotNull(current) { "rule outside a section" }.rules +=
            Rule(parser.name, attributes["domain"], attributes["path"])
        }
      }
    }
    return sections
  }
}
