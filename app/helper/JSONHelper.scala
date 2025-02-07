package helper

import play.api.libs.json.{Json, JsValue, JsString, JsObject}

/**
 * Created by Patrick Robinson on 07.05.2015.
 */
object JSONHelper {
	def simpleResponse(typ : String, description : String) : JsObject = Json.obj(
		"type" -> JsString(typ)
	,   "description" -> JsString(description)
	)

	def objectResponse(typ : String, obj: JsObject) : JsObject = Json.obj(
		"type" -> JsString(typ)
	,   "content" -> obj
	)

	def JsStringToString(jsString: JsValue) : String = {
		val tmp = jsString.toString()
		return tmp.substring(1, tmp.length - 1)
	}
}
