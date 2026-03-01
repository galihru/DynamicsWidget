package widgetdock.ui

import java.awt.{
  AlphaComposite,
  BasicStroke,
  Color,
  Font,
  GradientPaint,
  Graphics,
  Graphics2D,
  GraphicsEnvironment,
  Rectangle,
  RenderingHints,
  Robot
}
import java.awt.geom.RoundRectangle2D
import java.awt.image.{BufferedImage, ConvolveOp, Kernel}
import javax.swing.{
  AbstractButton,
  JButton,
  JLabel,
  JPanel,
  JToggleButton,
  SwingConstants
}
import scala.util.control.NonFatal

object WidgetTheme:
  val TitleFont: Font = new Font("Segoe UI", Font.BOLD, 22)
  val SubtitleFont: Font = new Font("Segoe UI", Font.PLAIN, 12)
  val UiFont: Font = new Font("Segoe UI", Font.PLAIN, 13)
  val UiBoldFont: Font = new Font("Segoe UI", Font.BOLD, 13)

  val GlassTop: Color = new Color(34, 40, 54, 176)
  val GlassBottom: Color = new Color(19, 23, 33, 210)
  val GlassBorder: Color = new Color(255, 255, 255, 64)
  val Accent: Color = new Color(59, 130, 246, 230)
  val TextPrimary: Color = new Color(245, 247, 250)
  val TextSecondary: Color = new Color(183, 190, 207)
  val SearchBg: Color = new Color(13, 17, 24, 190)
  val SearchBorder: Color = new Color(255, 255, 255, 48)
  val ButtonBg: Color = new Color(30, 36, 49, 185)
  val ButtonBgHover: Color = new Color(39, 48, 66, 215)
  val ButtonBorder: Color = new Color(255, 255, 255, 46)
  val ButtonDisabled: Color = new Color(81, 88, 104, 180)
  val ButtonTextDisabled: Color = new Color(180, 184, 196, 200)
  val Alert: Color = new Color(231, 76, 60, 235)

final class AnchorBubbleLabel(initialText: String) extends JLabel(initialText):

  private var alertMode = false

  setHorizontalAlignment(SwingConstants.CENTER)
  setVerticalAlignment(SwingConstants.CENTER)
  setForeground(Color.WHITE)
  setFont(new Font("Segoe UI", Font.BOLD, 15))
  setOpaque(false)

  def setAlertMode(enabled: Boolean): Unit =
    if alertMode != enabled then
      alertMode = enabled
      repaint()

  override protected def paintComponent(g: Graphics): Unit =
    val g2 = g.create().asInstanceOf[Graphics2D]
    g2.setRenderingHint(
      RenderingHints.KEY_ANTIALIASING,
      RenderingHints.VALUE_ANTIALIAS_ON
    )

    val w = getWidth
    val h = getHeight
    val size = math.min(w, h) - 2
    val x = (w - size) / 2
    val y = (h - size) / 2

    // Soft shadow
    g2.setColor(new Color(0, 0, 0, 70))
    g2.fillOval(x + 2, y + 3, size, size)

    val top = if alertMode then new Color(255, 128, 117) else new Color(102, 166, 255)
    val bottom = if alertMode then WidgetTheme.Alert else WidgetTheme.Accent
    g2.setPaint(new GradientPaint(0, y.toFloat, top, 0, (y + size).toFloat, bottom))
    g2.fillOval(x, y, size, size)

    g2.setStroke(new BasicStroke(1.6f))
    g2.setColor(new Color(255, 255, 255, 120))
    g2.drawOval(x, y, size, size)

    super.paintComponent(g2)
    g2.dispose()

final class PillButton(
    text: String,
    accent: Boolean = false
) extends JButton(text):

  configure()

  private def configure(): Unit =
    setOpaque(false)
    setContentAreaFilled(false)
    setBorderPainted(false)
    setFocusPainted(false)
    setForeground(WidgetTheme.TextPrimary)
    setFont(WidgetTheme.UiBoldFont)

  override protected def paintComponent(g: Graphics): Unit =
    val g2 = g.create().asInstanceOf[Graphics2D]
    g2.setRenderingHint(
      RenderingHints.KEY_ANTIALIASING,
      RenderingHints.VALUE_ANTIALIAS_ON
    )

    val bg = resolveBackground(this, accent)
    g2.setColor(bg)
    g2.fillRoundRect(0, 0, getWidth - 1, getHeight - 1, 12, 12)
    g2.setColor(WidgetTheme.ButtonBorder)
    g2.drawRoundRect(0, 0, getWidth - 1, getHeight - 1, 12, 12)

    g2.dispose()
    super.paintComponent(g)

