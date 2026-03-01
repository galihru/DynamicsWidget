package widgetdock.core

object HoverStateMachine:

  def reduce(
      state: AppState,
      event: WidgetEvent,
      closeDebounceMs: Long = 220L
  ): AppState =
    event match
      case WidgetEvent.TrayHoverEnter(_) =>
        state.copy(
          visibility = WidgetVisibility.Expanded,
          closeDeadlineMs = None
        )

      case WidgetEvent.TrayHoverExit(nowMs) =>
        if state.pointerInsideInteractiveArea then state
        else scheduleCloseIfNeeded(state, nowMs, closeDebounceMs)

      case WidgetEvent.PointerMoved(_, insideInteractiveArea, nowMs) =>
        val next = state.copy(pointerInsideInteractiveArea = insideInteractiveArea)
        if insideInteractiveArea then
          next.visibility match
            case WidgetVisibility.Collapsing =>
              next.copy(
                visibility = WidgetVisibility.Expanded,
                closeDeadlineMs = None
              )
            case WidgetVisibility.Collapsed =>
              next
            case _ =>
              next.copy(closeDeadlineMs = None)
        else
          scheduleCloseIfNeeded(next, nowMs, closeDebounceMs)

      case WidgetEvent.OpenByDeepLink(target, _) =>
        val nextActive = target.tabId
          .orElse(state.tabs.find(_.kind == target.module).map(_.id))
          .orElse(state.activeTabId)
        state.copy(
          visibility = WidgetVisibility.Expanded,
          activeTabId = nextActive,
          closeDeadlineMs = None
        )

      case WidgetEvent.PinChanged(tabId, pinned, _) =>
        val nextTabs = state.tabs.map { tab =>
          if tab.id == tabId then tab.copy(pinned = pinned) else tab
        }
        state.copy(tabs = nextTabs)

      case WidgetEvent.CloseTimerElapsed(nowMs) =>
        state.closeDeadlineMs match
          case Some(deadline) if nowMs >= deadline =>
            if hasPinnedActive(state) && state.alwaysStayOpenWhenPinned then
              state.copy(
                visibility = WidgetVisibility.PinnedHold,
                closeDeadlineMs = None
              )
            else
              state.copy(
                visibility = WidgetVisibility.Collapsed,
                closeDeadlineMs = None
              )
          case _ => state

      case WidgetEvent.WindowFocusLost(nowMs) =>
        if state.pointerInsideInteractiveArea then state
        else scheduleCloseIfNeeded(state, nowMs, closeDebounceMs)

  private def scheduleCloseIfNeeded(
      state: AppState,
      nowMs: Long,
      closeDebounceMs: Long
  ): AppState =
    state.visibility match
      case WidgetVisibility.Expanded | WidgetVisibility.Expanding |
          WidgetVisibility.PinnedHold =>
        state.copy(
          visibility = WidgetVisibility.Collapsing,
          closeDeadlineMs = Some(nowMs + closeDebounceMs)
        )
      case WidgetVisibility.Collapsing =>
        if state.closeDeadlineMs.isDefined then state
        else state.copy(closeDeadlineMs = Some(nowMs + closeDebounceMs))
      case WidgetVisibility.Collapsed =>
        state

  private def hasPinnedActive(state: AppState): Boolean =
    state.activeTabId.exists(id => state.tabs.exists(t => t.id == id && t.pinned))

