/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Listen Together notification action receiver — ported from vivi-music
 * (beta branch), vivi-music's ListenTogetherActionReceiver (GPL-3.0).
 */



package moe.rukamori.archivetune.listentogether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ListenTogetherActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val client = ListenTogetherClient.getInstance() ?: return
        val notifId = intent.getIntExtra(ListenTogetherClient.EXTRA_NOTIFICATION_ID, 0)


        when (intent.action) {
            // Chat replies must keep the conversation notification alive (it is
            // re-posted from the client with the new message), so they bypass
            // the blanket cancel below.
            ListenTogetherClient.ACTION_REPLY_CHAT -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                client.handleChatReplyFromNotification(
                    remoteInput?.getCharSequence(ListenTogetherClient.KEY_TEXT_REPLY)
                )
            }
            else -> {
                NotificationManagerCompat.from(context).cancel(notifId)

                when (intent.action) {
                    ListenTogetherClient.ACTION_APPROVE_JOIN -> {
                        val userId = intent.getStringExtra(ListenTogetherClient.EXTRA_USER_ID) ?: return
                        CoroutineScope(Dispatchers.IO).launch {
                            client.approveJoin(userId)
                        }
                    }
                    ListenTogetherClient.ACTION_REJECT_JOIN -> {
                        val userId = intent.getStringExtra(ListenTogetherClient.EXTRA_USER_ID) ?: return
                        CoroutineScope(Dispatchers.IO).launch {
                            client.rejectJoin(userId, null)
                        }
                    }
                    ListenTogetherClient.ACTION_APPROVE_SUGGESTION -> {
                        val suggestionId = intent.getStringExtra(ListenTogetherClient.EXTRA_SUGGESTION_ID) ?: return
                        CoroutineScope(Dispatchers.IO).launch {
                            client.approveSuggestion(suggestionId)
                        }
                    }
                    ListenTogetherClient.ACTION_REJECT_SUGGESTION -> {
                        val suggestionId = intent.getStringExtra(ListenTogetherClient.EXTRA_SUGGESTION_ID) ?: return
                        CoroutineScope(Dispatchers.IO).launch {
                            client.rejectSuggestion(suggestionId, null)
                        }
                    }
                }
            }
        }
    }
}
