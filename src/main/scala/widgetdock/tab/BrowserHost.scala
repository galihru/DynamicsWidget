package widgetdock.tab

import java.awt.{BorderLayout, Color, Component}
import java.nio.file.{Files, Paths}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.{JEditorPane, JPanel, JScrollPane, SwingUtilities}
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.{WebEngine, WebView}
import me.friwi.jcefmaven.{CefAppBuilder, MavenCefAppHandlerAdapter}
import netscape.javascript.JSObject
import org.cef.CefApp
import org.cef.browser.{CefBrowser, CefFrame}
import org.cef.handler.CefDisplayHandlerAdapter
import scala.util.control.NonFatal

final case class BrowserNotification(
    source: String,
    sender: String,
    body: String,
    url: String
)

trait BrowserHost:
  def component: JPanel
  def navigate(url: String): Unit
  def currentUrl: String
  def setNotificationListener(listener: BrowserNotification => Unit): Unit
  def setStatusListener(listener: String => Unit): Unit
  def setFocused(focused: Boolean): Unit
  def suspendInput(suspended: Boolean): Unit
  def dispose(): Unit

object BrowserHostFactory:
  def create(initialUrl: String): BrowserHost =
    Option(System.getProperty("widgetdock.browser.engine")).map(_.trim.toLowerCase) match
      case Some("javafx") => JavaFxBrowserHost(initialUrl)
      case Some("jcef") =>
        try JcefBrowserHost(initialUrl)
        catch case NonFatal(_) => JavaFxBrowserHost(initialUrl)
      case _ =>
        try JcefBrowserHost(initialUrl)
        catch case NonFatal(_) => JavaFxBrowserHost(initialUrl)

object JavaFxRuntime:
  private val initialized = AtomicBoolean(false)

  def ensureInitialized(): Unit =
    if initialized.compareAndSet(false, true) then
      System.setProperty("prism.order", "sw")
      System.setProperty("javafx.animation.fullspeed", "true")
      new JFXPanel()
      Platform.setImplicitExit(false)

object JcefRuntime:
  private val lock = Object()
  private var appOpt: Option[CefApp] = None

  def app(): CefApp =
    lock.synchronized {
      appOpt.getOrElse {
        val installDir = Paths.get(
          Option(System.getenv("LOCALAPPDATA"))
            .getOrElse(System.getProperty("java.io.tmpdir")),
          "WidgetDockPro",
          "jcef-bundle"
        )
        Files.createDirectories(installDir)

        val builder = CefAppBuilder()
        builder.setInstallDir(installDir.toFile)
        builder.getCefSettings.windowless_rendering_enabled = false
        builder.addJcefArgs("--disable-gpu")
        builder.addJcefArgs("--disable-gpu-compositing")
        builder.addJcefArgs("--disable-features=OnDeviceModel")
        builder.setAppHandler(new MavenCefAppHandlerAdapter:
          override def stateHasChanged(state: CefApp.CefAppState): Unit = ()
        )

        val app = builder.build()
        appOpt = Some(app)
        app
      }
    }

final class SwingHtmlBrowserHost(initialUrl: String) extends BrowserHost:
  private val delegate = BrowserHostFactory.create(initialUrl)

  override def component: JPanel = delegate.component
  override def navigate(url: String): Unit = delegate.navigate(url)
  override def currentUrl: String = delegate.currentUrl
  override def setNotificationListener(listener: BrowserNotification => Unit): Unit =
    delegate.setNotificationListener(listener)
  override def setStatusListener(listener: String => Unit): Unit =
    delegate.setStatusListener(listener)
  override def setFocused(focused: Boolean): Unit = delegate.setFocused(focused)
  override def suspendInput(suspended: Boolean): Unit = delegate.suspendInput(suspended)
  override def dispose(): Unit = delegate.dispose()

