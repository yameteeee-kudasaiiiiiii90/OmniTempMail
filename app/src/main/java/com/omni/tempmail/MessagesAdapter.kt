package com.omni.tempmail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MessagesAdapter(
    private var items: List<EmailMessage>,
    private val onItemClick: (EmailMessage) -> Unit,
    private val onCopyOtp: (String) -> Unit
) : RecyclerView.Adapter<MessagesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvSubject: TextView = view.findViewById(R.id.tvSubject)
        val btnCopyOtpChip: Button = view.findViewById(R.id.btnCopyOtpChip)
    }

    fun updateData(newItems: List<EmailMessage>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = items[position]
        holder.tvSender.text = msg.from
        holder.tvSubject.text = msg.subject
        holder.itemView.setOnClickListener { onItemClick(msg) }

        if (!msg.otp.isNullOrBlank()) {
            holder.btnCopyOtpChip.visibility = View.VISIBLE
            holder.btnCopyOtpChip.text = "📋 Copy OTP: ${msg.otp}"
            holder.btnCopyOtpChip.setOnClickListener { onCopyOtp(msg.otp) }
        } else {
            holder.btnCopyOtpChip.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = items.size
}
