package com.sonuclasses.mitra.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Entry point for the assist gesture (long-press home): opens Mitra and starts listening. */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_AUTO_LISTEN, true))
        finish()
    }
}