private final class JcefBrowserHost(initialUrl: String) extends BrowserHost:
  private val panel = JPanel(BorderLayout())
  private var current: String = BrowserHostUtil.normalizeUrl(initialUrl)
  private var listener: BrowserNotification => Unit = _ => ()
  private var statusListener: String => Unit = _ => ()

  private val client = JcefRuntime.app().createClient()
  private val browser = client.createBrowser(current, false, false)
  private val browserUi = browser.getUIComponent.asInstanceOf[Component]

  initUi()

  override def component: JPanel = panel

  override def navigate(url: String): Unit =
    current = BrowserHostUtil.normalizeUrl(url)
    emitStatus(s"Loading: $current")
    try browser.loadURL(current)
    catch case NonFatal(err) => emitStatus(s"Load failed: ${err.getMessage}")

  override def currentUrl: String = current

  override def setNotificationListener(next: BrowserNotification => Unit): Unit =
    listener = Option(next).getOrElse(_ => ())

  override def setStatusListener(next: String => Unit): Unit =
    statusListener = Option(next).getOrElse(_ => ())

  override def setFocused(focused: Boolean): Unit =
    try
      browserUi.setFocusable(focused)
      browser.setFocus(focused)
    catch case _: Throwable => ()

  override def suspendInput(suspended: Boolean): Unit =
    try
      browserUi.setEnabled(!suspended)
      if suspended then
        browser.setFocus(false)
        browserUi.setFocusable(false)
      else
        browserUi.setFocusable(true)
    catch case _: Throwable => ()

  override def dispose(): Unit =
    try browser.stopLoad()
    catch case _: Throwable => ()
    try client.dispose()
    catch case _: Throwable => ()

  private def initUi(): Unit =
    panel.setBackground(new Color(10, 14, 20))
    browserUi.setFocusable(true)
    panel.add(browserUi, BorderLayout.CENTER)
    browserUi.addMouseListener(new java.awt.event.MouseAdapter:
      override def mousePressed(e: java.awt.event.MouseEvent): Unit =
        setFocused(true)
      override def mouseReleased(e: java.awt.event.MouseEvent): Unit =
        setFocused(true)
    )

    client.addDisplayHandler(new CefDisplayHandlerAdapter:
      override def onAddressChange(b: CefBrowser, frame: CefFrame, url: String): Unit =
        val nextUrl = Option(url).map(_.trim).getOrElse("")
        if nextUrl.nonEmpty then current = nextUrl
        emitStatus(s"Loaded: $current")

      override def onTitleChange(b: CefBrowser, title: String): Unit =
        val txt = Option(title).map(_.trim).getOrElse("")
        BrowserHostUtil.parseTitleNotification(txt, current).foreach(emitNotification)
    )

    emitStatus("JCEF browser ready.")

  private def emitNotification(notification: BrowserNotification): Unit =
    try listener(notification)
    catch case NonFatal(_) => ()

  private def emitStatus(message: String): Unit =
    try statusListener(message)
    catch case NonFatal(_) => ()

