package com.mapineda48.phonotrainer.e2e.share;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

/**
 * The shared media of the share tests' sharing app (work-plan WP-23, change request CR-WP23-1). It runs in the TEST
 * PACKAGE's own process under its own uid and is not exported, so PhonoTrainer opens it only through the URI grant of
 * a share, as with any app's FileProvider: the grants are real. That process loads only the test APK, whose dex leaves
 * out every library the app already ships, the Kotlin standard library included: this file is Java and uses the
 * Android framework only. Its authority does not start with the app's package name, which the app refuses as its own.
 *
 * <p>{@code content://phonotrainer.e2e.sharedmedia/<kind>/<file or size>?name=…&type=…&declared=…}:
 * <ul>
 *   <li>{@code file/<file>}: a file {@link SharingAppActivity} wrote before sharing, a seekable descriptor;
 *   <li>{@code slow/<bytes>}: that many pattern bytes ({@code position % 251}) through a pipe, a chunk every
 *       {@code pause} ms (40 by default), as a slow provider (a cloud file) would.
 * </ul>
 * {@code name} is the display name it reports, {@code type} its type, and {@code declared} the size it reports: a
 * number (which may be a lie), {@code none} for no size, or nothing for the truth.
 */
public final class TestMediaProvider extends ContentProvider {
  public static final String AUTHORITY = "phonotrainer.e2e.sharedmedia";

  private static final int CHUNK = 32 * 1024;

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("read only");
    String kind = uri.getPathSegments().get(0);
    if ("file".equals(kind)) {
      return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    if ("slow".equals(kind)) {
      String pause = uri.getQueryParameter("pause");
      try {
        return slow(Long.parseLong(uri.getPathSegments().get(1)), pause == null ? 40L : Long.parseLong(pause));
      } catch (IOException failed) {
        throw new FileNotFoundException("no pipe");
      }
    }
    throw new FileNotFoundException("no such test media");
  }

  private File file(Uri uri) throws FileNotFoundException {
    String name = uri.getPathSegments().get(1);
    if (name.contains("/") || name.startsWith(".")) throw new FileNotFoundException("no such test media");
    return new File(getContext().getCacheDir(), name);
  }

  private static ParcelFileDescriptor slow(long size, long pauseMs) throws IOException {
    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
    new Thread(new SlowWriter(pipe[1], size, pauseMs), "slow-media").start();
    return pipe[0];
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
    String declared = uri.getQueryParameter("declared");
    Object size;
    if (declared == null) {
      size = trueSize(uri);
    } else if ("none".equals(declared)) {
      size = null;
    } else {
      size = Long.valueOf(declared);
    }
    cursor.addRow(new Object[] {uri.getQueryParameter("name"), size});
    return cursor;
  }

  private Long trueSize(Uri uri) {
    if ("slow".equals(uri.getPathSegments().get(0))) return Long.valueOf(uri.getPathSegments().get(1));
    try {
      return file(uri).length();
    } catch (FileNotFoundException missing) {
      return null;
    }
  }

  @Override
  public String getType(Uri uri) {
    return uri.getQueryParameter("type");
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    return null;
  }

  @Override
  public int delete(Uri uri, String selection, String[] args) {
    return 0;
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    return 0;
  }

  /** Writes the pattern bytes of a slow share into its pipe, until the reader goes away. */
  private static final class SlowWriter implements Runnable {
    private final ParcelFileDescriptor pipe;
    private final long size;
    private final long pauseMs;

    SlowWriter(ParcelFileDescriptor pipe, long size, long pauseMs) {
      this.pipe = pipe;
      this.size = size;
      this.pauseMs = pauseMs;
    }

    @Override
    public void run() {
      try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe)) {
        byte[] chunk = new byte[CHUNK];
        long sent = 0;
        while (sent < size) {
          int length = (int) Math.min(CHUNK, size - sent);
          for (int i = 0; i < length; i++) chunk[i] = (byte) ((sent + i) % 251);
          out.write(chunk, 0, length);
          sent += length;
          Thread.sleep(pauseMs);
        }
      } catch (IOException gone) {
        // The reader went away (the copy was stopped).
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
