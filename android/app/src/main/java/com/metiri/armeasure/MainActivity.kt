package com.metiri.armeasure

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import uniffi.core.ping

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "AR Measure - FFI ping: ${ping()}"
        })
    }
}