private final class JavaFxBrowserHost(initialUrl: String) extends BrowserHost:

  private val panel = JPanel(BorderLayout())
  private var current: String = BrowserHostUtil.normalizeUrl(initialUrl)
  private var listener: BrowserNotification => Unit = _ => ()
  private var statusListener: String => Unit = _ => ()
  private var fallbackMode = false

  private var fxPanel: JFXPanel = _
  private var engineOpt: Option[WebEngine] = None

  private val fallbackEditor = JEditorPane()
  fallbackEditor.setEditable(false)
  fallbackEditor.setContentType("text/html")

  initUi()
  navigate(current)

  override def component: JPanel = panel

  override def navigate(url: String): Unit =
    current = BrowserHostUtil.normalizeUrl(url)
    emitStatus(s"Loading: $current")
    if fallbackMode then renderFallbackPage(current)
    else
      engineOpt match
        case Some(engine) =>
          Platform.runLater(() => engine.load(current))
        case None =>
          emitStatus("Browser engine belum siap. Coba lagi...")

  override def currentUrl: String = current

  override def setNotificationListener(next: BrowserNotification => Unit): Unit =
    listener = Option(next).getOrElse(_ => ())

  override def setStatusListener(next: String => Unit): Unit =
    statusListener = Option(next).getOrElse(_ => ())

  override def setFocused(focused: Boolean): Unit =
    if fxPanel != null && focused then
      SwingUtilities.invokeLater(() => fxPanel.requestFocusInWindow())

  override def suspendInput(suspended: Boolean): Unit =
    if fxPanel != null then
      SwingUtilities.invokeLater(() => {
        fxPanel.setEnabled(!suspended)
        if !suspended then fxPanel.setFocusable(true)
      })

  override def dispose(): Unit = ()

  private def initUi(): Unit =
    panel.setBackground(new Color(10, 14, 20))

    try
      JavaFxRuntime.ensureInitialized()
      fxPanel = new JFXPanel()
      fxPanel.setFocusable(true)
      panel.add(fxPanel, BorderLayout.CENTER)
      initJavaFxWebView()
      emitStatus("JavaFX browser ready.")
    catch
      case NonFatal(err) =>
        fallbackMode = true
        panel.removeAll()
        panel.add(JScrollPane(fallbackEditor), BorderLayout.CENTER)
        renderFallbackPage(current)
        emitNotification(
          BrowserNotification(
            source = "System",
            sender = "WidgetDockPro",
            body = s"JavaFX WebView unavailable: ${err.getMessage}",
            url = current
          )
        )
        emitStatus(s"WebView unavailable: ${err.getMessage}")

  private def initJavaFxWebView(): Unit =
    val ready = CountDownLatch(1)
    Platform.runLater(() => {
      try
        val webView = new WebView()
        webView.setContextMenuEnabled(true)
        val engine = webView.getEngine
        engine.setJavaScriptEnabled(true)
        engine.setUserAgent(
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 WidgetDockPro"
        )

        engine.titleProperty().addListener((_, _, newTitle) => {
          val title = Option(newTitle).map(_.trim).getOrElse("")
          BrowserHostUtil.parseTitleNotification(title, current).foreach(emitNotification)
        })

        engine.getLoadWorker.stateProperty().addListener((_, _, next) => {
          next match
            case Worker.State.SCHEDULED =>
              emitStatus(s"Navigating: ${Option(engine.getLocation).getOrElse(current)}")
            case Worker.State.RUNNING =>
              emitStatus(s"Loading: ${Option(engine.getLocation).getOrElse(current)}")
            case Worker.State.SUCCEEDED =>
              current = Option(engine.getLocation).getOrElse(current)
              injectNotificationBridge(engine)
              emitStatus(s"Loaded: $current")
            case Worker.State.FAILED =>
              val err =
                Option(engine.getLoadWorker.getException).map(_.getMessage).getOrElse("Unknown load error")
              emitStatus(s"Load failed: $err")
            case Worker.State.CANCELLED =>
              emitStatus("Load cancelled.")
            case _ =>
              ()
        })

        engine.setOnAlert(evt => {
          val msg = Option(evt.getData).getOrElse("").trim
          if msg.nonEmpty then
            emitNotification(
              BrowserNotification(
                source = BrowserHostUtil.sourceFromUrl(Option(engine.getLocation).getOrElse(current)),
                sender = BrowserHostUtil.sourceFromUrl(Option(engine.getLocation).getOrElse(current)),
                body = msg,
                url = Option(engine.getLocation).getOrElse(current)
              )
            )
        })

        fxPanel.setScene(Scene(webView))
        engineOpt = Some(engine)
      catch
        case NonFatal(err) =>
          fallbackMode = true
          SwingUtilities.invokeLater(() => {
            panel.removeAll()
            panel.add(JScrollPane(fallbackEditor), BorderLayout.CENTER)
            panel.revalidate()
            panel.repaint()
            renderFallbackPage(current)
          })
          emitNotification(
            BrowserNotification(
              source = "System",
              sender = "WidgetDockPro",
              body = s"Failed to initialize WebView: ${err.getMessage}",
              url = current
            )
          )
          emitStatus(s"Failed to initialize WebView: ${err.getMessage}")
      finally ready.countDown()
    })
    ready.await()

  private def injectNotificationBridge(engine: WebEngine): Unit =
    try
      val window = engine.executeScript("window").asInstanceOf[JSObject]
      window.setMember("widgetDockBridge", JsBridge())
      engine.executeScript(
        """
          |(function() {
          |  if (window.__widgetDockBridgeInstalled) return;
          |  window.__widgetDockBridgeInstalled = true;
          |
          |  function sendToHost(title, body) {
          |    try {
          |      if (window.widgetDockBridge) {
          |        window.widgetDockBridge.notify(
          |          String(title || ""),
          |          String(body || ""),
          |          String(document.location.href || ""),
          |          String(document.title || "")
          |        );
          |      }
          |    } catch (e) {}
          |  }
          |
          |  const NativeNotification = window.Notification;
          |  if (NativeNotification) {
          |    function WrappedNotification(title, options) {
          |      const body = options && options.body ? options.body : "";
          |      sendToHost(title, body);
          |      return new NativeNotification(title, options);
          |    }
          |    WrappedNotification.prototype = NativeNotification.prototype;
          |    WrappedNotification.permission = "granted";
          |    WrappedNotification.requestPermission = function() {
          |      return Promise.resolve("granted");
          |    };
          |    window.Notification = WrappedNotification;
          |  }
          |
          |  let lastTitle = document.title;
          |  setInterval(function() {
          |    if (document.title !== lastTitle) {
          |      const current = document.title;
          |      lastTitle = current;
          |      sendToHost(current, "");
          |    }
          |  }, 1000);
          |})();
          |""".stripMargin
      )
    catch case NonFatal(_) => ()

  private def emitNotification(notification: BrowserNotification): Unit =
    try listener(notification)
    catch case NonFatal(_) => ()

  private def emitStatus(message: String): Unit =
    try statusListener(message)
    catch case NonFatal(_) => ()

  private def renderFallbackPage(url: String): Unit =
    fallbackEditor.setText(
      s"""
         |<html>
         |  <body style="font-family:Segoe UI,Arial; padding:16px; background:#0f172a; color:#e2e8f0;">
         |    <h3 style="margin-top:0;">Browser Fallback</h3>
         |    <p>URL: <b>$url</b></p>
         |    <p>JCEF dan JavaFX WebView tidak tersedia di mesin ini.</p>
         |  </body>
         |</html>
         |""".stripMargin
    )

  private final class JsBridge:
    def notify(title: String, body: String, url: String, pageTitle: String): Unit =
      val srcUrl = Option(url).filter(_.nonEmpty).getOrElse(current)
      val source = BrowserHostUtil.sourceFromUrl(srcUrl)
      val sender =
        Option(title).map(_.trim).filter(_.nonEmpty).getOrElse(source)
      val preview = Option(body).map(_.trim).filter(_.nonEmpty).getOrElse(
        Option(pageTitle).map(_.trim).filter(_.nonEmpty).getOrElse("Notifikasi baru")
      )
      emitNotification(BrowserNotification(source, sender, preview, srcUrl))

