package widgetdock.core

import java.util.concurrent.atomic.AtomicLong

final class TabManager(initialTabs: Vector[TabState]):

  private val idSeq = AtomicLong(1000L)

  private var tabs: Vector[TabState] =
    if initialTabs.nonEmpty then initialTabs
    else
      Vector(
        TabState(
          id = "tab-browser-default",
          title = "Browser",
          kind = TabKind.Browser,
          url = Some("https://duckduckgo.com"),
          pinned = true
        )
      )

  private var activeTabId: String = tabs.head.id

  def snapshot: Vector[TabState] = tabs

  def active: Option[TabState] = tabs.find(_.id == activeTabId)

  def activeId: String = activeTabId

  def addTab(
      kind: TabKind,
      title: String,
      url: Option[String] = None,
      pinned: Boolean = false
  ): TabState =
    val tab = TabState(
      id = s"tab-${idSeq.incrementAndGet()}",
      title = title,
      kind = kind,
      url = url,
      pinned = pinned
    )
    tabs = tabs :+ tab
    activeTabId = tab.id
    tab

  def removeTab(id: String): Boolean =
    val existing = tabs.exists(_.id == id)
    if !existing then false
    else
      tabs = tabs.filterNot(_.id == id)
      if tabs.isEmpty then
        val fallback = TabState(
          id = s"tab-${idSeq.incrementAndGet()}",
          title = "Browser",
          kind = TabKind.Browser,
          url = Some("https://duckduckgo.com"),
          pinned = true
        )
        tabs = Vector(fallback)
      if activeTabId == id then activeTabId = tabs.head.id
      true

  def selectTab(id: String): Boolean =
    tabs.find(_.id == id) match
      case Some(tab) =>
        tabs = tabs.map { t =>
          if t.id == id then t.copy(lastAccessMs = System.currentTimeMillis()) else t
        }
        activeTabId = tab.id
        true
      case None => false

  def pinTab(id: String, pinned: Boolean): Boolean =
    var updated = false
    tabs = tabs.map { t =>
      if t.id == id then
        updated = true
        t.copy(pinned = pinned)
      else t
    }
    updated

  def navigate(id: String, url: String): Boolean =
    var updated = false
    tabs = tabs.map { t =>
      if t.id == id then
        updated = true
        t.copy(url = Some(url), lastAccessMs = System.currentTimeMillis())
      else t
    }
    updated

  def incrementUnread(id: String, count: Int = 1): Unit =
    tabs = tabs.map { t =>
      if t.id == id then t.copy(unreadCount = t.unreadCount + count) else t
    }

  def clearUnread(id: String): Unit =
    tabs = tabs.map { t =>
      if t.id == id then t.copy(unreadCount = 0) else t
    }