final class PillToggleButton(
    text: String,
    selectedColor: Color = new Color(16, 185, 129, 210)
) extends JToggleButton(text):

  configure()

  private def configure(): Unit =
    setOpaque(false)
    setContentAreaFilled(false)
    setBorderPainted(false)
    setFocusPainted(false)
    setForeground(WidgetTheme.TextPrimary)
    setFont(WidgetTheme.UiBoldFont)

  override protected def paintComponent(g: Graphics): Unit =
    val g2 = g.create().asInstanceOf[Graphics2D]
    g2.setRenderingHint(
      RenderingHints.KEY_ANTIALIASING,
      RenderingHints.VALUE_ANTIALIAS_ON
    )

    val base = if isSelected then selectedColor else resolveBackground(this, accent = false)
    g2.setColor(base)
    g2.fillRoundRect(0, 0, getWidth - 1, getHeight - 1, 12, 12)
    g2.setColor(WidgetTheme.ButtonBorder)
    g2.drawRoundRect(0, 0, getWidth - 1, getHeight - 1, 12, 12)

    g2.dispose()
    super.paintComponent(g)

private def resolveBackground(button: AbstractButton, accent: Boolean): Color =
  if !button.isEnabled then WidgetTheme.ButtonDisabled
  else if button.getModel.isPressed then
    if accent then WidgetTheme.Accent.darker() else WidgetTheme.ButtonBgHover.darker()
  else if button.getModel.isRollover then
    if accent then WidgetTheme.Accent else WidgetTheme.ButtonBgHover
  else
    if accent then new Color(37, 99, 235, 220) else WidgetTheme.ButtonBg

final class BlurGlassPanel(
    radius: Int = 24,
    blurRadius: Int = 7
) extends JPanel:

  private var blurredBackdrop: Option[BufferedImage] = None

  setOpaque(false)

  def refreshBackdrop(captureRect: Rectangle): Unit =
    if captureRect.width <= 0 || captureRect.height <= 0 then return
    try
      val screen = GraphicsEnvironment.getLocalGraphicsEnvironment.getDefaultScreenDevice
      val robot = new Robot(screen)
      val capture = robot.createScreenCapture(captureRect)
      blurredBackdrop = Some(gaussianBlur(toArgb(capture), blurRadius))
      repaint()
    catch
      case NonFatal(_) =>
        // Graceful fallback: keep glass gradient only if screen capture is unavailable
        blurredBackdrop = None
        repaint()

  override protected def paintComponent(g: Graphics): Unit =
    val g2 = g.create().asInstanceOf[Graphics2D]
    g2.setRenderingHint(
      RenderingHints.KEY_ANTIALIASING,
      RenderingHints.VALUE_ANTIALIAS_ON
    )

    val w = getWidth
    val h = getHeight
    val clip = new RoundRectangle2D.Float(0, 0, w - 1, h - 1, radius, radius)

    g2.setClip(clip)
    blurredBackdrop.foreach { img =>
      g2.drawImage(img, 0, 0, w, h, null)
    }

    g2.setComposite(AlphaComposite.SrcOver.derive(0.94f))
    g2.setPaint(new GradientPaint(0, 0, WidgetTheme.GlassTop, 0, h, WidgetTheme.GlassBottom))
    g2.fillRoundRect(0, 0, w, h, radius, radius)

    g2.setComposite(AlphaComposite.SrcOver)
    g2.setClip(null)
    g2.setStroke(new BasicStroke(1.2f))
    g2.setColor(WidgetTheme.GlassBorder)
    g2.drawRoundRect(0, 0, w - 1, h - 1, radius, radius)

    g2.dispose()

  private def toArgb(src: BufferedImage): BufferedImage =
    if src.getType == BufferedImage.TYPE_INT_ARGB then src
    else
      val converted = new BufferedImage(src.getWidth, src.getHeight, BufferedImage.TYPE_INT_ARGB)
      val g = converted.createGraphics()
      g.drawImage(src, 0, 0, null)
      g.dispose()
      converted

  private def gaussianBlur(src: BufferedImage, radius: Int): BufferedImage =
    if radius <= 1 then return src

    val kernelSize = radius * 2 + 1
    val sigma = radius / 2.0
    val weights = Array.ofDim[Float](kernelSize)
    var sum = 0.0

    var i = 0
    while i < kernelSize do
      val x = i - radius
      val value = Math.exp(-(x * x) / (2.0 * sigma * sigma))
      weights(i) = value.toFloat
      sum += value
      i += 1

    i = 0
    while i < kernelSize do
      weights(i) = (weights(i) / sum).toFloat
      i += 1

    val horiz = new ConvolveOp(new Kernel(kernelSize, 1, weights), ConvolveOp.EDGE_NO_OP, null)
    val vert = new ConvolveOp(new Kernel(1, kernelSize, weights), ConvolveOp.EDGE_NO_OP, null)

    val tmp = new BufferedImage(src.getWidth, src.getHeight, BufferedImage.TYPE_INT_ARGB)
    val out = new BufferedImage(src.getWidth, src.getHeight, BufferedImage.TYPE_INT_ARGB)
    horiz.filter(src, tmp)
    vert.filter(tmp, out)
    out
