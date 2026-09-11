package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.{IconName, MessageStripDesign}
import be.doeraene.webcomponents.ui5.{Card, Icon, MessageStrip, Text}
import com.raquo.laminar.api.L.*

/** The "Connecter une caméra" card: shows the QR code a phone can scan to pair its camera with this session,
  * a "connected" message once one has, or a waiting message before the websocket itself is up.
  */
object ConnectCameraCard {

  def apply(showQrIdSignal: Signal[Option[String]], currentProviderIdSignal: Signal[Option[String]]): HtmlElement =
    Card.of(
      // see the comment on the Storyboard card in MovieDisplay: ui5-card is inline-block by default and must
      // be pinned to a block box at 100% width, or it shrink-to-fits its content instead of respecting its
      // flex-basis in the row below.
      _ => display.block,
      _ => width.percent := 100,
      _ => boxSizing.borderBox,
      _.slots.header := Card.header.of(
        _.titleText    := "Connecter une caméra",
        _.subtitleText := "Scanne ce QR code avec ton téléphone"
      ),
      _ =>
        div(
          padding.px := 16,
          display.flex,
          flexDirection.column,
          alignItems.center,
          gap.px       := 12,
          minHeight.px := 200,
          child <-- showQrIdSignal.combineWithFn(currentProviderIdSignal) {
            case (Some(id), _) =>
              div(
                cls("smlab-fade-in"),
                display.flex,
                flexDirection.column,
                alignItems.center,
                gap.px := 8,
                img(
                  className := "smlab-framed",
                  widthAttr := 200,
                  src       := s"/api/phone-connect-qrcode?editorId=$id",
                  alt       := "Scan with your phone to connect its camera"
                ),
                Text("Ouvre l'appareil photo de ton téléphone et scanne ce code")
              )
            case (None, Some(_)) =>
              MessageStrip.of(
                _.design          := MessageStripDesign.Positive,
                _.hideCloseButton := true,
                _ => "Téléphone connecté !"
              )
            case (None, None) =>
              div(
                display.flex,
                alignItems.center,
                gap.px  := 8,
                opacity := 0.6,
                Icon.of(_.name := IconName.disconnected),
                Text("En attente de la connexion au serveur…")
              )
          }
        )
    )

}
