package widgetdock

import java.awt.event.{ComponentAdapter, ComponentEvent, FocusAdapter, FocusEvent, KeyAdapter, KeyEvent, MouseAdapter, MouseEvent}
import java.awt.{
  AWTEvent,
  BorderLayout,
  CardLayout,
  Color,
  Cursor,
  Dimension,
  FlowLayout,
  Font,
  Graphics,
  Graphics2D,
  GraphicsEnvironment,
  Insets,
  KeyboardFocusManager,
  MouseInfo,
  Point,
  Rectangle,
  RenderingHints,
  Toolkit,
  Window
}
import java.awt.event.AWTEventListener
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.{Files, Path, Paths}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.{
  BorderFactory,
  Box,
  BoxLayout,
  ButtonGroup,
  JCheckBox,
  JComponent,
  JLabel,
  ImageIcon,
  JFrame,
  JScrollPane,
  JPanel,
  JPopupMenu,
  JTextArea,
  JTextField,
  JWindow,
  JMenuItem,
  ScrollPaneConstants,
  SwingUtilities,
  Timer,
  UIManager
}
import javax.swing.event.{DocumentEvent, DocumentListener}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import widgetdock.core.{
  AppState,
  DeepLink,
  HoverStateMachine,
  InboundNotification,
  NotificationRouter,
  Rect,
  TabKind,
  TabManager,
  TabState,
  TrayIconState,
  WidgetEvent,
  WidgetVisibility
}
import widgetdock.modules.{DailyForecast, TodoItem, TodoStore, UrlDraftStore, WeatherService}
import widgetdock.tab.{BrowserNotification, SwingHtmlBrowserHost}
import widgetdock.ui.{
  AnchorBubbleLabel,
  BlurGlassPanel,
  PillButton,
  PillToggleButton,
  RemoteIconCache,
  WidgetTheme
}

