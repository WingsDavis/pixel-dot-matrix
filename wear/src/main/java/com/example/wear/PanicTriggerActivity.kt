package com.example.wear

import android.app.Activity
import android.os.Bundle
import android.content.Intent

class PanicTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startActivity(Intent(this, MainActivity::class.java).apply {
            action = ComplicationActions.ACTION_OPEN_PANIC
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}