private object BrowserHostUtil:
  def parseTitleNotification(title: String, currentUrl: String): Option[BrowserNotification] =
    if title.isEmpty then None
    else
      val unreadCount = """^\((\d+)\)\s*(.+)$""".r
      title match
        case unreadCount(_, remaining) =>
          val source = sourceFromUrl(currentUrl)
          val (sender, body) = splitSenderBody(remaining)
          Some(BrowserNotification(source, sender, body, currentUrl))
        case _ =>
          None

  def sourceFromUrl(url: String): String =
    val lowered = url.toLowerCase
    if lowered.contains("messenger.com") then "Messenger"
    else if lowered.contains("web.whatsapp.com") then "WhatsApp"
    else if lowered.contains("telegram") then "Telegram"
    else if lowered.contains("mail.google.com") then "Gmail"
    else if lowered.contains("youtube.com") then "YouTube"
    else if lowered.contains("x.com") || lowered.contains("twitter.com") then "X"
    else hostLabel(url)

  private def hostLabel(url: String): String =
    try
      val u = java.net.URI(url)
      Option(u.getHost).map(_.replace("www.", "")).getOrElse("Browser")
    catch case _: Throwable => "Browser"

  private def splitSenderBody(text: String): (String, String) =
    val separators = Array(" - ", ":", " | ")
    separators.iterator
      .map(sep => text.split(java.util.regex.Pattern.quote(sep), 2))
      .find(_.length == 2) match
      case Some(arr) => (arr(0).trim, arr(1).trim)
      case None      => (text.take(32), text.take(120))

  def normalizeUrl(input: String): String =
    val trimmed = input.trim
    if trimmed.startsWith("http://") || trimmed.startsWith("https://") then trimmed
    else if trimmed.contains(" ") then
      s"https://www.google.com/search?q=${trimmed.replace(" ", "+")}"
    else s"https://$trimmed"