object Main:

  private val AnchorBubbleSize = 46
  private val AnchorWindowSize = 50
  private val MainWindowWidth = 440
  private val MainWindowHeight = 640
  private val PointerPollMs = 40
  private val CloseTickMs = 60

  private val keepAliveLatch = CountDownLatch(1)
  private val shuttingDown = AtomicBoolean(false)

  private var appLockFile: RandomAccessFile = _
  private var appLock: FileLock = _

  private val tabManager = new TabManager(
    Vector(
      TabState(
        id = "tab-browser",
        title = "Browser",
        kind = TabKind.Browser,
        url = Some("https://www.google.com"),
        pinned = true
      ),
      TabState(
        id = "tab-weather",
        title = "Weather",
        kind = TabKind.Weather,
        url = None
      ),
      TabState(
        id = "tab-todo",
        title = "To-Do",
        kind = TabKind.Todo,
        url = None
      ),
      TabState(
        id = "tab-msg",
        title = "Messaging",
        kind = TabKind.Messaging,
        url = None
      )
    )
  )

  private val notificationRouter = NotificationRouter()

  private var appState = AppState(
    visibility = WidgetVisibility.Collapsed,
    tabs = tabManager.snapshot,
    activeTabId = Some(tabManager.activeId)
  )

  private var trayState: TrayIconState = TrayIconState.default

  private var anchorWindow: JWindow = _
  private var mainWindow: JFrame = _
  private var anchorLabel: AnchorBubbleLabel = _
  private var glassRoot: BlurGlassPanel = _
  private var searchField: JTextField = _
  private var browserStatusLabel: JLabel = _
  private var pinToggle: PillToggleButton = _
  private var tabStripPanel: JPanel = _
  private var tabCardsPanel: JPanel = _
  private var tabCardsLayout: CardLayout = _

  private var pointerTimer: Timer = _
  private var closeTickTimer: Timer = _

  private val browserHosts = mutable.Map.empty[String, SwingHtmlBrowserHost]
  private val browserNotificationBound = mutable.Set.empty[String]
  private val modulePanels = mutable.Map.empty[String, JPanel]
  private val todoStore = TodoStore.defaultStore()
  private val urlDraftStore = UrlDraftStore.defaultStore()
  private var messagingArea: JTextArea = _
  private var messagingLines: Vector[String] = Vector.empty
  private var lastNotifSig: String = ""
  private var lastNotifMs: Long = 0L
  private var tabOrder: Vector[String] = Vector.empty
  private var rebuildingTabs = false
  private var searchEditing = false
  private var globalMouseBridgeInstalled = false
  private var suppressSearchEvents = false
  private given ExecutionContext = ExecutionContext.global
  private val weatherClockFmt = DateTimeFormatter.ofPattern("HH:mm")
  private val weatherDateFmt = DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.ENGLISH)

  def main(args: Array[String]): Unit =
    if !acquireSingleInstance() then
      println("WidgetDockPro sudah berjalan. Tutup instance lama dulu.")
      return

    installShutdownHook()
    SwingUtilities.invokeLater(() => launch())

    try keepAliveLatch.await()
    catch case _: InterruptedException => ()

  private def launch(): Unit =
    // Cross-platform LAF keeps contrast more predictable than system overrides.
    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName)
    createMainWindow()
    createAnchorWindow()
    rebuildTabs()
    syncPinToggle()
    applyTrayState(trayState)
    syncUiWithState()
    installGlobalMouseBridge()
    startPointerPolling()
    startCloseTick()

  private def createMainWindow(): Unit =
    mainWindow = JFrame()
    mainWindow.setUndecorated(true)
    mainWindow.setAlwaysOnTop(true)
    mainWindow.setFocusable(true)
    mainWindow.setFocusableWindowState(true)
    mainWindow.setAutoRequestFocus(true)
    mainWindow.setSize(MainWindowWidth, MainWindowHeight)
    mainWindow.setBackground(new Color(14, 18, 26))

    glassRoot = BlurGlassPanel(radius = 28, blurRadius = 9)
    glassRoot.setLayout(BorderLayout())
    glassRoot.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14))

    val content = JPanel(BorderLayout(0, 12))
    content.setOpaque(false)
    content.add(buildHeader(), BorderLayout.NORTH)
    content.add(buildMainContent(), BorderLayout.CENTER)

    glassRoot.add(content, BorderLayout.CENTER)
    mainWindow.setContentPane(glassRoot)
    positionWindows()

    mainWindow.addWindowFocusListener(new java.awt.event.WindowFocusListener:
      override def windowGainedFocus(e: java.awt.event.WindowEvent): Unit = ()
      override def windowLostFocus(e: java.awt.event.WindowEvent): Unit =
        dispatch(WidgetEvent.WindowFocusLost(System.currentTimeMillis()))
    )

    mainWindow.addComponentListener(new ComponentAdapter:
      override def componentMoved(e: ComponentEvent): Unit = refreshBackdrop()
      override def componentResized(e: ComponentEvent): Unit = refreshBackdrop()
      override def componentShown(e: ComponentEvent): Unit = refreshBackdrop()
    )

  private def buildHeader(): JPanel =
    val panel = JPanel(BorderLayout())
    panel.setOpaque(false)

    val left = JPanel()
    left.setOpaque(false)
    left.setLayout(BoxLayout(left, BoxLayout.Y_AXIS))

    val title = JLabel("Widgets")
    title.setFont(WidgetTheme.TitleFont)
    title.setForeground(WidgetTheme.TextPrimary)

    val subtitle = JLabel("Hover icon taskbar kiri-bawah untuk buka otomatis")
    subtitle.setFont(WidgetTheme.SubtitleFont)
    subtitle.setForeground(WidgetTheme.TextSecondary)

    left.add(title)
    left.add(Box.createVerticalStrut(2))
    left.add(subtitle)

    val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
    right.setOpaque(false)

    val status = JLabel("LIVE")
    status.setOpaque(true)
    status.setBackground(new Color(56, 189, 248, 90))
    status.setForeground(new Color(222, 247, 255))
    status.setFont(Font("Segoe UI", Font.BOLD, 11))
    status.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createLineBorder(new Color(186, 230, 253, 150), 1, true),
      BorderFactory.createEmptyBorder(2, 8, 2, 8)
    ))

    val closeButton = PillButton("Exit")
    closeButton.setPreferredSize(Dimension(58, 28))
    closeButton.addActionListener(_ => shutdownAndExit(triggerSystemExit = true))

    right.add(status)
    right.add(closeButton)

    panel.add(left, BorderLayout.WEST)
    panel.add(right, BorderLayout.EAST)
    panel

  private def buildMainContent(): JPanel =
    val body = JPanel(BorderLayout(0, 10))
    body.setOpaque(false)

    val topStack = JPanel()
    topStack.setOpaque(false)
    topStack.setLayout(BoxLayout(topStack, BoxLayout.Y_AXIS))

    val searchRow = JPanel(BorderLayout(8, 0))
    searchRow.setOpaque(false)

    searchField = JTextField()
    searchField.setToolTipText("Ketik URL atau kata pencarian")
    searchField.setEditable(true)
    searchField.setEnabled(true)
    searchField.setFocusable(true)
    searchField.setRequestFocusEnabled(true)
    searchField.addMouseListener(new MouseAdapter:
      override def mousePressed(e: MouseEvent): Unit =
        beginSearchEditing()
      override def mouseReleased(e: MouseEvent): Unit =
        beginSearchEditing()
    )
    searchField.addFocusListener(new FocusAdapter:
      override def focusGained(e: FocusEvent): Unit =
        beginSearchEditing()
        val pos = searchField.getText.length
        searchField.setCaretPosition(pos)
      override def focusLost(e: FocusEvent): Unit =
        ()
    )
    searchField.addKeyListener(new KeyAdapter:
      override def keyTyped(e: KeyEvent): Unit =
        beginSearchEditing()
    )
    searchField.getDocument.addDocumentListener(new DocumentListener:
      override def insertUpdate(e: DocumentEvent): Unit = onSearchTextEdited()
      override def removeUpdate(e: DocumentEvent): Unit = onSearchTextEdited()
      override def changedUpdate(e: DocumentEvent): Unit = onSearchTextEdited()
    )
    searchField.addActionListener(_ => navigateFromSearch())
    styleSearchField(searchField)
    syncSearchFieldWithActiveBrowser()

    val goButton = PillButton("Go", accent = true)
    goButton.setPreferredSize(Dimension(56, 34))
    goButton.addActionListener(_ =>
      if mainWindow != null then mainWindow.requestFocusInWindow()
      navigateFromSearch()
    )

    searchRow.add(searchField, BorderLayout.CENTER)
    val searchActions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
    searchActions.setOpaque(false)
    searchActions.add(goButton)
    searchRow.add(searchActions, BorderLayout.EAST)

    browserStatusLabel = JLabel("Browser ready.")
    browserStatusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11))
    browserStatusLabel.setForeground(new Color(191, 219, 254))

    val controls = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
    controls.setOpaque(false)

    val addTabButton = PillButton("+Tab")
    addTabButton.setPreferredSize(Dimension(64, 32))
    addTabButton.addActionListener(_ => addBrowserTab())

    val removeTabButton = PillButton("-Tab")
    removeTabButton.setPreferredSize(Dimension(64, 32))
    removeTabButton.addActionListener(_ => removeActiveTab())

    pinToggle = PillToggleButton("Pin")
    pinToggle.setPreferredSize(Dimension(64, 32))
    pinToggle.addActionListener(_ => togglePin())

    controls.add(addTabButton)
    controls.add(removeTabButton)
    controls.add(pinToggle)

    topStack.add(searchRow)
    topStack.add(Box.createVerticalStrut(6))
    topStack.add(browserStatusLabel)
    topStack.add(Box.createVerticalStrut(8))
    topStack.add(controls)

    tabStripPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
    tabStripPanel.setOpaque(false)

    tabCardsLayout = CardLayout()
    tabCardsPanel = JPanel(tabCardsLayout)
    tabCardsPanel.setOpaque(false)
    tabCardsPanel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0))

    val tabsArea = JPanel(BorderLayout(0, 8))
    tabsArea.setOpaque(false)
    tabsArea.add(tabStripPanel, BorderLayout.NORTH)
    tabsArea.add(tabCardsPanel, BorderLayout.CENTER)

    body.add(topStack, BorderLayout.NORTH)
    body.add(tabsArea, BorderLayout.CENTER)
    body

  private def styleSearchField(field: JTextField): Unit =
    field.setFont(WidgetTheme.UiFont)
    field.setEditable(true)
    field.setOpaque(true)
    field.setForeground(WidgetTheme.TextPrimary)
    field.setDisabledTextColor(new Color(203, 213, 225))
    field.setCaretColor(WidgetTheme.TextPrimary)
    field.setSelectionColor(new Color(59, 130, 246, 170))
    field.setSelectedTextColor(Color.WHITE)
    field.setBackground(new Color(10, 14, 20, 220))
    field.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createLineBorder(new Color(255, 255, 255, 110), 1, true),
      BorderFactory.createEmptyBorder(8, 10, 8, 10)
    ))

  private def createAnchorWindow(): Unit =
    anchorWindow = JWindow()
    anchorWindow.setAlwaysOnTop(true)
    anchorWindow.setFocusableWindowState(false)
    anchorWindow.setAutoRequestFocus(false)
    anchorWindow.setSize(AnchorWindowSize, AnchorWindowSize)
    anchorWindow.setBackground(new Color(0, 0, 0, 0))

    anchorLabel = AnchorBubbleLabel("WD")
    anchorLabel.setPreferredSize(Dimension(AnchorBubbleSize, AnchorBubbleSize))
    anchorLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
    anchorLabel.setToolTipText("WidgetDockPro")

    val menu = JPopupMenu()
    val openItem = JMenuItem("Open")
    openItem.addActionListener(_ => dispatch(WidgetEvent.TrayHoverEnter(System.currentTimeMillis())))
    val collapseItem = JMenuItem("Collapse")
    collapseItem.addActionListener(_ =>
      appState = appState.copy(visibility = WidgetVisibility.Collapsed, closeDeadlineMs = None)
      syncUiWithState()
    )
    val exitItem = JMenuItem("Exit WidgetDockPro")
    exitItem.addActionListener(_ => shutdownAndExit(triggerSystemExit = true))
    menu.add(openItem)
    menu.add(collapseItem)
    menu.addSeparator()
    menu.add(exitItem)

    anchorLabel.addMouseListener(new MouseAdapter:
      override def mouseEntered(e: MouseEvent): Unit =
        val now = System.currentTimeMillis()
        trayState.hoverTarget match
          case Some(target) =>
            dispatch(WidgetEvent.OpenByDeepLink(target, now))
            trayState = notificationRouter.clearByTarget(target)
            applyTrayState(trayState)
          case None =>
            dispatch(WidgetEvent.TrayHoverEnter(now))

      override def mouseExited(e: MouseEvent): Unit =
        dispatch(WidgetEvent.TrayHoverExit(System.currentTimeMillis()))

      override def mousePressed(e: MouseEvent): Unit = maybeShowMenu(e)
      override def mouseReleased(e: MouseEvent): Unit = maybeShowMenu(e)

      private def maybeShowMenu(e: MouseEvent): Unit =
        if e.isPopupTrigger then menu.show(e.getComponent, e.getX, e.getY)
    )

    val root = JPanel(BorderLayout())
    root.setOpaque(false)
    root.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2))
    root.add(anchorLabel, BorderLayout.CENTER)
    anchorWindow.setContentPane(root)

    positionWindows()
    anchorWindow.setVisible(true)

  private def addBrowserTab(): Unit =
    tabManager.addTab(
      kind = TabKind.Browser,
      title = "Browser",
      url = Some("https://www.google.com")
    )
    rebuildTabs()
    syncAppStateFromManager()
    dispatch(WidgetEvent.TrayHoverEnter(System.currentTimeMillis()))

  private def removeActiveTab(): Unit =
    val currentId = tabManager.activeId
    val ok = tabManager.removeTab(currentId)
    if ok then
      browserHosts.remove(currentId).foreach(_.dispose())
      browserNotificationBound -= currentId
      modulePanels.remove(currentId)
      urlDraftStore.remove(currentId)
      rebuildTabs()
      syncAppStateFromManager()
      syncPinToggle()

  private def togglePin(): Unit =
    val currentId = tabManager.activeId
    val pinned = pinToggle.isSelected
    if tabManager.pinTab(currentId, pinned) then
      dispatch(WidgetEvent.PinChanged(currentId, pinned, System.currentTimeMillis()))
      rebuildTabs()

  private def navigateFromSearch(): Unit =
    if searchField == null then return
    val text = searchField.getText.trim
    if text.isEmpty then
      setBrowserStatus("URL kosong. Ketik alamat lalu tekan Go.")
      return

    val current = tabManager.active.getOrElse(
      tabManager.addTab(TabKind.Browser, "Browser", Some("https://www.google.com"))
    )

    val browserTabId =
      if current.kind == TabKind.Browser then current.id
      else tabManager.addTab(TabKind.Browser, "Browser", Some("https://www.google.com")).id

    tabManager.selectTab(browserTabId)
    urlDraftStore.remove(browserTabId)
    endSearchEditing()
    tabManager.navigate(browserTabId, text)
    val host = browserHosts
      .getOrElseUpdate(
        browserTabId,
        SwingHtmlBrowserHost("https://www.google.com")
      )
    if !browserNotificationBound.contains(browserTabId) then
      host.setNotificationListener(note => onBrowserNotification(browserTabId, note))
      host.setStatusListener(msg => setBrowserStatus(msg))
      browserNotificationBound += browserTabId
    host.navigate(text)
    val committedUrl = host.currentUrl
    setSearchFieldTextSilently(committedUrl)
    setBrowserStatus(s"Loading: $committedUrl")
    host.setFocused(true)

    rebuildTabs()
    syncAppStateFromManager()

  private def setBrowserStatus(message: String): Unit =
    SwingUtilities.invokeLater(() => {
      if browserStatusLabel != null then
        val short = if message.length > 120 then message.take(117) + "..." else message
        browserStatusLabel.setText(short)
    })

  private def onSearchTextEdited(): Unit =
    if suppressSearchEvents || searchField == null then return
    if !searchField.hasFocus && !searchEditing then return
    if !searchEditing then beginSearchEditing()
    activeBrowserTabId().foreach { tabId =>
      val draft = searchField.getText
      urlDraftStore.put(tabId, draft)
      if draft.trim.nonEmpty then
        setBrowserStatus("Draft URL tersimpan. Tekan Go/Enter untuk buka.")
    }

  private def setSearchFieldTextSilently(value: String): Unit =
    suppressSearchEvents = true
    try searchField.setText(value)
    finally suppressSearchEvents = false

  private def beginSearchEditing(): Unit =
    if searchField == null then return
    if searchEditing then
      if !searchField.hasFocus then
        SwingUtilities.invokeLater(() => {
          if searchField != null then
            searchField.requestFocusInWindow()
            val caret = searchField.getCaret
            if caret != null then
              caret.setVisible(true)
              caret.setSelectionVisible(true)
        })
      return
    searchEditing = true
    if mainWindow != null then
      mainWindow.toFront()
      mainWindow.requestFocus()
    activeBrowserHost().foreach { host =>
      host.suspendInput(true)
      host.setFocused(false)
    }
    SwingUtilities.invokeLater(() => {
      if searchField != null then
        searchField.setEditable(true)
        searchField.setEnabled(true)
        KeyboardFocusManager.getCurrentKeyboardFocusManager.clearGlobalFocusOwner()
        searchField.requestFocusInWindow()
        searchField.grabFocus()
        val caret = searchField.getCaret
        if caret != null then
          caret.setVisible(true)
          caret.setSelectionVisible(true)
    })

  private def endSearchEditing(): Unit =
    searchEditing = false
    activeBrowserHost().foreach(_.suspendInput(false))

  private def syncSearchFieldWithActiveBrowser(): Unit =
    if searchField == null then return
    tabManager.active match
      case Some(tab) if tab.kind == TabKind.Browser =>
        val draft = urlDraftStore.get(tab.id)
        val current = browserHosts.get(tab.id).map(_.currentUrl).orElse(tab.url).getOrElse("")
        val displayValue = draft.getOrElse(current)
        if displayValue.nonEmpty && !searchField.hasFocus && !searchEditing then
          setSearchFieldTextSilently(displayValue)
      case _ =>
        ()

  private def activeBrowserTabId(): Option[String] =
    tabManager.active
      .filter(_.kind == TabKind.Browser)
      .map(_.id)

  private def activeBrowserHost(): Option[SwingHtmlBrowserHost] =
    tabManager.active
      .filter(_.kind == TabKind.Browser)
      .flatMap(tab => browserHosts.get(tab.id))

  private def installGlobalMouseBridge(): Unit =
    if globalMouseBridgeInstalled then return
    val listener = new AWTEventListener:
      override def eventDispatched(event: AWTEvent): Unit =
        event match
          case me: MouseEvent if me.getID == MouseEvent.MOUSE_PRESSED =>
            val src = me.getSource
            val inSearch =
              src.isInstanceOf[java.awt.Component] &&
                searchField != null &&
                SwingUtilities.isDescendingFrom(
                  src.asInstanceOf[java.awt.Component],
                  searchField
                )
            if !inSearch && searchEditing then endSearchEditing()
          case _ =>
            ()
    Toolkit.getDefaultToolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
    globalMouseBridgeInstalled = true

  private def startPointerPolling(): Unit =
    pointerTimer = Timer(
      PointerPollMs,
      _ =>
        val pointer = Option(MouseInfo.getPointerInfo).map(_.getLocation).getOrElse(Point(0, 0))
        val inside = isInsideInteractiveArea(pointer)
        dispatch(WidgetEvent.PointerMoved(pointer, inside, System.currentTimeMillis()))
        if !searchEditing then syncSearchFieldWithActiveBrowser()
    )
    pointerTimer.setRepeats(true)
    pointerTimer.start()

  private def startCloseTick(): Unit =
    closeTickTimer = Timer(
      CloseTickMs,
      _ =>
        dispatch(WidgetEvent.CloseTimerElapsed(System.currentTimeMillis()))
    )
    closeTickTimer.setRepeats(true)
    closeTickTimer.start()

  private def dispatch(event: WidgetEvent): Unit =
    syncAppStateFromManager()
    appState = HoverStateMachine.reduce(appState, event)

    event match
      case WidgetEvent.OpenByDeepLink(target, _) => openByDeepLink(target)
      case _                                     => ()

    syncUiWithState()

  private def openByDeepLink(target: DeepLink): Unit =
    val existing = tabManager.snapshot.find(_.kind == target.module).map(_.id)
    val tabId = target.tabId.orElse(existing).getOrElse {
      val created = tabManager.addTab(
        kind = target.module,
        title = target.module.toString,
        pinned = true
      )
      created.id
    }

    tabManager.selectTab(tabId)
    tabManager.clearUnread(tabId)

    target.url.foreach { url =>
      val currentKind = tabManager.snapshot.find(_.id == tabId).map(_.kind)
      val browserTabId =
        if currentKind.contains(TabKind.Browser) then tabId
        else tabManager
          .snapshot
          .find(t => t.kind == TabKind.Browser && t.pinned)
          .map(_.id)
          .getOrElse(tabManager.addTab(TabKind.Browser, "Browser", Some(url)).id)

      val host = browserHosts.getOrElseUpdate(
        browserTabId,
        SwingHtmlBrowserHost("https://www.google.com")
      )
      if !browserNotificationBound.contains(browserTabId) then
        host.setNotificationListener(note => onBrowserNotification(browserTabId, note))
        host.setStatusListener(msg => setBrowserStatus(msg))
        browserNotificationBound += browserTabId
      host.navigate(url)
      tabManager.navigate(browserTabId, url)
      setBrowserStatus(s"Loading: ${host.currentUrl}")
    }

    trayState = notificationRouter.clearByTarget(target)
    applyTrayState(trayState)
    rebuildTabs()
    syncAppStateFromManager()

  private def syncUiWithState(): Unit =
    if mainWindow == null then return

    appState.visibility match
      case WidgetVisibility.Collapsed =>
        mainWindow.setVisible(false)
      case WidgetVisibility.Collapsing =>
        if mainWindow.isVisible then mainWindow.repaint()
      case WidgetVisibility.Expanded | WidgetVisibility.Expanding |
          WidgetVisibility.PinnedHold =>
        if !mainWindow.isVisible then
          refreshBackdrop()
          mainWindow.setVisible(true)
          mainWindow.toFront()
          mainWindow.requestFocus()
          mainWindow.requestFocusInWindow()
          if searchField != null then searchField.requestFocusInWindow()
        else
          maintainBrowserFocus()

  private def refreshBackdrop(): Unit =
    if mainWindow != null && glassRoot != null then
      val b = mainWindow.getBounds
      if b.width > 0 && b.height > 0 then
        glassRoot.refreshBackdrop(Rectangle(b.x, b.y, b.width, b.height))

  private def maintainBrowserFocus(): Unit =
    if mainWindow == null || !mainWindow.isVisible then return
    if searchField != null && (searchField.hasFocus || searchEditing) then return
    tabManager.active match
      case Some(tab) if tab.kind == TabKind.Browser =>
        activeBrowserHost().foreach(_.setFocused(true))
      case _ =>
        ()

  private def syncAppStateFromManager(): Unit =
    appState = appState.copy(
      tabs = tabManager.snapshot,
      activeTabId = Some(tabManager.activeId)
    )

  private def rebuildTabs(): Unit =
    if tabStripPanel == null || tabCardsPanel == null then return

    rebuildingTabs = true
    tabStripPanel.removeAll()
    tabCardsPanel.removeAll()

    val btnGroup = ButtonGroup()
    tabOrder = tabManager.snapshot.map(_.id)

    tabManager.snapshot.foreach { tab =>
      val component = componentFor(tab)
      tabCardsPanel.add(component, tab.id)

      val chip = PillToggleButton(renderTabTitle(tab), selectedColor = new Color(37, 99, 235, 220))
      chip.setFont(WidgetTheme.UiFont)
      chip.setPreferredSize(Dimension(96, 28))
      chip.setSelected(tab.id == tabManager.activeId)
      chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
      chip.addActionListener { _ =>
        if !rebuildingTabs then
          tabManager.selectTab(tab.id)
          tabManager.clearUnread(tab.id)
          syncAppStateFromManager()
          syncPinToggle()
          rebuildTabs()
      }

      btnGroup.add(chip)
      tabStripPanel.add(chip)
    }

    val active = tabManager.activeId
    tabCardsLayout.show(tabCardsPanel, active)

    syncPinToggle()
    syncSearchFieldWithActiveBrowser()
    tabStripPanel.revalidate()
    tabStripPanel.repaint()
    tabCardsPanel.revalidate()
    tabCardsPanel.repaint()
    rebuildingTabs = false

  private def renderTabTitle(tab: TabState): String =
    val pin = if tab.pinned then "P " else ""
    val unread = if tab.unreadCount > 0 then s" ${tab.unreadCount}" else ""
    s"$pin${tab.title}$unread"

  private def componentFor(tab: TabState): JComponent =
    tab.kind match
      case TabKind.Browser =>
        val host = browserHosts.getOrElseUpdate(
          tab.id,
          SwingHtmlBrowserHost(tab.url.getOrElse("https://www.google.com"))
        )
        if !browserNotificationBound.contains(tab.id) then
          host.setNotificationListener(note => onBrowserNotification(tab.id, note))
          host.setStatusListener(msg => setBrowserStatus(msg))
          browserNotificationBound += tab.id
        tab.url.foreach { u =>
          if u != host.currentUrl then host.navigate(u)
        }
        if tab.id == tabManager.activeId then setBrowserStatus(s"Ready: ${host.currentUrl}")
        host.component
      case _ =>
        modulePanels.getOrElseUpdate(tab.id, createModulePanel(tab.kind))

  private def createModulePanel(kind: TabKind): JPanel =
    kind match
      case TabKind.Weather   => createWeatherPanel()
      case TabKind.Todo      => createTodoPanel()
      case TabKind.Messaging => createMessagingPanel()
      case TabKind.Browser   => createInfoPanel("Browser", "Browser tab sudah menggunakan WebView real.")

  private def createInfoPanel(titleText: String, descText: String): JPanel =
    val panel = baseModulePanel()
    val title = JLabel(titleText)
    title.setFont(Font("Segoe UI", Font.BOLD, 18))
    title.setForeground(new Color(250, 252, 255))
    val desc = JLabel(descText)
    desc.setFont(WidgetTheme.UiFont)
    desc.setForeground(new Color(213, 221, 235))
    panel.add(title)
    panel.add(Box.createVerticalStrut(8))
    panel.add(desc)
    panel

  private def createWeatherPanel(): JPanel =
    val panel = baseModulePanel()
    panel.setLayout(BorderLayout(0, 10))

    val top = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
    top.setOpaque(false)

    val cityField = JTextField("Jakarta", 16)
    styleSearchField(cityField)
    cityField.setPreferredSize(Dimension(210, 34))
    cityField.addMouseListener(new MouseAdapter:
      override def mousePressed(e: MouseEvent): Unit =
        cityField.requestFocusInWindow()
    )

    val refreshButton = PillButton("Refresh", accent = true)
    refreshButton.setPreferredSize(Dimension(92, 34))

    val clockLabel = JLabel(weatherClockText())
    clockLabel.setFont(Font("Segoe UI", Font.BOLD, 12))
    clockLabel.setForeground(new Color(191, 219, 254))

    top.add(cityField)
    top.add(refreshButton)
    top.add(clockLabel)

    val clockTimer = Timer(1000, _ => clockLabel.setText(weatherClockText()))
    clockTimer.setRepeats(true)
    clockTimer.start()

    val summaryCard = RoundedCardPanel(new Color(17, 25, 40, 220), new Color(255, 255, 255, 84), 18)
    summaryCard.setLayout(BorderLayout(0, 0))
    summaryCard.setPreferredSize(Dimension(360, 150))
    summaryCard.setMaximumSize(Dimension(Int.MaxValue, 150))
    summaryCard.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createLineBorder(new Color(255, 255, 255, 84), 1, true),
      BorderFactory.createEmptyBorder(10, 12, 10, 12)
    ))

    val todayIcon = JLabel()
    todayIcon.setPreferredSize(Dimension(92, 92))
    todayIcon.setForeground(new Color(203, 213, 225))
    summaryCard.add(todayIcon, BorderLayout.WEST)

    val summaryText = JPanel()
    summaryText.setOpaque(false)
    summaryText.setLayout(BoxLayout(summaryText, BoxLayout.Y_AXIS))

    val tempLabel = JLabel("-- C")
    tempLabel.setFont(Font("Segoe UI", Font.BOLD, 32))
    tempLabel.setForeground(new Color(250, 252, 255))

    val conditionLabel = JLabel("Weather")
    conditionLabel.setFont(Font("Segoe UI", Font.BOLD, 14))
    conditionLabel.setForeground(new Color(191, 219, 254))

    val locationLabel = JLabel("Lokasi: -")
    locationLabel.setFont(WidgetTheme.UiFont)
    locationLabel.setForeground(new Color(213, 221, 235))

    val detailLabel = JLabel("Feels / Humidity / Wind: -")
    detailLabel.setFont(WidgetTheme.UiFont)
    detailLabel.setForeground(new Color(213, 221, 235))

    val updatedLabel = JLabel("Updated: -")
    updatedLabel.setFont(Font("Segoe UI", Font.PLAIN, 11))
    updatedLabel.setForeground(new Color(147, 197, 253))

    summaryText.add(tempLabel)
    summaryText.add(Box.createVerticalStrut(2))
    summaryText.add(conditionLabel)
    summaryText.add(Box.createVerticalStrut(6))
    summaryText.add(locationLabel)
    summaryText.add(Box.createVerticalStrut(4))
    summaryText.add(detailLabel)
    summaryText.add(Box.createVerticalStrut(4))
    summaryText.add(updatedLabel)
    summaryCard.add(summaryText, BorderLayout.CENTER)

    val forecastTitle = JLabel("Forecast")
    forecastTitle.setFont(Font("Segoe UI", Font.BOLD, 16))
    forecastTitle.setForeground(new Color(250, 252, 255))

    val forecastStrip = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
    forecastStrip.setOpaque(false)

    val forecastScroll = JScrollPane(forecastStrip)
    forecastScroll.setBorder(BorderFactory.createEmptyBorder())
    forecastScroll.setOpaque(false)
    forecastScroll.getViewport.setOpaque(false)
    forecastScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
    forecastScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER)
    forecastScroll.setPreferredSize(Dimension(360, 148))

    val forecastHeader = JPanel()
    forecastHeader.setOpaque(false)
    forecastHeader.setLayout(BoxLayout(forecastHeader, BoxLayout.X_AXIS))
    forecastHeader.setMaximumSize(Dimension(Int.MaxValue, 30))
    forecastTitle.setAlignmentY(0.5f)

    val prevButton = PillButton("Prev")
    prevButton.setPreferredSize(Dimension(62, 28))
    prevButton.setMaximumSize(Dimension(62, 28))
    prevButton.setAlignmentY(0.5f)
    val nextButton = PillButton("Next")
    nextButton.setPreferredSize(Dimension(62, 28))
    nextButton.setMaximumSize(Dimension(62, 28))
    nextButton.setAlignmentY(0.5f)
    forecastHeader.add(forecastTitle)
    forecastHeader.add(Box.createHorizontalGlue())
    forecastHeader.add(prevButton)
    forecastHeader.add(Box.createHorizontalStrut(6))
    forecastHeader.add(nextButton)

    def refreshForecastNav(): Unit =
      val viewport = forecastScroll.getViewport
      val view = viewport.getView
      if view == null then
        prevButton.setEnabled(false)
        nextButton.setEnabled(false)
      else
        val currentX = viewport.getViewPosition.x
        val maxX = math.max(0, view.getWidth - viewport.getWidth)
        prevButton.setEnabled(currentX > 0)
        nextButton.setEnabled(currentX < maxX)

    def shiftForecast(delta: Int): Unit =
      val viewport = forecastScroll.getViewport
      val view = viewport.getView
      if view != null then
        val current = viewport.getViewPosition
        val maxX = math.max(0, view.getWidth - viewport.getWidth)
        val nextX = math.max(0, math.min(maxX, current.x + delta))
        viewport.setViewPosition(Point(nextX, 0))
        refreshForecastNav()

    prevButton.addActionListener(_ => shiftForecast(-240))
    nextButton.addActionListener(_ => shiftForecast(240))
    forecastScroll.getViewport.addChangeListener(_ => refreshForecastNav())
    forecastScroll.addComponentListener(new ComponentAdapter:
      override def componentResized(e: ComponentEvent): Unit =
        refreshForecastNav()
    )

    val statusLabel = JLabel("Muat data...")
    statusLabel.setFont(Font("Segoe UI", Font.BOLD, 12))
    statusLabel.setForeground(new Color(147, 197, 253))

    val weatherBody = JPanel()
    weatherBody.setOpaque(false)
    weatherBody.setLayout(BoxLayout(weatherBody, BoxLayout.Y_AXIS))
    weatherBody.add(summaryCard)
    weatherBody.add(Box.createVerticalStrut(10))
    weatherBody.add(forecastHeader)
    weatherBody.add(Box.createVerticalStrut(5))
    weatherBody.add(forecastScroll)
    weatherBody.add(Box.createVerticalStrut(8))
    weatherBody.add(statusLabel)

    def load(): Unit =
      val city = cityField.getText.trim
      if city.isEmpty then
        statusLabel.setText("Isi nama kota.")
        return
      statusLabel.setText("Loading weather...")
      Future {
        WeatherService.fetchByCity(city).map { data =>
          val nowIcon = RemoteIconCache.get(data.iconUrl, 128, 128)
          val dailyIcons = data.daily.take(7).map { day =>
            day.dateIso -> RemoteIconCache.get(day.iconUrl, 48, 48)
          }.toMap
          (data, nowIcon, dailyIcons)
        }
      }.foreach { res =>
        SwingUtilities.invokeLater(() => {
          res match
            case Right((data, nowIcon, dailyIcons)) =>
              locationLabel.setText(s"Lokasi: ${data.locationLabel}")
              tempLabel.setText(
                f"${data.temperatureC}%.1f C"
              )
              conditionLabel.setText(data.condition)
              detailLabel.setText(
                f"Feels ${data.feelsLikeC}%.1f C | Humidity ${data.humidityPct}%.0f%% | Wind ${data.windKmh}%.1f km/h"
              )
              updatedLabel.setText(s"Updated: ${formatWeatherUpdate(data.updatedAtMs)}")
              todayIcon.setIcon(nowIcon.map(ic => ImageIcon(ic.getImage.getScaledInstance(86, 86, java.awt.Image.SCALE_SMOOTH))).orNull)
              if nowIcon.isEmpty then todayIcon.setText(data.condition.take(8)) else todayIcon.setText("")
              forecastStrip.removeAll()
              data.daily.take(7).foreach { day =>
                val card = forecastCard(day, dailyIcons.getOrElse(day.dateIso, None))
                forecastStrip.add(card)
              }
              forecastScroll.getViewport.setViewPosition(Point(0, 0))
              forecastStrip.revalidate()
              forecastStrip.repaint()
              refreshForecastNav()
              statusLabel.setText("Live weather: Open-Meteo forecast.")
            case Left(err) =>
              statusLabel.setText(err)
        })
      }

    cityField.addActionListener(_ => load())
    refreshButton.addActionListener(_ => load())

    panel.add(top, BorderLayout.NORTH)
    panel.add(weatherBody, BorderLayout.CENTER)
    load()
    panel

  private def createTodoPanel(): JPanel =
    val panel = baseModulePanel()
    panel.setLayout(BorderLayout(0, 10))

    val inputRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
    inputRow.setOpaque(false)

    val input = JTextField("", 16)
    styleSearchField(input)
    input.setPreferredSize(Dimension(230, 34))

    val addButton = PillButton("Tambah", accent = true)
    addButton.setPreferredSize(Dimension(86, 34))

    inputRow.add(input)
    inputRow.add(addButton)

    val listPanel = JPanel()
    listPanel.setOpaque(false)
    listPanel.setLayout(BoxLayout(listPanel, BoxLayout.Y_AXIS))

    val scroll = JScrollPane(listPanel)
    scroll.setBorder(BorderFactory.createEmptyBorder())
    scroll.getViewport.setOpaque(false)
    scroll.setOpaque(false)

    def renderList(): Unit =
      listPanel.removeAll()
      val items = todoStore.all()
      if items.isEmpty then
        val empty = JLabel("Belum ada task.")
        empty.setFont(WidgetTheme.UiFont)
        empty.setForeground(new Color(213, 221, 235))
        listPanel.add(empty)
      else
        items.foreach { item =>
          listPanel.add(todoRow(item, renderList))
          listPanel.add(Box.createVerticalStrut(6))
        }
      listPanel.revalidate()
      listPanel.repaint()

    def addTask(): Unit =
      val text = input.getText.trim
      if text.nonEmpty then
        todoStore.add(text)
        input.setText("")
        renderList()

    input.addActionListener(_ => addTask())
    addButton.addActionListener(_ => addTask())

    panel.add(inputRow, BorderLayout.NORTH)
    panel.add(scroll, BorderLayout.CENTER)
    renderList()
    panel

  private def todoRow(item: TodoItem, refresh: () => Unit): JPanel =
    val row = JPanel(BorderLayout(8, 0))
    row.setOpaque(false)

    val check = JCheckBox(item.text, item.done)
    check.setOpaque(false)
    check.setForeground(new Color(238, 244, 255))
    check.setFont(WidgetTheme.UiFont)
    check.addActionListener(_ =>
      todoStore.toggle(item.id)
      refresh()
    )

    val deleteButton = PillButton("Hapus")
    deleteButton.setPreferredSize(Dimension(72, 28))
    deleteButton.addActionListener(_ =>
      todoStore.delete(item.id)
      refresh()
    )

    row.add(check, BorderLayout.CENTER)
    row.add(deleteButton, BorderLayout.EAST)
    row

  private def createMessagingPanel(): JPanel =
    val panel = baseModulePanel()
    panel.setLayout(BorderLayout(0, 10))

    val top = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
    top.setOpaque(false)

    val title = JLabel("Incoming Notifications")
    title.setFont(Font("Segoe UI", Font.BOLD, 18))
    title.setForeground(new Color(250, 252, 255))

    val clearButton = PillButton("Clear")
    clearButton.setPreferredSize(Dimension(70, 30))
    clearButton.addActionListener(_ =>
      messagingLines = Vector.empty
      if messagingArea != null then messagingArea.setText("")
      trayState = notificationRouter.clearAll()
      tabManager.snapshot.find(_.kind == TabKind.Messaging).foreach(t => tabManager.clearUnread(t.id))
      applyTrayState(trayState)
      rebuildTabs()
    )

    top.add(title)
    top.add(clearButton)

    messagingArea = JTextArea()
    messagingArea.setEditable(false)
    messagingArea.setLineWrap(true)
    messagingArea.setWrapStyleWord(true)
    messagingArea.setFont(WidgetTheme.UiFont)
    messagingArea.setForeground(new Color(238, 244, 255))
    messagingArea.setBackground(new Color(10, 14, 20, 225))
    messagingArea.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createLineBorder(new Color(255, 255, 255, 88), 1, true),
      BorderFactory.createEmptyBorder(10, 10, 10, 10)
    ))
    messagingArea.setText(messagingLines.reverse.mkString("\n\n"))

    val scroll = JScrollPane(messagingArea)
    scroll.setBorder(BorderFactory.createEmptyBorder())

    panel.add(top, BorderLayout.NORTH)
    panel.add(scroll, BorderLayout.CENTER)
    panel

  private def baseModulePanel(): JPanel =
    val panel = RoundedCardPanel(new Color(10, 14, 20, 220), new Color(255, 255, 255, 92), 18)
    panel.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createLineBorder(new Color(255, 255, 255, 92), 1, true),
      BorderFactory.createEmptyBorder(16, 16, 16, 16)
    ))
    panel

  private def onBrowserNotification(tabId: String, note: BrowserNotification): Unit =
    SwingUtilities.invokeLater(() => {
      val signature = s"${note.source}|${note.sender}|${note.body}|${note.url}"
      val now = System.currentTimeMillis()
      if !(signature == lastNotifSig && (now - lastNotifMs) < 3000) then
        lastNotifSig = signature
        lastNotifMs = now

        val msgTabId = tabManager.snapshot.find(_.kind == TabKind.Messaging).map(_.id)
        val target = DeepLink(
          module = TabKind.Messaging,
          tabId = msgTabId,
          url = Some(note.url),
          threadId = Some(s"${note.source}:${note.sender}")
        )

        val preview = s"${note.sender}: ${note.body}".trim.take(140)
        val notif = InboundNotification(
          source = note.source,
          senderDisplay = note.sender.take(48),
          senderAvatarPath = None,
          preview = if preview.nonEmpty then preview else "Notifikasi baru",
          target = target,
          priority = 10
        )

        trayState = notificationRouter.ingest(notif)
        tabManager.incrementUnread(tabId)
        msgTabId.foreach(tabManager.incrementUnread(_))
        appendMessagingLine(
          s"[${note.source}] ${note.sender}\n${if note.body.nonEmpty then note.body else "(tanpa isi)"}\n${note.url}"
        )
        applyTrayState(trayState)
        rebuildTabs()
    })

  private def appendMessagingLine(line: String): Unit =
    messagingLines = (messagingLines :+ line).takeRight(200)
    if messagingArea != null then
      messagingArea.setText(messagingLines.reverse.mkString("\n\n"))

  private def syncPinToggle(): Unit =
    if pinToggle != null then
      val pinned = tabManager.active.exists(_.pinned)
      pinToggle.setSelected(pinned)

  private def isInsideInteractiveArea(pointer: Point): Boolean =
    if anchorWindow == null then return false

    val anchorRect = rectOf(anchorWindow)
    if anchorRect.contains(pointer) then return true

    if mainWindow != null && mainWindow.isVisible then
      val mainRect = rectOf(mainWindow)
      if mainRect.contains(pointer) then return true

      val corridor = safeCorridor(anchorRect, mainRect)
      corridor.contains(pointer)
    else false

  private def safeCorridor(anchorRect: Rect, mainRect: Rect): Rect =
    val corridorWidth = math.max(anchorRect.width + 28, 96)
    val corridorX = math.min(anchorRect.x, mainRect.x) - 8
    val corridorTop = math.min(anchorRect.y, mainRect.y + mainRect.height - 110)
    val corridorBottom = math.max(anchorRect.y + anchorRect.height, mainRect.y + mainRect.height)
    Rect(corridorX, corridorTop, corridorWidth, corridorBottom - corridorTop).inflate(6)

  private def rectOf(window: Window): Rect =
    val b = window.getBounds
    Rect(b.x, b.y, b.width, b.height)

  private def applyTrayState(state: TrayIconState): Unit =
    if anchorLabel == null then return

    if state.badge > 0 then
      val iconOpt = trayIconUrl(state.iconToken).flatMap(url => RemoteIconCache.get(url, 28, 28))
      val sender = state.tooltip.split(":", 2).lift(1).map(_.trim).getOrElse("N")
      val initials = sender
        .split("\\s+")
        .filter(_.nonEmpty)
        .take(2)
        .flatMap(_.headOption)
        .mkString
        .toUpperCase
      val token = if initials.nonEmpty then initials.take(2) else s"N${math.min(state.badge, 9)}"
      anchorLabel.setIcon(iconOpt.orNull)
      anchorLabel.setText(if iconOpt.isDefined then "" else token)
      anchorLabel.setAlertMode(true)
      val preview = state.preview.getOrElse("Ada notifikasi")
      anchorLabel.setToolTipText(s"${state.tooltip}\n$preview")
    else
      anchorLabel.setIcon(null)
      anchorLabel.setText("WD")
      anchorLabel.setAlertMode(false)
      anchorLabel.setToolTipText("WidgetDockPro")

  private def trayIconUrl(token: String): Option[String] =
    token.toLowerCase match
      case "youtube"   => Some("https://www.google.com/s2/favicons?domain=youtube.com&sz=64")
      case "messenger" => Some("https://www.google.com/s2/favicons?domain=messenger.com&sz=64")
      case "whatsapp"  => Some("https://www.google.com/s2/favicons?domain=web.whatsapp.com&sz=64")
      case "telegram"  => Some("https://www.google.com/s2/favicons?domain=web.telegram.org&sz=64")
      case "gmail"     => Some("https://www.google.com/s2/favicons?domain=mail.google.com&sz=64")
      case "x"         => Some("https://www.google.com/s2/favicons?domain=x.com&sz=64")
      case _           => None

  private def weatherClockText(): String =
    val now = java.time.ZonedDateTime.now()
    s"${weatherClockFmt.format(now)} | ${weatherDateFmt.format(now)}"

  private def formatWeatherUpdate(epochMs: Long): String =
    val instant = Instant.ofEpochMilli(epochMs)
    val zoned = instant.atZone(ZoneId.systemDefault())
    s"${weatherClockFmt.format(zoned)} ${weatherDateFmt.format(zoned)}"

  private def forecastCard(day: DailyForecast, icon: Option[ImageIcon]): JPanel =
    val card = RoundedCardPanel(new Color(17, 25, 40, 220), new Color(255, 255, 255, 74), 14)
    card.setLayout(BoxLayout(card, BoxLayout.Y_AXIS))
    card.setPreferredSize(Dimension(128, 150))
    card.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createLineBorder(new Color(255, 255, 255, 74), 1, true),
      BorderFactory.createEmptyBorder(10, 10, 10, 10)
    ))

    val dayLabel = JLabel(day.dayLabel)
    dayLabel.setFont(Font("Segoe UI", Font.BOLD, 13))
    dayLabel.setForeground(new Color(248, 251, 255))
    dayLabel.setAlignmentX(0f)

    val iconLabel = JLabel()
    iconLabel.setAlignmentX(0f)
    iconLabel.setIcon(icon.map(ic => ImageIcon(ic.getImage.getScaledInstance(48, 48, java.awt.Image.SCALE_SMOOTH))).orNull)
    if icon.isEmpty then iconLabel.setText("N/A")
    iconLabel.setForeground(new Color(213, 221, 235))

    val tempLabel = JLabel(f"${day.maxC}%.0f C / ${day.minC}%.0f C")
    tempLabel.setFont(Font("Segoe UI", Font.BOLD, 14))
    tempLabel.setForeground(new Color(191, 219, 254))
    tempLabel.setAlignmentX(0f)

    val conditionLabel = JLabel(day.condition.take(14))
    conditionLabel.setFont(Font("Segoe UI", Font.PLAIN, 11))
    conditionLabel.setForeground(new Color(203, 213, 225))
    conditionLabel.setAlignmentX(0f)
    conditionLabel.setToolTipText(day.condition)

    card.add(dayLabel)
    card.add(Box.createVerticalStrut(4))
    card.add(iconLabel)
    card.add(Box.createVerticalStrut(4))
    card.add(tempLabel)
    card.add(Box.createVerticalStrut(6))
    card.add(conditionLabel)
    card

  private final class RoundedCardPanel(bg: Color, borderColor: Color, radius: Int) extends JPanel:
    setOpaque(false)

    override protected def paintComponent(g: Graphics): Unit =
      val g2 = g.create().asInstanceOf[Graphics2D]
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.setColor(bg)
      g2.fillRoundRect(0, 0, getWidth - 1, getHeight - 1, radius, radius)
      g2.dispose()
      super.paintComponent(g)

    override protected def paintBorder(g: Graphics): Unit =
      val g2 = g.create().asInstanceOf[Graphics2D]
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.setColor(borderColor)
      g2.drawRoundRect(0, 0, getWidth - 1, getHeight - 1, radius, radius)
      g2.dispose()

  private def positionWindows(): Unit =
    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment
    val gd = ge.getDefaultScreenDevice
    val gc = gd.getDefaultConfiguration
    val bounds = gc.getBounds
    val insets: Insets = Toolkit.getDefaultToolkit.getScreenInsets(gc)

    val usableX = bounds.x + insets.left
    val usableY = bounds.y + insets.top
    val usableWidth = bounds.width - insets.left - insets.right
    val usableHeight = bounds.height - insets.top - insets.bottom

    // Mimic taskbar pin location (left-bottom taskbar area) as closely as a normal app allows.
    val taskbarTop = bounds.y + bounds.height - insets.bottom
    val anchorY =
      if insets.bottom > 0 then taskbarTop + math.max(0, (insets.bottom - AnchorWindowSize) / 2)
      else usableY + usableHeight - AnchorWindowSize - 4

    val anchorX = usableX + 6

    if anchorWindow != null then
      anchorWindow.setLocation(anchorX, anchorY)

    if mainWindow != null then
      val targetWidth = math.max(MainWindowWidth, math.min((usableWidth * 0.42).toInt, 760))
      val targetHeight = math.max(MainWindowHeight, (usableHeight * 0.75).toInt)
      mainWindow.setSize(targetWidth, targetHeight)
      val windowX = usableX + 10
      val windowY = (if insets.bottom > 0 then taskbarTop else anchorY) - targetHeight - 8
      mainWindow.setLocation(windowX, math.max(usableY + 12, windowY))

  private def installShutdownHook(): Unit =
    Runtime.getRuntime.addShutdownHook(Thread(() => shutdownAndExit(triggerSystemExit = false)))

  private def shutdownAndExit(triggerSystemExit: Boolean): Unit =
    if !shuttingDown.compareAndSet(false, true) then return

    stopTimers()
    disposeWindows()
    releaseSingleInstance()
    keepAliveLatch.countDown()
    if triggerSystemExit then System.exit(0)

  private def stopTimers(): Unit =
    if pointerTimer != null then pointerTimer.stop()
    if closeTickTimer != null then closeTickTimer.stop()

  private def disposeWindows(): Unit =
    val disposeAction = () =>
      try
        if mainWindow != null then mainWindow.dispose()
      catch case _: Throwable => ()
      try
        if anchorWindow != null then anchorWindow.dispose()
      catch case _: Throwable => ()

    if SwingUtilities.isEventDispatchThread then disposeAction()
    else
      try SwingUtilities.invokeAndWait(() => disposeAction())
      catch case _: Throwable => disposeAction()

  private def acquireSingleInstance(): Boolean =
    val lockPath = appLockPath()
    try
      Files.createDirectories(lockPath.getParent)
      appLockFile = RandomAccessFile(lockPath.toFile, "rw")
      appLock = appLockFile.getChannel.tryLock()
      appLock != null
    catch
      case NonFatal(_) => false

  private def releaseSingleInstance(): Unit =
    try if appLock != null then appLock.release()
    catch case _: Throwable => ()
    try if appLockFile != null then appLockFile.close()
    catch case _: Throwable => ()
    appLock = null
    appLockFile = null

  private def appLockPath(): Path =
    val localAppData = Option(System.getenv("LOCALAPPDATA"))
      .map(Paths.get(_))
      .getOrElse(Paths.get(System.getProperty("java.io.tmpdir")))
    localAppData.resolve("WidgetDockPro").resolve("widgetdock.lock")

