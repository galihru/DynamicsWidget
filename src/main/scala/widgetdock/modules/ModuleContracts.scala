package widgetdock.modules

import widgetdock.core.{DeepLink, InboundNotification, TabKind, TabState}

trait WidgetModule:
  def id: String
  def kind: TabKind
  def title: String
  def onOpen(tab: TabState): Unit
  def onClose(tabId: String): Unit
  def onDeepLink(target: DeepLink): Boolean
  def pollNotifications(): Vector[InboundNotification]

final class WeatherModule extends WidgetModule:
  override val id: String = "weather"
  override val kind: TabKind = TabKind.Weather
  override val title: String = "Weather"
  override def onOpen(tab: TabState): Unit = ()
  override def onClose(tabId: String): Unit = ()
  override def onDeepLink(target: DeepLink): Boolean = target.module == TabKind.Weather
  override def pollNotifications(): Vector[InboundNotification] = Vector.empty

final class TodoModule extends WidgetModule:
  override val id: String = "todo"
  override val kind: TabKind = TabKind.Todo
  override val title: String = "To-Do"
  override def onOpen(tab: TabState): Unit = ()
  override def onClose(tabId: String): Unit = ()
  override def onDeepLink(target: DeepLink): Boolean = target.module == TabKind.Todo
  override def pollNotifications(): Vector[InboundNotification] = Vector.empty

final class MessagingModule extends WidgetModule:
  override val id: String = "messaging"
  override val kind: TabKind = TabKind.Messaging
  override val title: String = "Messaging"
  override def onOpen(tab: TabState): Unit = ()
  override def onClose(tabId: String): Unit = ()
  override def onDeepLink(target: DeepLink): Boolean = target.module == TabKind.Messaging
  override def pollNotifications(): Vector[InboundNotification] = Vector.empty

