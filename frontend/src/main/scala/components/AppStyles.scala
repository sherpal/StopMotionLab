package components

import com.raquo.laminar.api.L.*

/** A handful of small visual touches that the UI5 Web Components don't expose through attributes or slots: hover
  * lift on the storyboard thumbnails, a selection glow, a slim custom scrollbar for the filmstrip, and a couple of
  * fade-in/pulse animations.
  *
  * Everything lives here, written as plain CSS text in a Scala `String` and mounted once as a single `<style>` tag —
  * no separate `.css` file, per the "Laminar + UI5 only" rule for this app.
  */
object AppStyles {

  private val css =
    """
      |.smlab-thumb {
      |  transition: transform 150ms ease, box-shadow 150ms ease, border-color 150ms ease;
      |  cursor: pointer;
      |}
      |.smlab-thumb:hover {
      |  transform: translateY(-4px);
      |  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.25);
      |}
      |.smlab-thumb--selected {
      |  border-color: var(--sapSelectedColor, #0a6ed1) !important;
      |  box-shadow: 0 0 0 2px var(--sapSelectedColor, #0a6ed1);
      |}
      |.smlab-filmstrip-track {
      |  transition: transform 220ms cubic-bezier(0.22, 1, 0.36, 1);
      |}
      |.smlab-filmstrip-box::-webkit-scrollbar {
      |  height: 8px;
      |}
      |.smlab-filmstrip-box::-webkit-scrollbar-thumb {
      |  background: var(--sapScrollBar_FaceColor, #949494);
      |  border-radius: 4px;
      |}
      |.smlab-fade-in {
      |  animation: smlab-fade-in 220ms ease;
      |}
      |@keyframes smlab-fade-in {
      |  from { opacity: 0; transform: translateY(6px); }
      |  to   { opacity: 1; transform: translateY(0); }
      |}
      |.smlab-framed {
      |  border-radius: 12px;
      |  overflow: hidden;
      |  box-shadow: var(--sapContent_Shadow2, 0 4px 16px rgba(0, 0, 0, 0.2));
      |}
      |.smlab-pulse {
      |  animation: smlab-pulse 1.4s ease-in-out infinite;
      |}
      |@keyframes smlab-pulse {
      |  0%, 100% { opacity: 1; }
      |  50% { opacity: 0.35; }
      |}
      |.smlab-phone-app {
      |  position: fixed;
      |  inset: 0;
      |  display: flex;
      |  flex-direction: column;
      |  gap: 12px;
      |  padding: 12px;
      |  box-sizing: border-box;
      |  background: #000;
      |}
      |.smlab-phone-video-wrap {
      |  flex: 1 1 auto;
      |  min-height: 0;
      |  display: flex;
      |  align-items: center;
      |  justify-content: center;
      |}
      |.smlab-phone-video {
      |  max-width: 100%;
      |  max-height: 100%;
      |  object-fit: contain;
      |  border-radius: 12px;
      |}
      |.smlab-phone-placeholder {
      |  display: flex;
      |  flex-direction: column;
      |  align-items: center;
      |  gap: 8px;
      |  opacity: 0.6;
      |  color: white;
      |}
      |.smlab-phone-controls {
      |  flex: 0 0 auto;
      |  display: flex;
      |  justify-content: center;
      |}
      |""".stripMargin

  /** Mount once, near the root of the application. */
  def apply(): HtmlElement = styleTag(css)

}
