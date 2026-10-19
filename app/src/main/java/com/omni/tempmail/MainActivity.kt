package com.omni.tempmail

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private var mailService: OmniMailService? = null
    private var isBound = false
    private lateinit var adapter: MessagesAdapter

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mailService = (service as OmniMailService.LocalBinder).getService()
            isBound = true
            mailService?.setStateListener { refreshUi() }
            refreshUi()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            mailService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val rv = findViewById<RecyclerView>(R.id.recyclerViewMessages)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = MessagesAdapter(emptyList(), onItemClick = { msg ->
            ReaderActivity.cachedMsg = msg
            startActivity(Intent(this, ReaderActivity::class.java))
        }, onCopyOtp = { otp ->
            mailService?.copyToClipboard(otp, "Copied OTP: $otp")
        })
        rv.adapter = adapter

        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            mailService?.triggerRefresh { added ->
                refreshUi()
                Toast.makeText(this, if (added > 0) "Received $added new email(s)" else "Up to date", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnCopyAddress).setOnClickListener {
            mailService?.apiClient?.currentEmail?.let { mailService?.copyToClipboard(it, "Copied: $it") }
        }

        findViewById<TextView>(R.id.provMailTm).setOnClickListener { selectProvider("mail.tm") }
        findViewById<TextView>(R.id.prov1Sec).setOnClickListener { selectProvider("1secmail") }
        findViewById<TextView>(R.id.provMailGw).setOnClickListener { selectProvider("mail.gw") }

        val intent = Intent(this, OmniMailService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun selectProvider(prov: String) {
        val colActive = 0xFFFFFFFF.toInt()
        val colMuted = 0xFF8E8E93.toInt()

        val tvTm = findViewById<TextView>(R.id.provMailTm)
        val tv1Sec = findViewById<TextView>(R.id.prov1Sec)
        val tvGw = findViewById<TextView>(R.id.provMailGw)

        tvTm.setBackgroundResource(if (prov == "mail.tm") R.drawable.bg_segment_active else android.R.color.transparent)
        tvTm.setTextColor(if (prov == "mail.tm") colActive else colMuted)

        tv1Sec.setBackgroundResource(if (prov == "1secmail") R.drawable.bg_segment_active else android.R.color.transparent)
        tv1Sec.setTextColor(if (prov == "1secmail") colActive else colMuted)

        tvGw.setBackgroundResource(if (prov == "mail.gw") R.drawable.bg_segment_active else android.R.color.transparent)
        tvGw.setTextColor(if (prov == "mail.gw") colActive else colMuted)

        Toast.makeText(this, "Switching to $prov...", Toast.LENGTH_SHORT).show()
        mailService?.switchProvider(prov)
    }

    private fun refreshUi() {
        val client = mailService?.apiClient ?: return
        findViewById<TextView>(R.id.tvEmailAddress).text = client.currentEmail
        val msgs = client.getMessages()
        adapter.updateData(msgs)

        findViewById<LinearLayout>(R.id.layoutEmptyInbox).visibility = if (msgs.isEmpty()) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.recyclerViewMessages).visibility = if (msgs.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        if (isBound) {
            mailService?.setStateListener(null)
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}
