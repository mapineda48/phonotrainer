package com.mapineda48.phonotrainer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity. Empty at the toolchain proof (WP-02 M1); the contract freeze (M2) puts the splash, the
 * navigation shell and the theme here.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {}
  }
}
