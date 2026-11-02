package io.github.iamr0s.carvex

import android.service.notification.NotificationListenerService

/**
 * 只为**拿权限**而存在的监听服务：本类不处理任何通知，也不读取通知内容。
 *
 * Android 把「读取当前媒体会话」这件事挂在通知使用权上：
 * `MediaSessionManager.getActiveSessions()` 在没有授权的进程里直接抛 SecurityException，
 * 而车机上要显示的歌名/歌手正是从这里取的（见 `MediaSessionNowPlaying`）。
 *
 * 用户必须自己在「设置 → 通知使用权」里勾上本应用；系统不允许应用自行申请。
 */
class NowPlayingAccessService:NotificationListenerService()
