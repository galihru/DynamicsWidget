package widgetdock.core

final case class InboundNotification(
    source: String,
    senderDisplay: String,
    senderAvatarPath: Option[String],
    preview: String,
    target: DeepLink,
    priority: Int = 0,
    receivedAtMs: Long = System.currentTimeMillis()
)

final case class TrayIconState(
    tooltip: String,
    badge: Int,
    iconToken: String,
    hoverTarget: Option[DeepLink],
    preview: Option[String]
)

object TrayIconState:
  val default: TrayIconState = TrayIconState(
    tooltip = "WidgetDockPro",
    badge = 0,
    iconToken = "default",
    hoverTarget = None,
    preview = None
  )

final class NotificationRouter:

  private var queue: Vector[InboundNotification] = Vector.empty

  def ingest(notification: InboundNotification): TrayIconState = synchronized {
    queue = (queue :+ notification)
      .sortBy(n => (-n.priority, -n.receivedAtMs))
      .take(50)
    snapshotState()
  }

  def clearByTarget(target: DeepLink): TrayIconState = synchronized {
    queue = queue.filterNot(_.target == target)
    snapshotState()
  }

  def clearAll(): TrayIconState = synchronized {
    queue = Vector.empty
    snapshotState()
  }

  def unreadCount: Int = synchronized(queue.size)

  def snapshotState(): TrayIconState = synchronized {
    queue.headOption match
      case Some(top) =>
        TrayIconState(
          tooltip = s"${top.source}: ${top.senderDisplay}",
          badge = queue.size,
          iconToken = top.source.toLowerCase,
          hoverTarget = Some(top.target),
          preview = Some(top.preview.take(80))
        )
      case None =>
        TrayIconState.default
  }

