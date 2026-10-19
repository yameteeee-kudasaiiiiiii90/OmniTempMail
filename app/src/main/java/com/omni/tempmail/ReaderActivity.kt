package com.omni.tempmail

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ReaderActivity : AppCompatActivity() {
    companion object { var cachedMsg: EmailMessage? = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        val msg = cachedMsg ?: run { finish(); return }

        findViewById<Button>(R.id.btnReaderBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvReaderSubject).text = msg.subject
        findViewById<TextView>(R.id.tvReaderFrom).text = "From: ${msg.from}"
        findViewById<TextView>(R.id.tvReaderBody).text = msg.body
    }
}
