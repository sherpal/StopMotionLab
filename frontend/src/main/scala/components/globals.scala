package components

import urldsl.language.dummyErrorImpl.*
import scala.scalajs.js

def baseStrSpecific: String = js.`import`.meta.env.BASE_URL.asInstanceOf[String]

lazy val baseStr     = baseStrSpecific
inline def base = root / baseStr.filterNot(_ == '/')
