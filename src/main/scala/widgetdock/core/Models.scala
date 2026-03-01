package widgetdock.core

import java.awt.Point

enum WidgetVisibility:
  case Collapsed
  case Expanding
  case Expanded
  case Collapsing
  case PinnedHold

enum TabKind:
  case Browser
  case Weather
  case Todo
  case Messaging

final case class Rect(x: Int, y: Int, width: Int, height: Int):
  def contains(p: Point): Boolean =
    p.x >= x && p.x <= x + width && p.y >= y && p.y <= y + height

  def union(other: Rect): Rect =
    val minX = math.min(x, other.x)
    val minY = math.min(y, other.y)
    val maxX = math.max(x + width, other.x + other.width)
    val maxY = math.max(y + height, other.y + other.height)
    Rect(minX, minY, maxX - minX, maxY - minY)

  def inflate(px: Int): Rect =
    Rect(x - px, y - px, width + (px * 2), height + (px * 2))

final case class TabState(
    id: String,
    title: String,
    kind: TabKind,
    url: Option[String],
    pinned: Boolean = false,
    unreadCount: Int = 0,
    lastAccessMs: Long = System.currentTimeMillis()
)

final case class DeepLink(
    module: TabKind,
    tabId: Option[String] = None,
    url: Option[String] = None,
    threadId: Option[String] = None
)

final case class AppState(
    visibility: WidgetVisibility = WidgetVisibility.Collapsed,
    tabs: Vector[TabState] = Vector.empty,
    activeTabId: Option[String] = None,
    closeDeadlineMs: Option[Long] = None,
    pointerInsideInteractiveArea: Boolean = false,
    alwaysStayOpenWhenPinned: Boolean = false
)

enum WidgetEvent:
  case TrayHoverEnter(nowMs: Long)
  case TrayHoverExit(nowMs: Long)
  case PointerMoved(pointer: Point, insideInteractiveArea: Boolean, nowMs: Long)
  case OpenByDeepLink(target: DeepLink, nowMs: Long)
  case PinChanged(tabId: String, pinned: Boolean, nowMs: Long)
  case CloseTimerElapsed(nowMs: Long)
  case WindowFocusLost(nowMs: Long)
