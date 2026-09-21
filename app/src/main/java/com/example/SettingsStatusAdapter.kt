package com.example

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SettingsStatusAdapter(
    private var channels: List<Channel>
) : RecyclerView.Adapter<SettingsStatusAdapter.StatusViewHolder>() {

    fun updateChannels(newChannels: List<Channel>) {
        this.channels = newChannels
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StatusViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_settings_status, parent, false)
        return StatusViewHolder(view)
    }

    override fun onBindViewHolder(holder: StatusViewHolder, position: Int) {
        holder.bind(channels[position])
    }

    override fun getItemCount(): Int = channels.size

    class StatusViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvStatusName: TextView = itemView.findViewById(R.id.tv_status_name)
        private val tvStatusRegex: TextView = itemView.findViewById(R.id.tv_status_regex)
        private val tvStatusConnection: TextView = itemView.findViewById(R.id.tv_status_connection)

        fun bind(channel: Channel) {
            tvStatusName.text = channel.name

            // Regex-based verification display
            if (channel.isValidFormat) {
                tvStatusRegex.text = "✓ 格式合法"
                tvStatusRegex.setTextColor(0xFF05D9E8.toInt()) // Cyan
            } else {
                tvStatusRegex.text = "⚠ 格式不符"
                tvStatusRegex.setTextColor(0xFFFF2A6D.toInt()) // Neon Red
            }

            // Connectivity display
            when (channel.status) {
                ChannelStatus.ONLINE -> {
                    tvStatusConnection.text = "🟢 可播放"
                    tvStatusConnection.setTextColor(0xFF22C55E.toInt()) // Green
                }
                ChannelStatus.OFFLINE -> {
                    tvStatusConnection.text = "🔴 无法使用"
                    tvStatusConnection.setTextColor(0xFFFF2A6D.toInt()) // Red
                }
                ChannelStatus.PENDING -> {
                    tvStatusConnection.text = "🟡 检测中..."
                    tvStatusConnection.setTextColor(0xFFEAB308.toInt()) // Yellow
                }
            }
        }
    }
}
