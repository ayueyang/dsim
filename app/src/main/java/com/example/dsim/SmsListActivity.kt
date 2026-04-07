package com.example.dsim

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsListActivity : AppCompatActivity() {
    
    private lateinit var adapter: ConversationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_list)
        title = "信息"

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewSms)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        adapter = ConversationAdapter(emptyList())
        recyclerView.adapter = adapter

        lifecycleScope.launch {
            val dao = DsimDatabase.getDatabase(this@SmsListActivity).dsimDao()
            dao.getRecentConversationsFlow().collect { conversations ->
                withContext(Dispatchers.Main) {
                    adapter.updateData(conversations)
                    android.util.Log.d("dSIM_UI", "会话列表已刷新！当前会话数: ${conversations.size}")
                }
            }
        }
    }

    inner class ConversationAdapter(private var list: List<SmsMessage>) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {
        
        fun updateData(newList: List<SmsMessage>) {
            list = newList
            notifyDataSetChanged()
        }
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
            val tvSender: TextView = view.findViewById(R.id.tvSender)
            val tvSnippet: TextView = view.findViewById(R.id.tvSnippet)
            val tvTime: TextView = view.findViewById(R.id.tvTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val sms = list[position]
            holder.tvSender.text = sms.address
            holder.tvSnippet.text = sms.body
            holder.tvAvatar.text = sms.address.take(1).uppercase()
            
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            holder.tvTime.text = sdf.format(Date(sms.timestamp))
            
            holder.itemView.setOnClickListener {
                val intent = Intent(this@SmsListActivity, SmsChatActivity::class.java)
                intent.putExtra("CHAT_ADDRESS", sms.address)
                startActivity(intent)
            }
        }
        override fun getItemCount() = list.size
    }
}
