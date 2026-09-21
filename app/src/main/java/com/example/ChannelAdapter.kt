package com.example

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(
    private var channels: List<Channel>,
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelDoubleClick: ((Channel) -> Unit)? = null
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private var selectedPosition = RecyclerView.NO_POSITION

    fun updateChannels(newChannels: List<Channel>) {
        this.channels = newChannels
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = channels[position]
        holder.bind(channel, position == selectedPosition)
    }

    override fun getItemCount(): Int = channels.size

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvChannelName: TextView = itemView.findViewById(R.id.tv_channel_name)
        private val vStatusIndicator: View = itemView.findViewById(R.id.v_status_indicator)

        fun bind(channel: Channel, isSelected: Boolean) {
            tvChannelName.text = channel.name

            // Update status indicator dot color based on channel status
            when (channel.status) {
                ChannelStatus.ONLINE -> vStatusIndicator.background?.setTint(0xFF22C55E.toInt()) // Green
                ChannelStatus.OFFLINE -> vStatusIndicator.background?.setTint(0xFFFF2A6D.toInt()) // Red
                ChannelStatus.PENDING -> vStatusIndicator.background?.setTint(0xFFEAB308.toInt()) // Yellow
            }

            // Ensure focusability of the list item
            itemView.isFocusable = true
            itemView.isClickable = true
            itemView.isSelected = isSelected

            // Set initial state based on selection & focus
            if (itemView.isFocused) {
                tvChannelName.setTextColor(0xFF0B0F19.toInt())
                tvChannelName.alpha = 1.0f
            } else {
                if (isSelected) {
                    tvChannelName.setTextColor(0xFF05D9E8.toInt())
                    tvChannelName.alpha = 1.0f
                } else {
                    tvChannelName.setTextColor(0xFFFFFFFF.toInt())
                    tvChannelName.alpha = 0.75f
                }
            }

            // Set up click listener with double click detection
            var lastClickTime = 0L
            itemView.setOnClickListener {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime < 400) {
                    onChannelDoubleClick?.invoke(channel)
                } else {
                    selectedPosition = adapterPosition
                    onChannelClick(channel)
                    notifyDataSetChanged()
                }
                lastClickTime = currentTime
            }

            // Remote control Focus Animation
            itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    // Smoothly scale up both TextView and card frame, set text alpha to fully opaque
                    val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1.05f)
                    val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1.05f)
                    val alpha = ObjectAnimator.ofFloat(tvChannelName, "alpha", 1.0f)
                    
                    // Programmatically invert text color to dark navy on focused bright blue
                    tvChannelName.setTextColor(0xFF0B0F19.toInt())
                    
                    AnimatorSet().apply {
                        playTogether(scaleX, scaleY, alpha)
                        duration = 180
                        start()
                    }
                } else {
                    // Smoothly scale back to normal, and fade back to unfocused state
                    val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1.0f)
                    val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1.0f)
                    val alpha = ObjectAnimator.ofFloat(tvChannelName, "alpha", if (isSelected) 1.0f else 0.75f)

                    // Restore text color based on selected state
                    if (isSelected) {
                        tvChannelName.setTextColor(0xFF05D9E8.toInt())
                    } else {
                        tvChannelName.setTextColor(0xFFFFFFFF.toInt())
                    }

                    AnimatorSet().apply {
                        playTogether(scaleX, scaleY, alpha)
                        duration = 180
                        start()
                    }
                }
            }
        }
    }
}
