package com.mapineda48.phonotrainer.e2e.share;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * The share tests' sharing app (work-plan WP-23, change request CR-WP23-1), in the TEST PACKAGE's own process and task:
 * started by a test with the share intent to send ({@link #SHARE}) and the files it holds ({@link #FILES}), it keeps
 * the files and starts the share once, resolved in PhonoTrainer's
 * package by its intent filters, with a read grant for the shared items, from its own task (and in a new one with
 * {@link #NEW_TASK}, as some sharers and choosers do). It shows {@link #TEXT}, so a test sees it come back on top;
 * {@link #FINISH} ends its task. Java with the framework only, for the reason {@link TestMediaProvider} gives.
 */
public final class SharingAppActivity extends Activity {
  public static final String SHARE = "share";
  public static final String NEW_TASK = "newTask";
  public static final String FINISH = "finish";

  /** Files to hold before sharing: a bundle of byte arrays by file name, written where the provider serves them. */
  public static final String FILES = "files";

  /** What the sharing app shows. */
  public static final String TEXT = "Sharing app";

  /** The app the shares are resolved in. */
  public static final String TARGET_PACKAGE = "com.mapineda48.phonotrainer";

  @Override
  @SuppressLint("SetTextI18n") // a test app's fixed English marker, never shown to a learner
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    TextView text = new TextView(this);
    text.setText(TEXT);
    text.setTextSize(24f);
    setContentView(text);
    if (savedInstanceState == null) handle(getIntent());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    handle(intent);
  }

  @SuppressWarnings("deprecation")
  private void handle(Intent intent) {
    if (intent == null) return;
    if (intent.getBooleanExtra(FINISH, false)) {
      finishAndRemoveTask();
      return;
    }
    Intent share = intent.getParcelableExtra(SHARE);
    if (share == null) return;
    Bundle files = intent.getBundleExtra(FILES);
    if (files != null) {
      for (String name : files.keySet()) {
        if (!keep(name, files.getByteArray(name))) return;
      }
    }
    // Reading the extras unparcels them: startActivity moves EXTRA_STREAM into the ClipData that carries the read grant
    // only for extras in memory, as they are in a share an app builds itself (Intent.migrateExtraStreamToClipData).
    share.hasExtra(Intent.EXTRA_STREAM);
    share.setPackage(TARGET_PACKAGE);
    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if (intent.getBooleanExtra(NEW_TASK, false)) share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(share);
  }

  private boolean keep(String name, byte[] bytes) {
    if (bytes == null || name.contains("/") || name.startsWith(".")) return false;
    try (OutputStream out = new FileOutputStream(new File(getCacheDir(), name))) {
      out.write(bytes);
      return true;
    } catch (IOException failed) {
      return false;
    }
  }
}
