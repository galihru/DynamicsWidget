package widgetdock.ui

import java.awt.{BasicStroke, Color, GradientPaint, Graphics, Graphics2D, RenderingHints}
import javax.swing.{JPanel, Timer}

final class WeatherScenePanel extends JPanel:

  enum Scene:
    case Clear
    case Cloudy
    case Rain
    case Storm

  private var scene: Scene = Scene.Clear
  private var tick = 0
  private val animator = Timer(45, _ => {
    tick = (tick + 1) % 100000
    repaint()
  })

  setOpaque(false)
  animator.start()

  def setConditionLabel(condition: String): Unit =
    val c = condition.toLowerCase
    scene =
      if c.contains("badai") || c.contains("petir") || c.contains("storm") || c.contains("thunder") then
        Scene.Storm
      else if c.contains("hujan") || c.contains("gerimis") || c.contains("rain") || c.contains("drizzle") then
        Scene.Rain
      else if c.contains("awan") || c.contains("kabut") || c.contains("cloud") || c.contains("fog") then
        Scene.Cloudy
      else Scene.Clear
    repaint()

  override protected def paintComponent(g: Graphics): Unit =
    super.paintComponent(g)
    val g2 = g.create().asInstanceOf[Graphics2D]
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    val w = getWidth
    val h = getHeight

    val (top, bottom) = scene match
      case Scene.Clear  => (new Color(77, 171, 247), new Color(29, 78, 216))
      case Scene.Cloudy => (new Color(100, 116, 139), new Color(51, 65, 85))
      case Scene.Rain   => (new Color(71, 85, 105), new Color(30, 41, 59))
      case Scene.Storm  => (new Color(55, 65, 81), new Color(17, 24, 39))

    g2.setPaint(new GradientPaint(0, 0, top, 0, h, bottom))
    g2.fillRoundRect(0, 0, w, h, 22, 22)

    drawCloud(g2, w * 0.3, h * 0.32, 1.0)
    drawCloud(g2, w * 0.62, h * 0.28, 0.9)

    scene match
      case Scene.Clear =>
        drawSun(g2, (w * 0.78).toInt, (h * 0.26).toInt, 28)
      case Scene.Cloudy =>
        ()
      case Scene.Rain =>
        drawRain(g2, w, h, heavy = false)
      case Scene.Storm =>
        drawRain(g2, w, h, heavy = true)
        drawLightning(g2, w, h)

    g2.dispose()

  private def drawSun(g2: Graphics2D, cx: Int, cy: Int, r: Int): Unit =
    g2.setColor(new Color(253, 224, 71, 230))
    g2.fillOval(cx - r, cy - r, r * 2, r * 2)
    g2.setColor(new Color(254, 240, 138, 240))
    g2.setStroke(new BasicStroke(2f))
    var i = 0
    while i < 12 do
      val a = (Math.PI * 2 / 12) * i
      val x1 = cx + (r + 4) * Math.cos(a)
      val y1 = cy + (r + 4) * Math.sin(a)
      val x2 = cx + (r + 12) * Math.cos(a)
      val y2 = cy + (r + 12) * Math.sin(a)
      g2.drawLine(x1.toInt, y1.toInt, x2.toInt, y2.toInt)
      i += 1

  private def drawCloud(g2: Graphics2D, x: Double, y: Double, scale: Double): Unit =
    val w = (120 * scale).toInt
    val h = (52 * scale).toInt
    val xx = x.toInt
    val yy = y.toInt
    g2.setColor(new Color(226, 232, 240, 210))
    g2.fillOval(xx, yy, w / 2, h / 2)
    g2.fillOval(xx + w / 5, yy - h / 4, w / 2, h / 2)
    g2.fillOval(xx + w / 2, yy, w / 2, h / 2)
    g2.fillRoundRect(xx + 12, yy + h / 5, w - 24, h / 2, 20, 20)

  private def drawRain(g2: Graphics2D, w: Int, h: Int, heavy: Boolean): Unit =
    val drops = if heavy then 42 else 28
    val speed = if heavy then 18 else 11
    g2.setColor(new Color(147, 197, 253, 220))
    g2.setStroke(new BasicStroke(if heavy then 2f else 1.4f))
    var i = 0
    while i < drops do
      val x = ((i * 29 + tick * 2) % (w + 40)) - 20
      val y = ((i * 47 + tick * speed) % (h + 60)) - 30
      g2.drawLine(x, y, x - 4, y + (if heavy then 16 else 12))
      i += 1

  private def drawLightning(g2: Graphics2D, w: Int, h: Int): Unit =
    if (tick / 18) % 8 == 0 then
      g2.setColor(new Color(254, 240, 138, 230))
      g2.setStroke(new BasicStroke(3f))
      val x = (w * 0.7).toInt
      val y = (h * 0.22).toInt
      g2.drawLine(x, y, x - 18, y + 34)
      g2.drawLine(x - 18, y + 34, x - 2, y + 34)
      g2.drawLine(x - 2, y + 34, x - 24, y + 70)
